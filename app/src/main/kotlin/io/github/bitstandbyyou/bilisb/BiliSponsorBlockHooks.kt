package io.github.bitstandbyyou.bilisb

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.bitstandbyyou.bilisb.host.DexKitResolver
import io.github.bitstandbyyou.bilisb.host.HookProbe
import io.github.bitstandbyyou.bilisb.host.HookResolve
import io.github.bitstandbyyou.bilisb.host.HostTargets
import io.github.bitstandbyyou.bilisb.player.PlayerBridge
import io.github.bitstandbyyou.bilisb.player.PlayerHandle
import io.github.bitstandbyyou.bilisb.player.VideoDirectorListener
import io.github.bitstandbyyou.bilisb.sponsor.SponsorBlockController
import io.github.bitstandbyyou.bilisb.ui.ProgressMarkerPainter
import io.github.bitstandbyyou.bilisb.ui.RemainingTimeFormatter
import io.github.bitstandbyyou.bilisb.util.info
import io.github.bitstandbyyou.bilisb.util.warn
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Hook 安装与编排中枢。
 *
 * 所有宿主类名与方法候选集中于 [HostTargets]；解析命中由 [HookProbe] 记录，
 * 便于诊断宿主更新后的适配情况。
 */
object BiliSponsorBlockHooks {
    private val installed = ConcurrentHashMap.newKeySet<String>()

    // 这两个字段被多个线程读（进度回调线程 / draw 线程 / 容器绑定线程），必须 @Volatile，
    // 否则设置改动或 controller 重建后，其它线程可能长时间读到旧值。
    @Volatile
    private var sponsorBlockController: SponsorBlockController? = null

    @Volatile
    private var settings: io.github.bitstandbyyou.bilisb.settings.SettingsSnapshot = io.github.bitstandbyyou.bilisb.settings.SettingsSnapshot.DEFAULT

    fun install(module: XposedModule, param: PackageLoadedParam, processName: String) {
        val installKey = "${param.packageName}:$processName"
        if (!installed.add(installKey)) {
            return
        }

        val cl = param.defaultClassLoader
        module.info("Installing hooks for ${param.packageName} process=$processName with $cl")

        // 先用 DexKit 解析被混淆的锚点（候选名全部存在时零开销跳过），再装 hook。
        runCatching {
            DexKitResolver.resolve(module, param.applicationInfo.sourceDir, cl)
        }.onFailure {
            module.warn("DexKitResolver 异常，沿用候选名：${it.javaClass.simpleName}: ${it.message}")
        }

        // aid/cid 消费方：观察者回调 -> controller
        VideoDirectorListener.setVideoIdSink { contextHash, aid, cid ->
            sponsorBlockController?.onVideoIds(contextHash, aid, cid)
        }

        // 注意:此时 Application 尚未创建,无法获取 Context 读取设置。
        // 设置加载延迟到容器绑定回调里,那时能拿到 Context 进行 ContentProvider IPC。
        //
        // 每条安装点单独兜底：某一类找不到/不可 hook 时，不能连带把后面的功能全部丢掉
        // （曾经出现过"一条 hook 抛异常 → 标记/时间扣减/我的页菜单全都没装"的情况）。
        installSafely(module, "directorService") { hookDirectorService(module, cl) }
        installSafely(module, "containerBinding") { hookContainerBinding(module, cl) }
        installSafely(module, "seekTrack") { hookProgressDrawable(module, cl) }
        installSafely(module, "progressCallback") { hookProgressText(module, cl) }
        installSafely(module, "mineMenu") { io.github.bitstandbyyou.bilisb.hook.MineMenuInjector.install(module, cl) }
        installSafely(module, "recommendationFullVideoCards") {
            io.github.bitstandbyyou.bilisb.hook.RecommendationFullVideoCardFilter.install(module, cl)
        }
        installSafely(module, "playerRelatedFullVideoAds") {
            io.github.bitstandbyyou.bilisb.hook.PlayerRelatedFullVideoAdFilter.install(module, cl)
        }
        installSafely(module, "authorSpaceFullVideoAds") {
            io.github.bitstandbyyou.bilisb.hook.AuthorSpaceFullVideoAdFilter.install(module, cl)
        }
        installSafely(module, "authorSpaceH5FullVideoAds") {
            io.github.bitstandbyyou.bilisb.hook.AuthorSpaceH5FullVideoAdFilter.install(module, cl)
        }

        module.info(HookProbe.summary())
    }

    private inline fun installSafely(module: XposedModule, key: String, block: () -> Unit) {
        runCatching { block() }.onFailure { throwable ->
            HookProbe.miss(module, key, "install threw: ${throwable.javaClass.simpleName}: ${throwable.message}")
        }
    }

    // ------------------------------------------------------------------ aid/cid 入口

