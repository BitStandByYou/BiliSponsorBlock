package io.github.bitstandbyyou.bilisb.hook

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import io.github.bitstandbyyou.bilisb.host.HookProbe
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

/** UP 主投稿「视频」列表：只隐藏 SponsorBlock 标记为 sponsor/full 的单条 AV 视频。 */
object AuthorSpaceFullVideoAdFilter {
    private data class BoundCard(
        val item: WeakReference<Any>,
        val bvid: String,
        val serverAddress: String,
    )

    private val lock = Any()
    private val boundCards = WeakHashMap<View, BoundCard>()
    private val hiddenCards = WeakHashMap<View, Boolean>()
    private val prefsListeners = WeakHashMap<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val classNames = ResolvedTargets.effectiveAuthorVideoCardHolderClasses
        var hooked = 0
        classNames.forEach { className ->
            val holderClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                ?: return@forEach
            val bindMethods = holderClass.declaredMethods.filter(::isVideoBindMethod)
            bindMethods.forEach { method ->
                method.isAccessible = true
                module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val holder = chain.getThisObject() ?: return@runCatching
                            val item = chain.getArgs().firstOrNull() ?: return@runCatching
                            val root = field(holder, "itemView") as? View ?: return@runCatching

                            // RecyclerView 复用时仅撤销本过滤器留下的 GONE。
                            if (synchronized(lock) { hiddenCards.remove(root) != null }) {
                                root.visibility = View.VISIBLE
                            }
                            val bvid = videoBvid(item) ?: run {
                                synchronized(lock) { boundCards.remove(root) }
                                return@runCatching
                            }
                            if (!isAuthorSpace(root.context)) {
                                synchronized(lock) { boundCards.remove(root) }
                                return@runCatching
                            }

                            val prefs = root.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                            registerPreferenceListener(prefs, module)
                            val bound = BoundCard(
                                item = WeakReference(item),
                                bvid = bvid,
                                serverAddress = readServerAddress(prefs),
                            )
                            synchronized(lock) { boundCards[root] = bound }
                            checkCard(root, bound, prefs, module)
                        }.onFailure {
                            module.warn("UP 主投稿整段广告过滤失败：${it.javaClass.simpleName}: ${it.message}")
                        }
                        result
                    }
                HookProbe.ok(module, "authorSpaceFullVideoAds", methodDescription(method))
                hooked++
            }
        }

        if (hooked == 0) {
            HookProbe.miss(
                module,
                "authorSpaceFullVideoAds",
                "video card bind method not found; candidates=${classNames.joinToString()}",
            )
            return
        }
        module.info("已安装 UP 主投稿视频整段广告过滤（$hooked 个卡片绑定方法）")
    }

    private fun isVideoBindMethod(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0].name == HostTargets.AUTHOR_SPACE_VIDEO_MODEL_CLASS &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType

    private fun checkCard(view: View, bound: BoundCard, prefs: SharedPreferences, module: XposedModule) {
        if (!shouldHideCards(prefs)) return

        FullVideoLabelLookup.shared(bound.serverAddress).lookup(bound.bvid) { result ->
            val item = bound.item.get() ?: return@lookup
            val stillBound = synchronized(lock) { boundCards[view] === bound }
            if (!stillBound || item.javaClass.name != HostTargets.AUTHOR_SPACE_VIDEO_MODEL_CLASS || !isAuthorSpace(view.context)) {
                return@lookup
            }
            if (!shouldHideCards(view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE))) {
                return@lookup
            }

            when (result) {
                is FullVideoLabelLookup.Result.Checked -> {
                    val label = result.label
                    HookProbe.first(module, "authorSpaceFullVideoAdChecked", 12) {
                        "bvid=${bound.bvid} category=${label?.category ?: "none"}"
                    }
                    if (FullVideoLabel.isFullVideoAd(label)) {
                        view.post {
                            val currentBinding = synchronized(lock) { boundCards[view] === bound }
                            if (currentBinding && shouldHideCards(
                                    view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE),
                                )
                            ) {
                                view.visibility = View.GONE
                                synchronized(lock) { hiddenCards[view] = true }
                                HookProbe.first(module, "authorSpaceFullVideoAdHidden", 12) {
                                    "bvid=${bound.bvid}"
                                }
                            }
                        }
                    }
                }

                is FullVideoLabelLookup.Result.Failed -> {
                    module.warn("UP 主投稿整段广告标签查询失败 status=${result.statusCode} bvid=${bound.bvid}")
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
                cards.forEach { (view, bound) ->
                    view.post {
                        if (!shouldHide) {
                            if (synchronized(lock) { hiddenCards.remove(view) != null }) {
                                view.visibility = View.VISIBLE
                            }
                        } else {
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

    private fun videoBvid(item: Any): String? {
        val direct = field(item, "bvid")?.toString()?.takeIf { it.isNotBlank() }
        if (direct != null) return direct

        val aid = field(item, "param")?.toString()?.removePrefix("av")?.toLongOrNull()
            ?: runCatching {
                Uri.parse(field(item, "uri")?.toString() ?: return null)
                    .lastPathSegment?.removePrefix("av")?.toLongOrNull()
            }.getOrNull()
            ?: return null
        return AidBvidConverter.aidToBvid(aid).takeIf { it.isNotBlank() }
    }

    private fun isAuthorSpace(context: Context): Boolean {
        var current: Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) {
                var type: Class<*>? = current.javaClass
                while (type != null) {
                    if (type.name == HostTargets.AUTHOR_SPACE_ACTIVITY_CLASS) return true
                    type = type.superclass
                }
                return false
            }
            current = current.baseContext
        }
        return false
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
}
