package io.github.bitstandbyyou.bilisb.hook

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.bitstandbyyou.bilisb.host.HostTargets
import io.github.bitstandbyyou.bilisb.host.HookProbe
import io.github.bitstandbyyou.bilisb.host.HookResolve
import io.github.bitstandbyyou.bilisb.settings.SettingsKeys
import io.github.bitstandbyyou.bilisb.sponsor.FullVideoLabelLookup
import io.github.bitstandbyyou.bilisb.util.AidBvidConverter
import io.github.bitstandbyyou.bilisb.util.info
import io.github.bitstandbyyou.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 只处理首页 Pegasus 推荐流卡片；投稿页、动态页和其他列表不在此过滤范围。 */
object RecommendationFullVideoCardFilter {
    private data class BoundCard(
        val item: WeakReference<Any>,
        val adapter: WeakReference<Any>,
        val fragment: WeakReference<Any>,
        val view: WeakReference<View>,
        val bvid: String,
        val serverAddress: String,
    )

    private data class RemovedCard(
        val adapter: WeakReference<Any>,
        val item: Any,
        val originalPosition: Int,
    )

    private val lock = Any()
    private val boundCards = WeakHashMap<Any, BoundCard>()
    private val removedCards = mutableListOf<RemovedCard>()
    private val prefsListeners = WeakHashMap<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val adapterClass = HookResolve.findClass(classLoader, listOf(HostTargets.RECOMMENDATION_CARD_ADAPTER_CLASS))
        if (adapterClass == null) {
            HookProbe.miss(module, "recommendationFullVideoCards", "Pegasus adapter class not found")
            return
        }

        val bindMethod = HookResolve.declaredMethodByShape(
            adapterClass,
            listOf(HostTargets.RECOMMENDATION_CARD_BIND_METHOD),
            arity = 3,
        ) { typeName ->
            typeName.contains("RecyclerView\$ViewHolder") || typeName == "int" || typeName == "java.util.List"
        }?.takeIf { method ->
            method.parameterTypes[0].name.contains("RecyclerView\$ViewHolder") &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(method.parameterTypes[2])
        }
        if (bindMethod == null) {
            HookProbe.miss(module, "recommendationFullVideoCards", "Pegasus onBindViewHolder signature not found")
            return
        }

