package io.github.bitstandbyyou.bilisb.hook

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.bitstandbyyou.bilisb.host.HookProbe
import io.github.bitstandbyyou.bilisb.host.HookResolve
import io.github.bitstandbyyou.bilisb.host.HostTargets
import io.github.bitstandbyyou.bilisb.host.ResolvedTargets
import io.github.bitstandbyyou.bilisb.settings.SettingsKeys
import io.github.bitstandbyyou.bilisb.sponsor.FullVideoLabel
import io.github.bitstandbyyou.bilisb.sponsor.FullVideoLabelLookup
import io.github.bitstandbyyou.bilisb.util.AidBvidConverter
import io.github.bitstandbyyou.bilisb.util.info
import io.github.bitstandbyyou.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** 关注流与动态快速浏览共用的视频卡片：命中 sponsor/full 后移除整条动态。 */
object DynamicFullVideoAdFilter {
    private data class BoundCard(
        val model: WeakReference<Any>,
        val aid: Long,
        val dynamicId: Long,
        val bvid: String,
        val serverAddress: String,
    )

    private data class RemovedModule(val index: Int, val model: Any)

    private data class RemovedDynamicCard(
        val adapter: WeakReference<Any>,
        val dynamicId: Long,
        val modules: List<RemovedModule>,
    )

    private val lock = Any()
    private val boundCards = WeakHashMap<View, BoundCard>()
    private val removedCards = mutableListOf<RemovedDynamicCard>()
    private val prefsListeners = WeakHashMap<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val holderClass = runCatching {
            Class.forName(HostTargets.DYNAMIC_VIDEO_CARD_HOLDER_BASE_CLASS, false, classLoader)
        }.getOrNull()
        if (holderClass == null) {
            HookProbe.miss(module, "dynamicFullVideoAds", "dynamic video holder base class not found")
            return
        }

        val bindMethod = HookResolve.declaredMethodByShape(
            holderClass,
            listOf(HostTargets.DYNAMIC_VIDEO_CARD_BIND_METHOD),
            arity = 4,
        ) { typeName ->
            typeName == HostTargets.DYNAMIC_VIDEO_MODEL_BASE_CLASS ||
                typeName == "com.bilibili.bplus.followinglist.module.item.playable.e" ||
                typeName == "com.bilibili.bplus.followinglist.service.Z" ||
                typeName == "java.util.List"
        }?.takeIf { method ->
            method.parameterTypes[0].name == HostTargets.DYNAMIC_VIDEO_MODEL_BASE_CLASS &&
                method.parameterTypes[1].name == "com.bilibili.bplus.followinglist.module.item.playable.e" &&
                method.parameterTypes[2].name == "com.bilibili.bplus.followinglist.service.Z" &&
                List::class.java.isAssignableFrom(method.parameterTypes[3])
        }
        if (bindMethod == null) {
            HookProbe.miss(module, "dynamicFullVideoAds", "dynamic archive bind signature not found")
            return
        }

        module.hook(bindMethod)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val holder = chain.getThisObject() ?: return@runCatching
                    val model = chain.getArgs().firstOrNull() ?: return@runCatching
                    val root = field(holder, "itemView") as? View ?: return@runCatching

                    val aid = (field(model, "j") as? Number)?.toLong()?.takeIf { it > 0L }
                    val bvid = aid?.let(AidBvidConverter::aidToBvid)?.takeIf { it.isNotBlank() }
                    val dynamicId = dynamicIdFor(model)
                    val prefs = root.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    registerPreferenceListener(prefs, module)
                    if (bvid == null || dynamicId == null || !shouldHideCards(prefs)) {
                        synchronized(lock) { boundCards.remove(root) }
                        return@runCatching
                    }

