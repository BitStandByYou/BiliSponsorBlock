package io.github.bitstandbyyou.bilisb.hook

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import io.github.bitstandbyyou.bilisb.host.HookProbe
import io.github.bitstandbyyou.bilisb.host.HostTargets
import io.github.bitstandbyyou.bilisb.settings.SettingsKeys
import io.github.bitstandbyyou.bilisb.sponsor.FullVideoLabel
import io.github.bitstandbyyou.bilisb.sponsor.FullVideoLabelLookup
import io.github.bitstandbyyou.bilisb.util.AidBvidConverter
import io.github.bitstandbyyou.bilisb.util.info
import io.github.bitstandbyyou.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** UP 主本地空间 H5 视频页：根据归档游标响应里的 bvid 隐藏 sponsor/full 视频卡片。 */
object AuthorSpaceH5FullVideoAdFilter {
    private data class ArchiveCard(val bvid: String, val titles: List<String>, val cover: String)

    private val lock = Any()
    private val activeWebViews = WeakHashMap<WebView, WeakReference<Activity>>()
    private val cardsByWebView = WeakHashMap<WebView, MutableMap<String, ArchiveCard>>()
    private val prefsListeners = WeakHashMap<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val activityClass = runCatching {
            Class.forName(HostTargets.LOCAL_AUTHOR_SPACE_ACTIVITY_CLASS, false, classLoader)
        }.getOrNull()
        if (activityClass == null) {
            HookProbe.miss(module, "authorSpaceH5FullVideoAds", "LocalAuthorSpaceActivity not found")
            return
        }