        module.hook(bindMethod)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val adapter = chain.getThisObject()
                    val args = chain.getArgs()
                    val holder = args.getOrNull(0) ?: return@runCatching
                    val position = args.getOrNull(1) as? Int ?: return@runCatching
                    val data = field(adapter, "b") as? List<*> ?: return@runCatching
                    val item = data.getOrNull(position) ?: return@runCatching
                    val fragment = field(adapter, "d") ?: return@runCatching
                    val view = field(holder, "itemView") as? View ?: return@runCatching
                    onCardBound(module, adapter, holder, item, fragment, view)
                }.onFailure {
                    module.warn("recommendation Pegasus bind failed: ${it.javaClass.simpleName}: ${it.message}")
                }
                result
            }

        HookProbe.ok(module, "recommendationFullVideoCards", "${bindMethod.declaringClass.name}#${bindMethod.name}")
        module.info("Installed full-video label filter on Pegasus recommendation adapter")
    }

    private fun onCardBound(
        module: XposedModule,
        adapter: Any,
        holder: Any,
        item: Any,
        fragment: Any,
        view: View,
    ) {
        val prefs = view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        registerPreferenceListener(prefs, module)

        val isRecommendationFeed = activityFrom(view.context)?.javaClass?.name == "tv.danmaku.bili.MainActivityV2" &&
            fragment.javaClass.name == HostTargets.RECOMMENDATION_FEED_FRAGMENT
        if (!isRecommendationFeed || !shouldHideCards(prefs)) {
            synchronized(lock) { boundCards.remove(holder) }
            return
        }

        val bvid = recommendationBvid(item)
        if (bvid == null) {
            synchronized(lock) { boundCards.remove(holder) }
            return
        }
        val serverAddress = readServerAddress(prefs)
        val previous = synchronized(lock) { boundCards[holder] }
        if (previous?.item?.get() === item && previous.adapter.get() === adapter && previous.fragment.get() === fragment &&
            previous.view.get() === view && previous.bvid == bvid && previous.serverAddress == serverAddress
        ) return

        val bound = BoundCard(
            item = WeakReference(item),
            adapter = WeakReference(adapter),
            fragment = WeakReference(fragment),
            view = WeakReference(view),
            bvid = bvid,
            serverAddress = serverAddress,
        )
        synchronized(lock) { boundCards[holder] = bound }
        checkCard(holder, bound, module)
    }

    private fun checkCard(holder: Any, bound: BoundCard, module: XposedModule) {
        val view = bound.view.get() ?: return
        if (!shouldHideCards(view.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE))) return

        FullVideoLabelLookup.shared(bound.serverAddress).lookup(bound.bvid) { result ->
            val item = bound.item.get() ?: return@lookup
            val adapter = bound.adapter.get() ?: return@lookup
            val fragment = bound.fragment.get() ?: return@lookup
            val currentView = bound.view.get() ?: return@lookup
            val stillBound = synchronized(lock) { boundCards[holder] === bound }
            if (!stillBound || fragment.javaClass.name != HostTargets.RECOMMENDATION_FEED_FRAGMENT ||
                activityFrom(currentView.context)?.javaClass?.name != "tv.danmaku.bili.MainActivityV2"
            ) return@lookup

            if (!shouldHideCards(currentView.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE))) {
                return@lookup
            }

            when (result) {
                is FullVideoLabelLookup.Result.Checked -> {
                    val label = result.label
                    HookProbe.first(module, "recommendationFullVideoCardChecked", 12) {
                        "bvid=${bound.bvid} label=${label?.category ?: "none"}"
                    }
                    if (label != null) {
                        if (removeCard(module, holder, adapter, item)) {
                            HookProbe.first(module, "recommendationFullVideoCardHidden", 12) {
                                "bvid=${bound.bvid} category=${label.category}"
                            }
                        }
                    }
                }
                is FullVideoLabelLookup.Result.Failed -> {
                    module.warn("recommendation full-video label lookup failed status=${result.statusCode} bvid=${bound.bvid}")
                }
            }
        }
    }

    private fun recommendationBvid(item: Any): String? {
        val goTo = property(item, "goTo", "getGoTo")?.toString()
        if (goTo != "av" && goTo != "vertical_av") return null

        val args = property(item, "args", "getArgs")
        val playerArgs = property(item, "playerArgs", "getPlayerArgs")
        val aid = numberProperty(args, "aid", "getAid", "getAvid")
            ?: numberProperty(playerArgs, "aid", "getAid", "getAvid")
            ?: property(item, "param", "getParam")?.toString()?.toLongOrNull()
            ?: uriAid(property(item, "uri", "getUri")?.toString())
            ?: return null
        return AidBvidConverter.aidToBvid(aid).takeIf { it.isNotBlank() }
    }

    private fun uriAid(rawUri: String?): Long? = runCatching {
        val uri = Uri.parse(rawUri ?: return null)
        (uri.getQueryParameter("aid") ?: uri.lastPathSegment)?.removePrefix("av")?.toLongOrNull()
    }.getOrNull()

    private fun numberProperty(target: Any?, fieldName: String, vararg getters: String): Long? {
        if (target == null) return null
        val value = property(target, fieldName, *getters) as? Number ?: return null
        return value.toLong().takeIf { it > 0L }
    }

    private fun property(target: Any?, fieldName: String, vararg getters: String): Any? {
        if (target == null) return null
        HookResolve.invokeNoArg(target, getters.toList())?.let { return it }
        return field(target, fieldName)
    }

    private fun readServerAddress(prefs: SharedPreferences): String =
        prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER)
            ?.takeIf { it.isNotBlank() } ?: SettingsKeys.DEFAULT_SERVER

    private fun shouldHideCards(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(SettingsKeys.ENABLED, true) &&
            prefs.getBoolean(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, true)

    private fun removeCard(module: XposedModule, holder: Any, adapter: Any, item: Any): Boolean {
        val items = field(adapter, "b") as? MutableList<Any?> ?: return false
        val index = items.indexOfFirst { it === item }
        if (index < 0) return false

        val removed = runCatching { items.removeAt(index) }.getOrNull() ?: return false
        synchronized(lock) {
            removedCards += RemovedCard(WeakReference(adapter), removed, index)
            boundCards.remove(holder)
        }
        runCatching {
            adapter.javaClass.getMethod("notifyItemRemoved", Int::class.javaPrimitiveType).invoke(adapter, index)
        }.onFailure {
            module.warn("recommendation card removal notify failed: ${it.message}")
            runCatching { adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter) }
        }
        return true
    }

    private fun restoreRemovedCards() {
        val cards = synchronized(lock) { removedCards.toList().asReversed().also { removedCards.clear() } }
        mainHandler.post {
            cards.forEach { removed ->
                val adapter = removed.adapter.get() ?: return@forEach
                val items = field(adapter, "b") as? MutableList<Any?> ?: return@forEach
                if (items.any { it === removed.item }) return@forEach
                val index = removed.originalPosition.coerceIn(0, items.size)
                items.add(index, removed.item)
                runCatching {
                    adapter.javaClass.getMethod("notifyItemInserted", Int::class.javaPrimitiveType).invoke(adapter, index)
                }.onFailure {
                    runCatching { adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter) }
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
                val shouldHide = changed.getBoolean(SettingsKeys.ENABLED, true) &&
                    changed.getBoolean(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, true)
                if (!shouldHide) {
                    restoreRemovedCards()
                } else {
                    val cards = synchronized(lock) { boundCards.entries.map { it.key to it.value } }
                    cards.forEach { (holder, bound) ->
                        bound.view.get()?.post { checkCard(holder, bound, module) }
                    }
                }
            }
            prefsListeners[prefs] = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun activityFrom(context: Context): Context? = when (context) {
        is android.app.Activity -> context
        is android.content.ContextWrapper -> activityFrom(context.baseContext)
        else -> null
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
}