                    val bound = BoundCard(
                        model = WeakReference(model),
                        aid = aid ?: return@runCatching,
                        dynamicId = dynamicId,
                        bvid = bvid,
                        serverAddress = readServerAddress(prefs),
                    )
                    synchronized(lock) { boundCards[root] = bound }
                    checkCard(root, bound, prefs, module)
                }.onFailure {
                    module.warn("动态整段广告过滤失败：${it.javaClass.simpleName}: ${it.message}")
                }
                result
            }

        HookProbe.ok(module, "dynamicFullVideoAds", methodDescription(bindMethod))
        module.info("已安装关注流/动态视频 sponsor/full 整段广告过滤")
    }

    private fun checkCard(view: View, bound: BoundCard, prefs: SharedPreferences, module: XposedModule) {
        if (!shouldHideCards(prefs)) return

        FullVideoLabelLookup.shared(bound.serverAddress).lookup(bound.bvid) { result ->
            val model = bound.model.get() ?: return@lookup
            val currentBinding = synchronized(lock) { boundCards[view] === bound }
            val currentAid = (field(model, "j") as? Number)?.toLong()
            if (!currentBinding || currentAid != bound.aid || dynamicIdFor(model) != bound.dynamicId) return@lookup
            if (!shouldHideCards(view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE))) {
                return@lookup
            }

            when (result) {
                is FullVideoLabelLookup.Result.Checked -> {
                    val label = result.label
                    HookProbe.first(module, "dynamicFullVideoAdChecked", 12) {
                        "bvid=${bound.bvid} category=${label?.category ?: "none"}"
                    }
                    if (FullVideoLabel.isFullVideoAd(label)) {
                        view.post {
                            val stillBound = synchronized(lock) { boundCards[view] === bound }
                            if (!stillBound || !shouldHideCards(
                                    view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE),
                                )
                            ) return@post

                            val removedCount = removeWholeDynamic(view, bound, module)
                            if (removedCount > 0) {
                                HookProbe.first(module, "dynamicFullVideoAdHidden", 12) {
                                    "dynamicId=${bound.dynamicId} modules=$removedCount bvid=${bound.bvid}"
                                }
                            }
                        }
                    }
                }

                is FullVideoLabelLookup.Result.Failed -> {
                    module.warn("动态整段广告标签查询失败 status=${result.statusCode} bvid=${bound.bvid}")
                }
            }
        }
    }

    private fun registerPreferenceListener(prefs: SharedPreferences, module: XposedModule) {
        synchronized(lock) {
            if (prefsListeners.containsKey(prefs)) return
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                if (key != SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS && key != SettingsKeys.ENABLED) {
                    return@OnSharedPreferenceChangeListener
                }
                val shouldHide = shouldHideCards(changed)
                val cards = synchronized(lock) { boundCards.entries.map { it.key to it.value } }
                if (!shouldHide) mainHandler.post { restoreRemovedCards(module) }
                cards.forEach { (view, bound) ->
                    view.post {
                        if (shouldHide) {
                            checkCard(view, bound, changed, module)
                        }
                    }
                }
            }
            prefsListeners[prefs] = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun shouldHideCards(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(SettingsKeys.ENABLED, true) &&
            prefs.getBoolean(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, true)

    private fun readServerAddress(prefs: SharedPreferences): String =
        prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER)
            ?.takeIf { it.isNotBlank() } ?: SettingsKeys.DEFAULT_SERVER

    private fun dynamicIdFor(model: Any): Long? = runCatching {
        val root = model.javaClass.getMethod(ResolvedTargets.effectiveDynamicPostRootMethodName).invoke(model)
            ?: model
        (root.javaClass.getMethod(ResolvedTargets.effectiveDynamicPostIdMethodName).invoke(root) as? Number)
            ?.toLong()
            ?.takeIf { it > 0L }
    }.getOrNull()

    private fun removeWholeDynamic(view: View, bound: BoundCard, module: XposedModule): Int {
        val recycler = enclosingRecyclerView(view) ?: run {
            module.warn("动态整条移除失败：未找到动态列表 RecyclerView dynamicId=${bound.dynamicId}")
            return 0
        }
        val adapter = runCatching { recycler.javaClass.getMethod("getAdapter").invoke(recycler) }.getOrNull()
            ?: return 0
        if (adapter.javaClass.name != ResolvedTargets.effectiveDynamicModuleListAdapterClass) {
            module.warn("动态整条移除失败：列表适配器不匹配 ${adapter.javaClass.name}")
            return 0
        }
        val current = field(adapter, ResolvedTargets.effectiveDynamicModuleListField) as? List<*> ?: return 0
        val removed = current.mapIndexedNotNull { index, item ->
            if (item != null && dynamicIdFor(item) == bound.dynamicId) {
                RemovedModule(index, item)
            } else {
                null
            }
        }
        if (removed.isEmpty()) return 0

        val newItems = current.filter { item -> item == null || dynamicIdFor(item) != bound.dynamicId }
        if (!updateAdapter(adapter, newItems)) return 0

        synchronized(lock) {
            removedCards.removeAll { it.adapter.get() === adapter && it.dynamicId == bound.dynamicId }
            removedCards += RemovedDynamicCard(WeakReference(adapter), bound.dynamicId, removed)
            while (removedCards.size > MAX_REMOVED_DYNAMIC_CARDS) removedCards.removeAt(0)
            boundCards.entries.removeAll { (_, other) -> other.dynamicId == bound.dynamicId }
        }
        return removed.size
    }

    private fun restoreRemovedCards(module: XposedModule) {
        val removed = synchronized(lock) {
            removedCards.toList().asReversed().also { removedCards.clear() }
        }
        removed.forEach { card ->
            val adapter = card.adapter.get() ?: return@forEach
            if (adapter.javaClass.name != ResolvedTargets.effectiveDynamicModuleListAdapterClass) return@forEach
            val current = field(adapter, ResolvedTargets.effectiveDynamicModuleListField) as? List<*> ?: return@forEach
            if (current.any { it != null && dynamicIdFor(it) == card.dynamicId }) return@forEach

            val restored = ArrayList(current)
            card.modules.sortedBy { it.index }.forEach { moduleItem ->
                val index = moduleItem.index.coerceIn(0, restored.size)
                restored.add(index, moduleItem.model)
            }
            if (!updateAdapter(adapter, restored)) {
                module.warn("动态整条恢复失败 dynamicId=${card.dynamicId}")
            }
        }
    }

    private fun updateAdapter(adapter: Any, items: List<*>): Boolean = runCatching {
        val method = adapter.javaClass.getDeclaredMethod(
            ResolvedTargets.effectiveDynamicModuleListUpdateMethodName,
            List::class.java,
        ).apply { isAccessible = true }
        method.invoke(adapter, ArrayList(items))
    }.isSuccess

    private fun enclosingRecyclerView(view: View): Any? {
        var current: android.view.ViewParent? = view.parent
        while (current != null) {
            if (current.javaClass.name == "androidx.recyclerview.widget.RecyclerView") return current
            current = (current as? View)?.parent
        }
        return null
    }

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val value = runCatching {
                type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
            if (value != null) return value
            type = type.superclass
        }
        return null
    }

    private fun methodDescription(method: Method): String =
        "${method.declaringClass.name}#${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"

    private const val MAX_REMOVED_DYNAMIC_CARDS = 64
}