        val onCreate = runCatching { activityClass.getDeclaredMethod("onCreate", Bundle::class.java) }.getOrNull()
        if (onCreate == null) {
            HookProbe.miss(module, "authorSpaceH5FullVideoAds", "onCreate(Bundle) not found")
            return
        }
        module.hook(onCreate)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val activity = chain.getThisObject() as? Activity ?: return@runCatching
                    val hiloWebView = field(activity, "a1") ?: return@runCatching
                    val (webViewField, webView) = HostTargets.HILO_WEBVIEW_INNER_VIEW_FIELDS
                        .firstNotNullOfOrNull { name ->
                            (field(hiloWebView, name) as? WebView)?.let { name to it }
                        } ?: return@runCatching
                    synchronized(lock) { activeWebViews[webView] = WeakReference(activity) }
                    val prefs = activity.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    registerPreferenceListener(prefs, module)
                    HookProbe.first(module, "authorSpaceH5Activity", 3) { activity.javaClass.name }
                    HookProbe.first(module, "authorSpaceH5WebView", 3) {
                        "field=$webViewField view=${webView.javaClass.name}"
                    }
                }.onFailure {
                    module.warn("UP 主 H5 过滤初始化失败：${it.javaClass.simpleName}: ${it.message}")
                }
                result
            }

        val hiloClientClass = runCatching {
            Class.forName(HostTargets.HILO_CLIENT_CLASS, false, classLoader)
        }.getOrNull()
        if (hiloClientClass == null) {
            HookProbe.miss(module, "authorSpaceH5FullVideoAds", "HiloClient not found")
            return
        }

        val pageFinished = declaredMethod(
            hiloClientClass,
            "onPageFinished",
            WebView::class.java,
            String::class.java,
        )
        if (pageFinished != null) {
            module.hook(pageFinished)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val webView = chain.getArgs().firstOrNull() as? WebView
                    if (webView != null && isActiveAuthorSpace(webView)) {
                        installDomController(webView)
                    }
                    result
                }
            HookProbe.ok(module, "authorSpaceH5PageFinished", methodDescription(pageFinished))
        } else {
            HookProbe.miss(module, "authorSpaceH5PageFinished", "HiloClient#onPageFinished not found")
        }

        val interceptRequest = declaredMethod(
            hiloClientClass,
            "shouldInterceptRequest",
            WebView::class.java,
            WebResourceRequest::class.java,
        )
        if (interceptRequest == null) {
            HookProbe.miss(module, "authorSpaceH5ArchiveRequest", "HiloClient#shouldInterceptRequest not found")
        } else {
            module.hook(interceptRequest)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val args = chain.getArgs()
                        val webView = args.getOrNull(0) as? WebView ?: return@runCatching
                        if (!isActiveAuthorSpace(webView)) return@runCatching
                        val request = args.getOrNull(1) as? WebResourceRequest ?: return@runCatching
                        if (request.url?.encodedPath != HostTargets.AUTHOR_SPACE_ARCHIVE_API_PATH) return@runCatching
                        val response = result as? WebResourceResponse ?: return@runCatching
                        val bytes = response.data?.readBytes() ?: return@runCatching
                        // HiloClient 的响应流会被 WebView 消费；读完后换回同内容的新流。
                        response.data = ByteArrayInputStream(bytes)
                        val archiveCards = parseArchiveCards(bytes)
                        if (archiveCards.isEmpty()) return@runCatching

                        synchronized(lock) {
                            val cards = cardsByWebView.getOrPut(webView) { LinkedHashMap() }
                            archiveCards.forEach { cards[it.bvid] = it }
                        }
                        HookProbe.first(module, "authorSpaceH5ArchiveBatch", 6) {
                            "count=${archiveCards.size} first=${archiveCards.first().bvid}"
                        }
                        val activity = synchronized(lock) { activeWebViews[webView]?.get() } ?: return@runCatching
                        val prefs = activity.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                        if (shouldHideCards(prefs)) {
                            archiveCards.forEach { card -> checkCard(webView, card, prefs, module) }
                        }
                    }.onFailure {
                        module.warn("UP 主 H5 视频列表过滤失败：${it.javaClass.simpleName}: ${it.message}")
                    }
                    result
                }
            HookProbe.ok(module, "authorSpaceH5ArchiveRequest", methodDescription(interceptRequest))
        }

        module.info("已安装 UP 主 H5 视频整段广告过滤")
    }

    private fun parseArchiveCards(bytes: ByteArray): List<ArchiveCard> = runCatching {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val items = root.optJSONObject("data")?.optJSONArray("item") ?: return emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                val translatedTitle = item.optString("translated_title").trim()
                val uri = item.optString("uri")
                val directBvid = item.optString("bvid").takeIf { it.startsWith("BV", ignoreCase = true) }
                val aid = item.optString("aid").toLongOrNull()
                    ?: item.optString("param").removePrefix("av").toLongOrNull()
                    ?: uriAid(uri)
                val bvid = directBvid ?: aid?.let(AidBvidConverter::aidToBvid)
                if (!title.isNullOrBlank() && !bvid.isNullOrBlank() && uri.contains("video", ignoreCase = true)) {
                    val titles = buildList {
                        add(title)
                        if (translatedTitle.isNotBlank() && translatedTitle != title) add(translatedTitle)
                    }
                    add(ArchiveCard(bvid, titles, item.optString("cover")))
                }
            }
        }
    }.getOrElse { emptyList() }

    private fun uriAid(rawUri: String): Long? = runCatching {
        val uri = Uri.parse(rawUri)
        (uri.getQueryParameter("aid") ?: uri.lastPathSegment)
            ?.removePrefix("av")
            ?.toLongOrNull()
    }.getOrNull()

    private fun checkCard(webView: WebView, card: ArchiveCard, prefs: SharedPreferences, module: XposedModule) {
        if (!shouldHideCards(prefs) || !isActiveAuthorSpace(webView)) return
        val serverAddress = readServerAddress(prefs)
        FullVideoLabelLookup.shared(serverAddress).lookup(card.bvid) { result ->
            if (!isActiveAuthorSpace(webView) || !shouldHideCards(
                    webView.context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE),
                )
            ) return@lookup

            when (result) {
                is FullVideoLabelLookup.Result.Checked -> {
                    val label = result.label
                    HookProbe.first(module, "authorSpaceH5FullVideoAdChecked", 16) {
                        "bvid=${card.bvid} category=${label?.category ?: "none"}"
                    }
                    if (FullVideoLabel.isFullVideoAd(label)) {
                        hideCardInPage(webView, card, module)
                    }
                }

                is FullVideoLabelLookup.Result.Failed -> {
                    module.warn("UP 主 H5 整段广告标签查询失败 status=${result.statusCode} bvid=${card.bvid}")
                }
            }
        }
    }

    private fun installDomController(webView: WebView) {
        webView.evaluateJavascript(DOM_CONTROLLER_SCRIPT, null)
    }

    private fun hideCardInPage(webView: WebView, card: ArchiveCard, module: XposedModule) {
        val titles = org.json.JSONArray(card.titles).toString()
        val cover = JSONObject.quote(card.cover)
        val script = """
            (function(){
              const state=window.__bilisbFullVideoAds;
              if(!state)return 0;
              return state.hide($titles,$cover);
            })()
        """.trimIndent()
        webView.post {
            if (!isActiveAuthorSpace(webView)) return@post
            webView.evaluateJavascript(script) { result ->
                val hiddenCount = result?.toIntOrNull() ?: 0
                if (hiddenCount > 0) {
                    HookProbe.first(module, "authorSpaceH5FullVideoAdHidden", 16) {
                        "bvid=${card.bvid} cards=$hiddenCount"
                    }
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
                val pages = synchronized(lock) {
                    activeWebViews.entries.mapNotNull { (webView, activityRef) ->
                        val activity = activityRef.get() ?: return@mapNotNull null
                        val cards = cardsByWebView[webView]?.values?.toList().orEmpty()
                        Triple(webView, activity, cards)
                    }
                }
                pages.forEach { (webView, activity, cards) ->
                    webView.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        if (!shouldHideCards(changed)) {
                            webView.evaluateJavascript(DOM_RESTORE_SCRIPT, null)
                        } else {
                            cards.forEach { checkCard(webView, it, changed, module) }
                        }
                    }
                }
            }
            prefsListeners[prefs] = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun isActiveAuthorSpace(webView: WebView): Boolean = synchronized(lock) {
        val activity = activeWebViews[webView]?.get() ?: return@synchronized false
        !activity.isFinishing && !activity.isDestroyed && activity.javaClass.name == HostTargets.LOCAL_AUTHOR_SPACE_ACTIVITY_CLASS
    }

    private fun shouldHideCards(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(SettingsKeys.ENABLED, true) &&
            prefs.getBoolean(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, true)

    private fun readServerAddress(prefs: SharedPreferences): String =
        prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER)
            ?.takeIf { it.isNotBlank() } ?: SettingsKeys.DEFAULT_SERVER

    private fun declaredMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { clazz.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true } }.getOrNull()

    private fun methodDescription(method: Method): String =
        "${method.declaringClass.name}#${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"

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

    private val DOM_CONTROLLER_SCRIPT = """
        (function(){
          if(window.__bilisbFullVideoAds)return 1;
          const entries=new Map();
          const cardSelector='.video-list-card-wrap.archive-video-list__item';
          const applyEntry=entry=>{
            let hidden=0;
            for(const card of document.querySelectorAll(cardSelector)){
              const title=card.querySelector('.video-list-card__title')?.textContent?.trim();
              if(!entry.titles.includes(title))continue;
              if(entry.cover){
                const file=entry.cover.split('/').pop().split('@')[0];
                const images=[...card.querySelectorAll('img')].map(img=>img.currentSrc||img.src||'');
                if(file&&!images.some(src=>src.includes(file)))continue;
              }
              const wasHidden=card.hasAttribute('data-bilisb-hidden');
              if(!card.hasAttribute('data-bilisb-prev-display'))
                card.setAttribute('data-bilisb-prev-display',card.style.display||'');
              card.setAttribute('data-bilisb-hidden','1');
              card.style.setProperty('display','none','important');
              if(!wasHidden)hidden++;
            }
            return hidden;
          };
          const scan=()=>{for(const entry of entries.values())applyEntry(entry);};
          const state={
            hide:(titles,cover)=>{
              const entry={titles,cover};
              entries.set(titles.join('\u0000')+'\u0000'+cover,entry);
              return applyEntry(entry);
            },
            restore:()=>{
              for(const card of document.querySelectorAll('[data-bilisb-hidden]')){
                card.style.display=card.getAttribute('data-bilisb-prev-display')||'';
                card.removeAttribute('data-bilisb-prev-display');
                card.removeAttribute('data-bilisb-hidden');
              }
              entries.clear();
            },
            scan
          };
          window.__bilisbFullVideoAds=state;
          new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});
          return 1;
        })()
    """.trimIndent()

    private val DOM_RESTORE_SCRIPT = "window.__bilisbFullVideoAds&&window.__bilisbFullVideoAds.restore()"
}
