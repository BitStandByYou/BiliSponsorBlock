package io.github.bitstandbyyou.bilisb.hook

import android.content.Context
import android.content.SharedPreferences
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

/** 播放页「更多视频」列表：只隐藏 SponsorBlock 整段广告标签的 AV 卡片。 */
object PlayerRelatedFullVideoAdFilter {
    private data class BoundCard(
        val item: WeakReference<Any>,
        val bvid: String,
        val serverAddress: String,
    )

    private val lock = Any()
    private val boundCards = WeakHashMap<View, BoundCard>()
    private val hiddenCards = WeakHashMap<View, Boolean>()
    private val originalCardHeights = WeakHashMap<View, Int>()
    private val prefsListeners = WeakHashMap<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val componentClass = HookResolve.findClass(
            classLoader,
            listOf(HostTargets.RELATED_AV_CARD_COMPONENT_CLASS),
        )
        if (componentClass == null) {
            HookProbe.miss(module, "playerRelatedFullVideoAds", "related AV card component not found")
            return
        }

        val bindMethod = HookResolve.declaredMethodByShape(
            componentClass,
            ResolvedTargets.effectiveRelatedAvCardBindMethodNames,
            arity = 2,
        ) { typeName ->
            typeName in HostTargets.RELATED_AV_CARD_BINDING_CLASSES ||
                typeName == "kotlin.coroutines.Continuation"
        }?.takeIf { method ->
            method.parameterTypes[0].name in HostTargets.RELATED_AV_CARD_BINDING_CLASSES &&
                method.parameterTypes[1].name == "kotlin.coroutines.Continuation"
        }

        if (bindMethod == null) {
            HookProbe.miss(module, "playerRelatedFullVideoAds", "related AV card bind method not found")
            return
        }

        module.hook(bindMethod)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val component = chain.getThisObject() ?: return@runCatching
                    val binding = chain.getArgs().firstOrNull() ?: return@runCatching
                    val root = runCatching {
                        binding.javaClass.getMethod("getRoot").invoke(binding) as? View
                    }.getOrNull() ?: return@runCatching
                    // holder 复用时恢复本模块隐藏的卡片尺寸，避免 RecyclerView 留下空白行。
                    restoreCard(root)

                    val card = relatedVideoCard(component) ?: run {
                        synchronized(lock) { boundCards.remove(root) }
                        return@runCatching
                    }
                    val prefs = root.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    registerPreferenceListener(prefs, module)
                    val bound = BoundCard(
                        item = WeakReference(card.first),
                        bvid = card.second,
                        serverAddress = readServerAddress(prefs),
                    )
                    synchronized(lock) { boundCards[root] = bound }
                    checkCard(root, bound, prefs, module)
                }.onFailure {
                    module.warn("player related AV card filter failed: ${it.javaClass.simpleName}: ${it.message}")
                }
                result
            }

        HookProbe.ok(module, "playerRelatedFullVideoAds", methodDescription(bindMethod))
        module.info("Installed full-video ad filter on player related AV cards")
    }

    private fun relatedVideoCard(component: Any): Pair<Any, String>? {
        val contract = field(component, "a") ?: return null
        val item = field(contract, "b") ?: return null
        if (field(item, "a")?.toString() != "AV" || field(item, "b")?.toString() != "av") return null

        val basicInfo = field(item, "c") ?: return null
        if (item.javaClass.name !in HostTargets.RELATED_CARD_MODEL_CLASSES ||
            basicInfo.javaClass.name !in HostTargets.RELATED_CARD_BASIC_INFO_CLASSES
        ) return null
        val aid = (field(basicInfo, "l") as? Number)?.toLong()?.takeIf { it > 0L } ?: return null
        val bvid = AidBvidConverter.aidToBvid(aid).takeIf { it.isNotBlank() } ?: return null
        return item to bvid
    }

    private fun checkCard(view: View, bound: BoundCard, prefs: SharedPreferences, module: XposedModule) {
        if (!shouldHideCards(prefs)) return

        FullVideoLabelLookup.shared(bound.serverAddress).lookup(bound.bvid) { result ->
            val item = bound.item.get() ?: return@lookup
            val isCurrentBinding = synchronized(lock) { boundCards[view] === bound }
            if (!isCurrentBinding || item.javaClass.name !in HostTargets.RELATED_CARD_MODEL_CLASSES) return@lookup
            if (!shouldHideCards(view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE))) {
                return@lookup
            }

            when (result) {
                is FullVideoLabelLookup.Result.Checked -> {
                    val label = result.label
                    HookProbe.first(module, "playerRelatedFullVideoAdChecked", 12) {
                        "bvid=${bound.bvid} category=${label?.category ?: "none"}"
                    }
                    if (FullVideoLabel.isFullVideoAd(label)) {
                        view.post {
                            val stillBound = synchronized(lock) { boundCards[view] === bound }
                            if (stillBound && shouldHideCards(
                                    view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE),
                                )
                            ) {
                                hideCard(view)
                                HookProbe.first(module, "playerRelatedFullVideoAdHidden", 12) {
                                    "bvid=${bound.bvid}"
                                }
                            }
                        }
                    }
                }
                is FullVideoLabelLookup.Result.Failed -> {
                    module.warn("player related full-video label lookup failed status=${result.statusCode} bvid=${bound.bvid}")
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
                            restoreCard(view)
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

    private fun hideCard(view: View) {
        synchronized(lock) {
            if (hiddenCards.put(view, true) == null) {
                view.layoutParams?.let { originalCardHeights[view] = it.height }
            }
        }
        // RecyclerView 仍保留已绑定 child 的测量尺寸；单设 GONE 会留下原卡片高度。
        view.layoutParams?.let { params ->
            if (params.height != 0) {
                params.height = 0
                view.layoutParams = params
            }
        }
        view.visibility = View.GONE
        requestListLayout(view)
    }

    private fun restoreCard(view: View) {
        val wasHidden = synchronized(lock) { hiddenCards.remove(view) != null }
        if (!wasHidden) return

        val originalHeight = synchronized(lock) { originalCardHeights.remove(view) }
        if (originalHeight != null) {
            view.layoutParams?.let { params ->
                if (params.height != originalHeight) {
                    params.height = originalHeight
                    view.layoutParams = params
                }
            }
        }
        view.visibility = View.VISIBLE
        requestListLayout(view)
    }

    private fun requestListLayout(view: View) {
        view.requestLayout()
        var parent = view.parent
        while (parent != null) {
            parent.requestLayout()
            if (parent.javaClass.name == "androidx.recyclerview.widget.RecyclerView") return
            parent = (parent as? View)?.parent
        }
    }

    private fun shouldHideCards(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(SettingsKeys.ENABLED, true) &&
            prefs.getBoolean(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, true)

    private fun readServerAddress(prefs: SharedPreferences): String =
        prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER)
            ?.takeIf { it.isNotBlank() } ?: SettingsKeys.DEFAULT_SERVER

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