    /** Hook 观察者注册方法，取得服务实例后注册模块自己的观察者代理。 */
    private fun hookDirectorService(module: XposedModule, cl: ClassLoader) {
        for (className in HostTargets.DIRECTOR_SERVICE_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            // 用「参数类型名」而不是只看参数个数：混淆名 j0 极易撞名，命中错误重载会静默用错语义
            val addMethod = HookResolve.declaredMethodByShape(
                clazz,
                HostTargets.DIRECTOR_ADD_OBSERVER_METHODS,
                1,
                paramTypeName = { it == HostTargets.DIRECTOR_OBSERVER_INTERFACE },
            ) ?: continue

            module.hook(addMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        VideoDirectorListener.registerDirectorService(module, chain.getThisObject())
                    }
                    result
                }
            HookProbe.ok(module, "directorService", "$className#${addMethod.name}")
            return
        }
        HookProbe.miss(module, "directorService", HostTargets.DIRECTOR_SERVICE_CLASSES.joinToString())
    }

    // ------------------------------------------------------------------ 播放器容器绑定

    /**
     * 在 widget 的容器绑定方法上接入播放器，并在同一 widget 上挂
     * `onDetachedFromWindow` 作为**播放器离开**信号。离开信号用于避免：
     * 静音不解除、倒计时离开了还在跑（到点对已废弃的 core seek 并记统计）、按钮/浮层残留、
     * controller 里按 contextHash 的容器强引用永不释放。
     */
    private fun hookContainerBinding(module: XposedModule, cl: ClassLoader) {
        var hooked = false
        var teardownHooked = false
        for (className in HostTargets.CONTAINER_BINDING_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            val method = HookResolve.declaredMethodByShape(
                clazz,
                listOf(HostTargets.BIND_CONTAINER_METHOD),
                1,
                paramTypeName = { it == HostTargets.CONTAINER_INTERFACE },
            ) ?: continue

            hookAfter(module, method, "containerBinding:$className") { chain ->
                val host = chain.getThisObject() ?: return@hookAfter
                val container = chain.getArgs().getOrNull(0)
                // 探针：先确认这个入口到底有没有被调用（宿主类存在 ≠ 这条路径被走到）
                HookProbe.first(module, "bindPlayerContainerCalled", 5) {
                    "${host.javaClass.name} <- ${container?.javaClass?.name ?: "null"}"
                }
                bindPlayer(module, host, container)
            }
            hooked = true

            // 对每个成功挂上 bind 的 widget 类都尝试挂 detach:
            // 两个 widget 类(PlayerSeekWidget3 / PlayerProgressTextWidget)的实例各自独立
            // detach,只挂第一个会让第二个类的 widget 分离时不触发清理。onPlayerLeft 幂等,
            // 重复触发只是多打一条探针。
            HookResolve.methodIncludingInherited(clazz, listOf(HostTargets.WIDGET_DETACH_METHOD))?.let { detach ->
                hookAfter(module, detach, "playerTeardown:$className") { chain ->
                    val host = chain.getThisObject() ?: return@hookAfter
                    if (!clazz.isInstance(host)) return@hookAfter
                    onPlayerLeft(module, host, null)
                }
                teardownHooked = true
            }
        }
        if (!hooked) {
            HookProbe.miss(module, "containerBinding", HostTargets.CONTAINER_BINDING_CLASSES.joinToString())
        }
        if (!teardownHooked) {
            HookProbe.miss(module, "playerTeardown", "widget detach hook not found")
        }
    }

    /**
     * 播放器离开/销毁：取消静音、隐藏浮层与按钮、释放 contextHash 关联状态。
     *
     * 清理入口挂在 widget 的 `onDetachedFromWindow` 上。
     */
    private fun onPlayerLeft(module: XposedModule, host: Any, container: Any?) {
        HookProbe.first(module, "playerTeardownCalled", 5) { host.javaClass.name }
        if (scheduleDeferredTeardown(module, host, container)) {
            module.info("player left: teardown deferred host=${host.javaClass.name}")
        }
    }

    /**
     * 待清理的 contextHash 与登记时刻。
     *
     * 键是 **contextHash** 而不是 host 对象：全屏切换会 detach 旧 widget 再 attach 一个
     * **新的** PlayerSeekWidget3 实例（真机日志证实 bindPlayerContainerCalled #2/#3 是新实例），
     * 按 host 身份匹配永远取消不掉，延迟清理照样执行、状态照样被删空。
     * contextHash（容器的 Context hash）在竖屏/全屏之间是同一个，才能正确撤销。
     */
    private data class PendingTeardown(val contextHash: Int, val registeredAtMs: Long)

    /** 已登记延迟清理的 contextHash 表。进度回调 / bind 到来时按 hash 移除。 */
    private val pendingTeardowns: MutableMap<Int, PendingTeardown> =
        java.util.concurrent.ConcurrentHashMap<Int, PendingTeardown>()

    /** 延迟清理窗口：全屏切换的 detach→attach 间隔远小于它；退出播放页则不会再有回调。 */
    private const val TEARDOWN_DELAY_MS = 3000L

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 计算 host/container 的 contextHash，取不到返回 0（调用方按 0 跳过）。 */
    private fun teardownHashOf(host: Any, container: Any?): Int {
        val context = container?.let { PlayerBridge.context(it) } ?: PlayerBridge.context(host)
        return context?.let { PlayerBridge.contextHash(it) } ?: 0
    }

    /**
     * 登记 contextHash 并起延迟任务。返回 false 表示该 hash 已有待执行的清理（不重复登记）。
     *
     * @param host 触发 detach 的 widget（用于延迟任务里真正清理时解绑 director）
     * @param container 播放器容器（用于算 contextHash；detach 时机上可能拿不到，可传 null）
     */
    private fun scheduleDeferredTeardown(module: XposedModule, host: Any, container: Any?): Boolean {
        val hash = teardownHashOf(host, container)
        if (hash == 0) {
            // 拿不到 context:按 hash 的撤销/清理都不可靠,留探针便于定位状态泄漏。
            HookProbe.first(module, "teardownNoHash", 3) { "host=${host.javaClass.name}" }
            module.info("player left: teardown skipped, no context hash from host=${host.javaClass.name}")
            return false
        }
        val pending = PendingTeardown(hash, android.os.SystemClock.uptimeMillis())
        val first = pendingTeardowns.putIfAbsent(hash, pending) == null
        if (!first) return false
        mainHandler.postDelayed({
            // 必须按 (hash, 本次登记的那一条) 精确移除:detach→attach→detach 叠加时,
            // remove(hash) 会取走**后来者**的登记并用本次闭包里的旧 host 执行清理,
            // 把仍存活的 context 状态删空(症状:当前视频跳过/静音失效)。
            if (pendingTeardowns.remove(hash, pending)) {
                performTeardown(module, host, hash)
            }
        }, TEARDOWN_DELAY_MS)
        return true
    }

    /** 有任何「播放器仍然活着」的信号（bind / 进度回调）时调用：撤销该 context 的待执行清理。 */
    private fun cancelDeferredTeardown(module: XposedModule, hash: Int) {
        if (hash == 0) return
        val removed = pendingTeardowns.remove(hash) != null
        if (removed) {
            HookProbe.first(module, "teardownCancelled", 3) { "context=$hash" }
        }
    }

    /** 真正的清理：只应由 [scheduleDeferredTeardown] 的延迟任务调用。 */
    private fun performTeardown(module: XposedModule, host: Any, contextHash: Int) {
        pendingBindRef.set(null)
        VideoDirectorListener.unregister(host)
        // 必须走按 hash 的清理:此间宿主 widget 多半已 detach,反射取 Context 会失败,
        // onPlayerDestroyed(host) 会把 Int 当 host 用(hash=0 → 状态不清理/静音不解除)。
        sponsorBlockController?.onPlayerContextDestroyed(contextHash)
        module.info("player left: teardown done context=$contextHash host=${host.javaClass.name}")
    }

    /**
     * 待补绑的播放器：`bindPlayerContainer` 触发时 core 往往还没注入，
     * 这时先记下来，等第一次进度回调（那时 widget 已经有 core）再补绑。
     */
    private data class PendingBind(
        val contextHash: Int,
        val container: Any,
        val host: Any,
        val context: android.content.Context,
    )

    /** 补绑用的挂起绑定。AtomicReference 抢占式清空,避免多线程重复 completeBind。 */
    private val pendingBindRef = java.util.concurrent.atomic.AtomicReference<PendingBind?>()

    /**
     * 绑定播放器：取 Context / core 并交给 controller。
     *
     * @param host 触发绑定的 widget
     * @param container 播放器容器
     */
    private fun bindPlayer(module: XposedModule, host: Any, container: Any?) {
        val context = container?.let { PlayerBridge.context(it) }
            ?: PlayerBridge.context(host)
            ?: run {
                HookProbe.first(module, "bindPlayerNoContext", 3) { host.javaClass.name }
                return
            }

        // 全屏切换会先 detach 再 attach 并重新 bind：bind 到来说明播放器还活着，
        // 按 contextHash 撤销延迟清理（新 widget 实例与旧的不是一个对象，不能按身份匹配）。
        cancelDeferredTeardown(module, PlayerBridge.contextHash(context))

        ensureSettingsLoaded(module, context)

        if (!settings.enabled) {
            module.info("player bound but SponsorBlock disabled in settings")
            return
        }

        val contextHash = PlayerBridge.contextHash(context)
        VideoDirectorListener.noteContextHash(contextHash)
        // 之前回调里拿到的 aid/cid 可能因为"还没有容器"被缓存下来，这里补发
        VideoDirectorListener.flushPendingIds(module, contextHash)

        // core 三个来源：widget 自己 -> 容器 -> director 服务（真机实测 bind 时 widget 的 core 还是 null）
        val core = PlayerBridge.coreService(host)
            ?: container?.let { PlayerBridge.coreService(it) }
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService())

        if (core == null) {
            pendingBindRef.set(PendingBind(contextHash, container ?: host, host, context))
            module.info("core not ready at bind time, defer binding context=$contextHash host=${host.javaClass.name}")
            return
        }

        completeBind(module, contextHash, container ?: host, host, core, context)
    }

    /**
     * 状态被误清后的补绑兜底：全屏切换 detach→attach 若在延迟窗口外触发了清理，
     * controller 里该 context 的 state/handle 会被删空且不会再有 bindPlayerContainer。
     * 进度回调仍每帧到来（widget 还在画），这里用 widget 的 core（或 director 服务的）
     * 重建一个 handle 重新登记，恢复跳过/标记链路。core 未就绪时静默放弃（等下一帧）。
     */
    private fun ensureRebindAfterTeardown(module: XposedModule, widget: Any, contextHash: Int) {
        val core = PlayerBridge.coreService(widget)
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService()) ?: return
        // container 必须是 widget 本身(是 View,必有 Context):director 服务不是容器,
        // 对它反射取 Context 会失败,补绑后的 Toast/静音/后续补绑会静默失效。
        val container = widget
        val controller = sponsorBlockController ?: return
        val rebindContext = PlayerBridge.context(widget) ?: PlayerBridge.context(container) ?: return
        controller.bindPlayerHandle(PlayerHandle(contextHash, container, core, rebindContext))
        VideoDirectorListener.noteContextHash(contextHash)
        // 光有 handle 不够:state 只能由 onVideoIds 创建。玩家重建后 Context 实例换了
        // (contextHash 变了),director 回调却只会带着「当时」的旧 hash —— 新 context
        // 永远拿不到 ids,这里每个进度 tick 都会进来空转(真机日志每秒一条 rebound)。
        // 主动从 director 服务的当前条目提取 aid/cid 喂给 onVideoIds,补绑才算闭环。
        if (!controller.latestStateExists(contextHash)) {
            val ids = VideoDirectorListener.currentIdsFromService(module)
            if (ids != null && ids.first > 0 && ids.second > 0) {
                controller.onVideoIds(contextHash, ids.first, ids.second)
                HookProbe.first(module, "rebindFeedIds", 3) {
                    "context=$contextHash aid=${ids.first} cid=${ids.second}"
                }
            }
        }
        HookProbe.first(module, "rebindAfterTeardown", 3) {
            "context=$contextHash widget=${widget.javaClass.name} core=${core.javaClass.name}"
        }
        module.info("player handle rebound after teardown context=$contextHash")
    }

    /** 首次进度回调时补绑（那时 widget 已完成服务注入）。
     *  用 AtomicReference 抢占式清空，避免多条回调并发时对同一 pending 重复 completeBind。 */
    private fun ensureDeferredBind(module: XposedModule, progressWidget: Any) {
        val pending = pendingBindRef.getAndSet(null) ?: return
        // 必须校验「补绑用的 widget」就是当初发起绑定的那个播放器：
        // 否则会用 A 的 contextHash/container 配 B 的 core（小窗/快速切集时串台）。
        if (pending.host !== progressWidget &&
            PlayerBridge.contextHash(progressWidget) != pending.contextHash
        ) {
            // 校验失败:把 pending 放回去,等待真正匹配的 widget
            pendingBindRef.compareAndSet(null, pending)
            return
        }
        val core = PlayerBridge.coreService(progressWidget)
            ?: PlayerBridge.coreServiceFromDirector(VideoDirectorListener.lastDirectorService())
            ?: run {
                // core 还没就绪:放回 pending,等下一次回调
                pendingBindRef.compareAndSet(null, pending)
                return
            }
        completeBind(module, pending.contextHash, pending.container, pending.host, core, pending.context)
    }

    private fun completeBind(
        module: XposedModule,
        contextHash: Int,
        container: Any,
        host: Any,
        core: Any,
        context: android.content.Context,
    ) {
        sponsorBlockController?.bindPlayerHandle(PlayerHandle(contextHash, container, core, context))

        // 探针：从 widget 上尝试获取 director 服务。
        VideoDirectorListener.tryRegisterFromHost(module, host)

        module.info("player bound context=$contextHash host=${host.javaClass.name} core=${core.javaClass.name}")
    }

    /**
     * reload 的最小间隔:bindPlayerContainer 在每次全屏切换/竖屏旋转都会触发,
     * 每次都无条件同步走跨进程 IPC(失败再同步读盘)会把主线程卡在 Binder 上。
     * 间隔内改走 [ModuleSettings.load](命中进程内缓存,零 IPC)。
     */
    private const val SETTINGS_RELOAD_MIN_INTERVAL_MS = 3_000L

    @Volatile
    private var lastSettingsReloadAtMs: Long = 0L

    private fun ensureSettingsLoaded(module: XposedModule, containerContext: android.content.Context) {
        val reloadDue = android.os.SystemClock.uptimeMillis() - lastSettingsReloadAtMs >=
            SETTINGS_RELOAD_MIN_INTERVAL_MS
        val freshSettings = if (reloadDue) {
            lastSettingsReloadAtMs = android.os.SystemClock.uptimeMillis()
            runCatching {
                io.github.bitstandbyyou.bilisb.settings.ModuleSettings.reload(module, containerContext)
            }.getOrElse {
                module.info("ModuleSettings reload failed, using defaults: ${it.message}")
                io.github.bitstandbyyou.bilisb.settings.SettingsSnapshot.DEFAULT
            }
        } else {
            io.github.bitstandbyyou.bilisb.settings.ModuleSettings.load(module, containerContext)
        }

        // 注意：必须先拿旧快照再赋值。`settings` 是同一个字段，若先赋值再比较，
        // `settings != freshSettings` 恒为 false（data class equals 自反），
        // 会导致除总开关外的所有设置改动都不生效（只有重建 controller 才会带上新配置）。
        module.info(
            "Settings snapshot on player enter: enabled=${freshSettings.enabled} " +
                "autoSkip=${freshSettings.autoSkip} server=${freshSettings.serverAddress} " +
                "userId=${redactUserId(freshSettings.userId)}",
        )
        applySnapshot(module, freshSettings, source = "player enter")
    }

    /** 应用一份设置快照，必要时重建 controller。 */
    private fun applySnapshot(
        module: XposedModule,
        freshSettings: io.github.bitstandbyyou.bilisb.settings.SettingsSnapshot,
        source: String,
    ) {
        val previousSettings = settings
        settings = freshSettings

        if (!freshSettings.enabled) {
            sponsorBlockController?.close()
            sponsorBlockController = null
            module.info("SponsorBlock disabled in settings ($source)")
            return
        }

        val currentController = sponsorBlockController
        if (currentController == null || previousSettings != freshSettings) {
            val replacement = SponsorBlockController(module, freshSettings)
            // 状态迁移必须在 close 之前:onVideoIds 只在播放条目变化时触发,同一视频内不会再来,
            // 不迁移的话改任意开关后,当前视频的跳过/静音/手动按钮会整体失效到下一集。
            currentController?.let { replacement.adoptStateFrom(it) }
            // 先赋值再关旧:close→assign 间隙里到来的进度/director 回调会落在已 close 的
            // 旧 controller 上被丢弃;先切换引用则窗口内事件直接进新 controller。
            sponsorBlockController = replacement
            currentController?.close()
            module.info("SponsorBlock controller initialized settingsChanged=${currentController != null} ($source)")
        } else {
            module.info("SponsorBlock controller reused ($source)")
        }
    }

    private fun redactUserId(userId: String): String =
        if (userId.isEmpty()) "-" else userId.take(4) + "…"

    // ------------------------------------------------------------------ 进度回调

    private fun hookProgressText(module: XposedModule, cl: ClassLoader) {
        var callbackHooked = 0

        for (className in HostTargets.PROGRESS_TEXT_WIDGET_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull()
            if (clazz == null) {
                HookProbe.miss(module, "progressWidget:$className", "class not found")
                continue
            }

            // 优先解析整型参数的进度回调候选。
            HookResolve.declaredMethod(
                clazz,
                HostTargets.PROGRESS_CALLBACK_INT_METHODS,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )?.let { method ->
                hookProgressCallback(module, method, "progressInt:$className#${method.name}")
                callbackHooked++
            }

            // 长整型候选只记录探针，不传给 controller，避免把语义未确认的数据用于跳过决策。
            HookResolve.declaredMethod(
                clazz,
                HostTargets.PROGRESS_CALLBACK_LONG_METHODS,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
            )?.let { method ->
                hookProgressCallback(module, method, "progressLong:$className#${method.name}", feedController = false)
                callbackHooked++
            }

            // 时间扣减：拦截 setText(CharSequence, TextView$BufferType)
            HookResolve.declaredMethod(
                clazz,
                listOf("setText"),
                CharSequence::class.java,
                TextView.BufferType::class.java,
            )?.let { method ->
                hookProgressTextViewSetText(module, method, className)
            } ?: HookProbe.miss(module, "timeDeduction:$className", "setText(CharSequence,BufferType) not declared")
        }

        if (callbackHooked == 0) {
            HookProbe.miss(module, "progressCallback", HostTargets.PROGRESS_TEXT_WIDGET_CLASSES.joinToString())
        }
    }

    private fun hookProgressCallback(
        module: XposedModule,
        method: Method,
        key: String,
        feedController: Boolean = true,
    ) {
        hookAfter(module, method, key) { chain ->
            val target = chain.getThisObject() ?: return@hookAfter
            val args = chain.getArgs()
            val positionMs = (args.getOrNull(0) as? Number)?.toLong() ?: return@hookAfter
            val durationMs = (args.getOrNull(1) as? Number)?.toLong() ?: return@hookAfter

            // 探针：确认真正回调的是哪个方法、参数是秒还是毫秒
            HookProbe.first(module, "progressCallbackArgs", 10) {
                "${method.declaringClass.simpleName}#${method.name} arg0=$positionMs arg1=$durationMs" +
                    if (feedController) "" else " (probe-only)"
            }

            if (!feedController) {
                return@hookAfter
            }

            // 参数合理性校验：混淆名同签名的重载可能语义不同（例如把布局参数当进度传进来），
            // 只有 (0 <= position <= duration) 且 duration > 0 才喂给 controller 做跳过决策。
            if (durationMs <= 0 || positionMs < 0 || positionMs > durationMs + 1000) {
                HookProbe.first(module, "progressArgsRejected:$key", 3) {
                    "arg0=$positionMs arg1=$durationMs"
                }
                return@hookAfter
            }

            // 进度回调节点同时用于「补绑」：bindPlayerContainer 时 core 还没注入，
            // 到第一次进度回调时 widget 已经有 core 了。
            ensureDeferredBind(module, target)

            // onProgress 只负责触发跳过/静音；时长扣减显示交给 setText hook。
            val contextHash = contextHash(target)
            if (contextHash != 0) {
                // 进度回调本身就是「播放器还活着」的信号：按 contextHash 撤销延迟清理
                // （全屏切换 detach→attach 后是新的 widget 实例，不能按对象身份匹配）。
                cancelDeferredTeardown(module, contextHash)
                // 状态缺失兜底：全屏切换若触发了清理（延迟窗口之外的边缘时序），
                // state 会被删空且不会再有 bindPlayerContainer。这里从 widget 的 core
                // 与 director 服务重建一个 handle 并重新登记，恢复跳过/标记链路。
                if (sponsorBlockController?.latestStateExists(contextHash) != true) {
                    ensureRebindAfterTeardown(module, target, contextHash)
                }
                sponsorBlockController?.onProgress(contextHash, positionMs, durationMs)
            } else {
                // 取不到 contextHash 时全链路会静默什么都不做，这里留一条探针便于定位
                // （写侧用容器的 Context、读侧用 widget 的 Context，两者必须同一个对象）
                HookProbe.first(module, "progressNoContext:$key", 3) { target.javaClass.name }
            }
        }
    }

    // 防止 setText 递归的标志
    private val isAdjusting = ThreadLocal.withInitial { false }

    private fun hookProgressTextViewSetText(module: XposedModule, method: Method, className: String) {
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // 先执行原方法(设置原始文本)
                val result = chain.proceed()

                // 防递归:如果是我们触发的 setText,跳过
                if (isAdjusting.get() == true) {
                    return@intercept result
                }

                val textView = chain.getThisObject() as? TextView ?: return@intercept result
                val originalText = textView.text ?: return@intercept result
                val contextHash = contextHash(textView)

                val newText = computeAdjustedText(contextHash, originalText)
                if (newText != null && newText.toString() != originalText.toString()) {
                    isAdjusting.set(true)
                    try {
                        textView.text = newText
                    } finally {
                        isAdjusting.set(false)
                    }
                }
                result
            }
        HookProbe.ok(module, "timeDeduction:$className", "setText(CharSequence, BufferType)")
    }

    private fun computeAdjustedText(contextHash: Int, originalText: CharSequence): CharSequence? {
        if (!settings.showTimeDeduction) {
            return null
        }

        // 已经带我们追加的 "(xx:xx)" 后缀时不再处理，避免重复。
        val textStr = originalText.toString()
        if (hasAdjustedDurationSuffix(textStr)) {
            return null
        }

        val controller = sponsorBlockController ?: return null
        val (_, segments) = controller.latestSegments(contextHash) ?: return null
        if (segments.isEmpty()) {
            return null
        }

        val durationMs = extractDurationFromText(textStr)
        if (durationMs <= 0) {
            return null
        }

        // 扣减口径必须与实际跳过策略一致：用同一个最小片段阈值过滤，
        // 且自动/手动跳过都关闭时不做扣减（否则会显示"剩余 xx"但实际没省那么多）。
        val skipEnabled = settings.autoSkip || settings.manualSkip
        val adjustedDurationMs = RemainingTimeFormatter.adjustedDuration(
            durationMs,
            segments,
            minSkipDurationMs = (settings.minSkipDurationSec * 1000).toLong(),
            skipEnabled = skipEnabled,
        )
        if (adjustedDurationMs >= durationMs) {
            return null  // 没有可扣减的片段
        }

        return RemainingTimeFormatter.appendAdjustedDuration(originalText, adjustedDurationMs)
    }

    // 进度文本每次 setText 都会走到这里(UI 线程高频):Regex 提为常量,Kotlin Regex 线程安全可复用
    private val adjustedSuffixRegex = Regex("""\s\(\d{1,3}:\d{2}(?::\d{2})?\)$""")
    private val durationWithHoursRegex = Regex("""(\d+):(\d+):(\d+)\s*/\s*(\d+):(\d+):(\d+)""")
    private val durationNoHoursRegex = Regex("""(\d+):(\d+)\s*/\s*(\d+):(\d+)""")

    private fun hasAdjustedDurationSuffix(text: String): Boolean {
        return adjustedSuffixRegex.containsMatchIn(text)
    }

    private fun extractDurationFromText(text: String): Long {
        // 尝试从 "00:16 / 30:01" 格式中提取总时长
        val match = durationWithHoursRegex.find(text)
        if (match != null) {
            val groups = match.groupValues
            val h = groups.getOrNull(4)?.toLongOrNull() ?: 0
            val m = groups.getOrNull(5)?.toLongOrNull() ?: 0
            val s = groups.getOrNull(6)?.toLongOrNull() ?: 0
            return (h * 3600 + m * 60 + s) * 1000
        }

        // 尝试 "00:16 / 30:01" 格式 (无小时)
        val match2 = durationNoHoursRegex.find(text)
        if (match2 != null) {
            val groups = match2.groupValues
            val m = groups.getOrNull(3)?.toLongOrNull() ?: 0
            val s = groups.getOrNull(4)?.toLongOrNull() ?: 0
            return (m * 60 + s) * 1000
        }

        return -1L
    }

    // ------------------------------------------------------------------ 进度条标记

    /** 进度条片段标记；候选类由 HostTargets 与 DexKitResolver 提供。 */
    private fun hookProgressDrawable(module: XposedModule, cl: ClassLoader) {
        var hooked = 0
        for (className in io.github.bitstandbyyou.bilisb.host.ResolvedTargets.effectiveSeekTrackClasses) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            val method = HookResolve.declaredMethod(clazz, listOf(HostTargets.DRAW_METHOD), Canvas::class.java)
                ?: continue

            hookAfter(module, method, "seekTrack:$className") { chain ->
                val target = chain.getThisObject() ?: return@hookAfter

                // 探针开关判定必须放在探针**之前**:draw 回调每帧都来,用户关掉标记后
                // 探针日志照样每帧打(即使有限频)纯属浪费。
                if (!settings.showSeekbarMarker) {
                    return@hookAfter
                }
                // 探针：按类名分开记录，才能看出「薄轨道 drawable(g)」和「SeekBar 本体(f/子类)」
                // 哪一个在带片段数据的情况下真正在画（原来共用 key，被 first(5/8) 上限吃掉了）
                val targetName = target.javaClass.name
                HookProbe.first(module, "seekTrackCalled:$className", 3) { targetName }

                val canvas = chain.getArgs().getOrNull(0) as? Canvas ?: return@hookAfter

                val contextHash = contextHash(target)
                val markers = sponsorBlockController?.progressMarkers(contextHash) ?: return@hookAfter
                val (durationMs, segments) = markers
                if (segments.isEmpty()) {
                    return@hookAfter
                }

                // 薄轨道优先：如果**同一个 SeekBar 实例**的轨道 drawable（seek.v3.g）刚画过标记，
                // 就不在 SeekBar 本体上重复画（否则会出现一条整高的色块盖住进度条）。
                //
                // 键必须是「实例」而不是 contextHash：竖屏与全屏是两个 SeekBar 实例、却共用同一个
                // Activity Context，用 contextHash 做键会互相抑制（某个方向没标记）。
                val isDrawableTarget = target is Drawable
                val ownerView = ownerViewOf(target)
                if (isDrawableTarget) {
                    if (ownerView != null) {
                        // 单调时钟:墙钟会被 NTP/改时间回拨,窗口计算失真(与其余抑制窗口口径一致)
                        thinTrackPaintedAtByOwner[ownerView] = android.os.SystemClock.uptimeMillis()
                    }
                } else if (ownerView != null) {
                    val lastThin = thinTrackPaintedAtByOwner[ownerView] ?: 0L
                    if (android.os.SystemClock.uptimeMillis() - lastThin < THIN_TRACK_PREFERENCE_MS) {
                        return@hookAfter
                    }
                }

                val geometry = markerBoundsOf(target) ?: return@hookAfter

                // 按「实例」记录，才能在竖屏/全屏两个 SeekBar 实例之间区分开
                val instanceId = Integer.toHexString(System.identityHashCode(target))
                HookProbe.first(module, "seekDraw:$className:$instanceId", 3) {
                    val view = target as? View
                    val location = IntArray(2)
                    if (view != null) runCatching { view.getLocationOnScreen(location) }
                    buildString {
                        append("inst=").append(instanceId)
                        append(" source=").append(geometry.second)
                        append(" rect=").append(geometry.first)
                        if (view != null) {
                            append(" view=").append(view.width).append('x').append(view.height)
                            append(" pad=").append(view.paddingLeft).append(',').append(view.paddingTop)
                            append(',').append(view.paddingRight).append(',').append(view.paddingBottom)
                            append(" loc=").append(location[0]).append(',').append(location[1])
                        }
                        append(" durationMs=").append(durationMs)
                        append(" segments=").append(segments.size)
                    }
                }
                ProgressMarkerPainter.drawInBounds(
                    canvas,
                    geometry.first,
                    durationMs,
                    segments,
                    settings.categoryColors,
                )
            }
            hooked++
        }
        if (hooked == 0) {
            HookProbe.miss(module, "seekTrack", HostTargets.SEEK_TRACK_CLASSES.joinToString())
        }
    }

    /**
     * 计算标记应该画在哪个矩形区域。
     *
     * 优先级：
     *   1. `Drawable` 目标（`seek.v3.g`，实色矩形轨道层）→ 用它自己的 bounds。
     *      这条路径下 canvas 已经被 `ProgressBar.onDraw` 的 `canvas.translate(paddingLeft, paddingTop)`
     *      平移过，所以 bounds 直接可用，**不要再加 padding**；
     *   2. `ProgressBar`（`seek.v3.f` / `PlayerSeekWidget3`）→ 用它的 progressDrawable bounds，
     *      但 **必须补上 (paddingLeft, paddingTop)**：`progressDrawable.bounds` 是「内容盒」坐标
     *      （`onSizeChanged` 里设成 `(0, 0, w - padL - padR, h - padT - padB)`），
     *      而 Hook `View.draw(Canvas)` 拿到的是控件本地坐标。
     *      真机实测（全屏 2493x72、pad=27,9,27,9）：漏掉 padding 会让标记整体左移 27px、上移 9px，
     *      表现就是「彩色标记浮在轨道上方」；
     *   3. 其它 View → 居中的细带（绝不用整高，否则会盖住整个进度条）。
     *
     * @return (区域, 来源描述)，来源用于探针日志判断实际走的是哪条路。
     */
    private fun markerBoundsOf(target: Any): Pair<Rect, String>? = when (target) {
        is Drawable -> {
            val bounds = target.bounds
            if (bounds.isEmpty) null else Rect(bounds) to "drawable"
        }

        is android.widget.ProgressBar -> {
            val drawableBounds = runCatching { target.progressDrawable?.bounds }.getOrNull()
            if (drawableBounds != null && !drawableBounds.isEmpty) {
                Rect(drawableBounds)
                    .also { it.offset(target.paddingLeft, target.paddingTop) } to "progressDrawable+pad"
            } else {
                centeredBand(target)?.let { it to "view-band" }
            }
        }

        is View -> centeredBand(target)?.let { it to "view-band" }
        else -> null
    }

    /** 居中的细带：高度取 min(控件高, 6dp)，避免整高色块。 */
    private fun centeredBand(view: View): Rect? {
        if (view.width <= 0 || view.height <= 0) return null
        val density = view.resources.displayMetrics.density
        val bandHeight = minOf(view.height, (6 * density).toInt().coerceAtLeast(1))
        val top = (view.height - bandHeight) / 2
        return Rect(view.paddingLeft, top, view.width - view.paddingRight, top + bandHeight)
    }

    /**
     * 「某个 SeekBar 实例的轨道 drawable 最近画过标记」的时间表。
     *
     * 键是**承载 drawable 的 View**（`Drawable.callback` 就是宿主 SeekBar），而不是 contextHash：
     * 竖屏/全屏是两个 SeekBar 实例、共用同一个 Activity Context，用 contextHash 会互相抑制。
     * 用弱引用键避免长期持有宿主 View。
     */
    private val thinTrackPaintedAtByOwner: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())
    private const val THIN_TRACK_PREFERENCE_MS = 2000L

    /** 取绘制目标所属的 View：Drawable 用它的 callback（宿主 SeekBar），View 就是它自己。 */
    private fun ownerViewOf(target: Any): View? = when (target) {
        is View -> target
        is Drawable -> target.callback as? View
        else -> null
    }

    // ------------------------------------------------------------------ 工具

    private fun hookAfter(
        module: XposedModule,
        method: Method,
        key: String,
        onAfter: (io.github.libxposed.api.XposedInterface.Chain) -> Unit,
    ) {
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                // 我们的回调异常不能影响宿主，但**必须留日志**：
                // 之前这里是裸 runCatching，异常被静默吞掉，现场完全查不出来（标记整帧消失就是这么来的）。
                runCatching { onAfter(chain) }.onFailure { throwable ->
                    module.warn("hook $key callback failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
                }
                result
            }
        HookProbe.ok(module, key, "${method.declaringClass.name}#${method.name}")
    }

    private fun contextHash(target: Any): Int {
        return runCatching {
            val context = when (target) {
                is View -> target.context
                is Drawable -> {
                    val callback = target.callback
                    when (callback) {
                        is android.view.View -> callback.context
                        else -> null
                    }
                }
                else -> null
            } ?: return 0
            PlayerBridge.contextHash(context)
        }.getOrDefault(0)
    }
}
