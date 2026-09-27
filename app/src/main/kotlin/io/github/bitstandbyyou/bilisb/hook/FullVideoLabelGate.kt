package io.github.bitstandbyyou.bilisb.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import io.github.bitstandbyyou.bilisb.host.HostTargets
import io.github.bitstandbyyou.bilisb.host.HookProbe
import io.github.bitstandbyyou.bilisb.settings.ModuleSettings
import io.github.bitstandbyyou.bilisb.settings.SettingsKeys
import io.github.bitstandbyyou.bilisb.settings.SettingsSnapshot
import io.github.bitstandbyyou.bilisb.sponsor.FullVideoLabel
import io.github.bitstandbyyou.bilisb.sponsor.FullVideoLabelLookup
import io.github.bitstandbyyou.bilisb.util.AidBvidConverter
import io.github.bitstandbyyou.bilisb.util.info
import io.github.bitstandbyyou.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 所有已验证视频来源最终都路由到 `bilibili://united_video/{aid}`。
 * 在 Activity 启动视频详情页之前查询整段标签；未完成查询时暂缓这次导航，
 * 有标签则先给用户看标签并选择继续或返回，因此不会把播放器内提示冒充为播放前展示。
 */
object FullVideoLabelGate {
    private val pendingIntents = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Intent, Boolean>()),
    )
    private val replayingIntents = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Intent, Boolean>()),
    )
    fun install(module: XposedModule) {
        val methods = Activity::class.java.declaredMethods.filter { method ->
            method.name in setOf("startActivity", "startActivityForResult") &&
                method.parameterTypes.firstOrNull() == Intent::class.java &&
                method.parameterTypes.size in setOf(1, 2, 3)
        }
        if (methods.isEmpty()) {
            HookProbe.miss(module, "fullVideoLabelGate", "Activity start methods not found")
            return
        }

        methods.forEach { method ->
            runCatching { method.isAccessible = true }
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val activity = chain.getThisObject() as? Activity
                    val intent = chain.getArgs().firstOrNull() as? Intent
                    if (activity == null || intent == null || replayingIntents.contains(intent)) {
                        return@intercept chain.proceed()
                    }
                    val bvid = videoBvid(intent) ?: return@intercept chain.proceed()
                    if (pendingIntents.contains(intent)) return@intercept null

                    val settings = runCatching { ModuleSettings.load(module, activity) }
                        .getOrElse {
                            module.warn("pre-play label settings read failed: ${it.message}")
                            SettingsSnapshot.DEFAULT
                    }
                    if (!settings.enabled) return@intercept chain.proceed()
                    val hideRecommendationCard = activity.getSharedPreferences(
                        SettingsKeys.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE,
                    ).getBoolean(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, settings.hideFullVideoLabelCards) &&
                        isRecommendationFeedRoute(intent)

                    pendingIntents.add(intent)
                    HookProbe.first(module, "fullVideoLabelGateCalled", 5) {
                        "${activity.javaClass.name} -> ${intent.component?.className ?: intent.data} " +
                            "aid=${aidValue(intent) ?: intent.data?.lastPathSegment}"
                    }
                    val chainArgs = chain.getArgs()
                    val savedArgs = Array<Any?>(chainArgs.size) { index -> chainArgs[index] }
                    val savedMethod = method
                    val lookup = FullVideoLabelLookup.shared(settings.serverAddress)
                    val navigation = PendingNavigation(
                        activity,
                        intent,
                        savedMethod,
                        savedArgs,
                        module,
                        hideRecommendationCard,
                    )
                    if (!hideRecommendationCard) {
                        navigation.showLoading()
                    }
                    lookup.lookup(bvid) { result -> navigation.onLookup(result) }
                    null
                }
        }
        HookProbe.ok(module, "fullVideoLabelGate", "Activity start methods=${methods.size}")
        module.info("Installed pre-play full-video-label gate methods=${methods.joinToString { it.name + it.parameterCount }}")
    }

    private fun videoBvid(intent: Intent): String? {
        val uri = intent.data
        val videoRoute = uri?.scheme == HostTargets.VIDEO_DETAIL_ROUTE_SCHEME &&
            uri.host == HostTargets.VIDEO_DETAIL_ROUTE_HOST
        val explicitPlayer = intent.component?.className == HostTargets.VIDEO_DETAIL_ACTIVITY
        if (!videoRoute && !explicitPlayer) return null

        val bvid = uri?.getQueryParameter("bvid")
            ?.takeIf { it.startsWith("BV", ignoreCase = true) && it.length > 2 }
            ?: extraString(intent, "bvid")
                ?.takeIf { it.startsWith("BV", ignoreCase = true) && it.length > 2 }
        if (bvid != null) return bvid

        val aidValue = aidValue(intent)
            ?: uri?.getQueryParameter("aid")
            ?: uri?.lastPathSegment
        val aid = aidValue?.removePrefix("av")?.toLongOrNull() ?: return null
        return AidBvidConverter.aidToBvid(aid).takeIf { it.isNotBlank() }
    }

    private class PendingNavigation(
        private val activity: Activity,
        private val intent: Intent,
        private val method: Method,
        private val args: Array<Any?>,
        private val module: XposedModule,
        private val hideRecommendationCard: Boolean,
    ) {
        private var dialog: AlertDialog? = null
        private var finished = false

        fun showLoading() {
            if (!isActivityUsable()) {
                finish()
                return
            }
            dialog = AlertDialog.Builder(activity)
                .setTitle("检查视频标签")
                .setMessage("正在确认是否有整段视频标签…确认前不会进入播放页。")
                .setNegativeButton("取消") { _, _ -> finish() }
                .setCancelable(false)
                .create()
                .also { showSafely(it) }
        }

        fun onLookup(result: FullVideoLabelLookup.Result) {
            if (finished || !isActivityUsable()) {
                finish()
                return
            }
            dismissDialog()
            when (result) {
                is FullVideoLabelLookup.Result.Checked -> {
                    val label = result.label
                    if (label == null) {
                        module.info("pre-play full-video label check: no label")
                        replay()
                    } else if (hideRecommendationCard && shouldHideRecommendationCardNow()) {
                        module.info("tagged recommendation card click canceled; feed filter will hide it")
                        finish()
                    } else {
                        showLabel(label)
                    }
                }
                is FullVideoLabelLookup.Result.Failed -> showFailure(result.statusCode)
            }
        }

        private fun showLabel(label: FullVideoLabel) {
            val title = titleFromIntent(intent)
            val message = buildString {
                append("SponsorBlock 整段视频标签：").append(label.displayName)
                if (title != null) append("\n\n《").append(title).append("》")
                append("\n\n这是播放前提示；继续后仍可按普通片段数据跳过视频中可明确跳过的部分。")
            }
            dialog = AlertDialog.Builder(activity)
                .setTitle("发现整段视频标签")
                .setMessage(message)
                .setPositiveButton("继续观看") { _, _ -> replay() }
                .setNegativeButton("返回") { _, _ -> finish() }
                .setCancelable(false)
                .create()
                .also { showSafely(it) }
            module.info("pre-play full-video label shown category=${label.category}")
        }

        private fun showFailure(statusCode: Int) {
            dialog = AlertDialog.Builder(activity)
                .setTitle("暂时无法确认视频标签")
                .setMessage("标签服务暂不可用（$statusCode）。为保证播放前完成检查，请重试或返回列表；当前不会进入播放页。")
                .setNeutralButton("重试") { _, _ ->
                    dismissDialog()
                    showLoading()
                    val settings = runCatching { ModuleSettings.load(module, activity) }
                        .getOrDefault(SettingsSnapshot.DEFAULT)
                    FullVideoLabelLookup.shared(settings.serverAddress)
                        .lookup(videoBvid(intent).orEmpty()) { onLookup(it) }
                }
                .setNegativeButton("返回列表") { _, _ -> finish() }
                .setCancelable(false)
                .create()
                .also { showSafely(it) }
        }

        private fun replay() {
            if (finished || !isActivityUsable()) {
                finish()
                return
            }
            finish()
            replayingIntents.add(intent)
            try {
                method.invoke(activity, *args)
            } catch (throwable: Throwable) {
                replayingIntents.remove(intent)
                module.warn("pre-play route replay failed: ${throwable.cause?.message ?: throwable.message}")
                showReplayFailure()
                return
            } finally {
                replayingIntents.remove(intent)
            }
        }

        private fun showReplayFailure() {
            finished = false
            pendingIntents.add(intent)
            dialog = AlertDialog.Builder(activity)
                .setTitle("无法打开视频")
                .setMessage("原始播放跳转失败，请返回列表后重试。")
                .setPositiveButton("知道了") { _, _ -> finish() }
                .setCancelable(false)
                .create()
                .also { showSafely(it) }
        }

        private fun finish(removePending: Boolean = true) {
            if (finished) return
            finished = true
            dismissDialog()
            if (removePending) pendingIntents.remove(intent)
        }

        private fun dismissDialog() {
            val current = dialog
            dialog = null
            if (current?.isShowing == true) runCatching { current.dismiss() }
        }

        private fun showSafely(value: AlertDialog) {
            if (!isActivityUsable()) {
                finish()
                return
            }
            runCatching { value.show() }
                .onFailure {
                    module.warn("pre-play label dialog failed: ${it.message}")
                    finish()
                }
        }

        private fun isActivityUsable(): Boolean = !activity.isFinishing && !activity.isDestroyed

        private fun shouldHideRecommendationCardNow(): Boolean {
            val prefs = activity.getSharedPreferences(SettingsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            return prefs.getBoolean(SettingsKeys.ENABLED, true) &&
                prefs.getBoolean(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, true)
        }

        private fun titleFromIntent(intent: Intent): String? {
            val candidate = sequenceOf("title", "av_title", "archive_title")
                .mapNotNull { key -> extraString(intent, key) }
                .firstOrNull { it.isNotBlank() }
                ?: intent.data?.getQueryParameter("title")
            return candidate?.takeIf { it.isNotBlank() }
        }
    }

    private fun extraString(intent: Intent, key: String): String? =
        runCatching { intent.extras?.getString(key) }.getOrNull()

    private fun aidValue(intent: Intent): String? {
        extraString(intent, "aid")?.let { return it }
        val extras = intent.extras ?: return null
        return runCatching { extras.getLong("aid").takeIf { it > 0L }?.toString() }.getOrNull()
            ?: runCatching { extras.getInt("aid").takeIf { it > 0 }?.toString() }.getOrNull()
    }

    private fun isRecommendationFeedRoute(intent: Intent): Boolean =
        intent.data?.getQueryParameter("from_spmid") == "tm.recommend.0.0"
}
