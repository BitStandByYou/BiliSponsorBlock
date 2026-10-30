package io.github.bitstandbyyou.bilisb.settings

import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONObject

/**
 * 设置快照的编解码:SharedPreferences / Bundle(Provider IPC)/ JSON(镜像文件)三条通道共用。
 *
 * **只处理用户能改的设置**(见 [SettingsKeys] 的类注释)。服务器地址、缓存 TTL、最小片段时长、
 * 自动跳过倒计时、标记颜色都不再读取 prefs —— 它们固定为代码里的默认值,历史残留键一律忽略,
 * 这样"界面上没有的开关"不会在后台偷偷生效。
 */
object SettingsCodec {
    fun snapshotFromPreferences(prefs: SharedPreferences): SettingsSnapshot {
        return assemble(
            autoSkip = prefs.getBoolean(SettingsKeys.AUTO_SKIP, true),
            manualSkip = prefs.getBoolean(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = prefs.getBoolean(SettingsKeys.MUTE_SEGMENTS, false),
            enabled = prefs.getBoolean(SettingsKeys.ENABLED, true),
            reader = prefs::getBoolean,
        )
    }

    /**
     * 字段映射的唯一实现:Bundle / JSON / SharedPreferences 三条通道都折叠到这里。
     *
     * 为什么用 Map 中转:JVM 单测里 `android.os.Bundle` 全是 stub(没有 Robolectric),
     * 折叠之后「整对象等价」的往返测试可以在无 Android 环境下覆盖全部字段。
     */
    fun snapshotToMap(snapshot: SettingsSnapshot): Map<String, Any> {
        // 快照可能由旧配置构造而来,写出前再归一一次,保证镜像 / IPC 里始终是互斥三元组。
        val mode = SkipMode.fromFlags(snapshot.autoSkip, snapshot.manualSkip, snapshot.muteSegments)
        val (autoSkip, manualSkip, muteSegments) = mode.toFlags()
        return linkedMapOf<String, Any>().apply {
            put(SettingsKeys.ENABLED, snapshot.enabled)
            put(SettingsKeys.AUTO_SKIP, autoSkip)
            put(SettingsKeys.MANUAL_SKIP, manualSkip)
            put(SettingsKeys.MUTE_SEGMENTS, muteSegments)
            SettingsKeys.CATEGORY_MAP.forEach { (key, category) ->
                put(key, snapshot.enabledCategories.contains(category))
            }
            put(SettingsKeys.SHOW_TOAST, snapshot.showToast)
            put(SettingsKeys.SHOW_SEEKBAR_MARKER, snapshot.showSeekbarMarker)
            put(SettingsKeys.SHOW_TIME_DEDUCTION, snapshot.showTimeDeduction)
            put(SettingsKeys.SHOW_SKIP_STATS, snapshot.showSkipStats)
            put(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, snapshot.hideFullVideoLabelCards)
        }
    }

    /**
     * 从 Map 还原快照。
     *
     * 字段级容错(JSON 镜像可能是半截内容或被外部改写):单个字段出错只影响该字段,
     * 退回它的默认值,其余字段照常解析。布尔要求类型严格匹配,不做隐式强转
     * (`"enabled": 1` 这类脏数据不会被当成 true 用)。
     */
    fun snapshotFromMap(values: Map<String, Any?>): SettingsSnapshot {
        fun <T> field(key: String, default: T, cast: (Any) -> T?): T =
            runCatching { values[key]?.let(cast) }.getOrNull() ?: default

        fun bool(key: String, default: Boolean) = field(key, default) { it as? Boolean }

        return assemble(
            autoSkip = bool(SettingsKeys.AUTO_SKIP, true),
            manualSkip = bool(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = bool(SettingsKeys.MUTE_SEGMENTS, false),
            enabled = bool(SettingsKeys.ENABLED, true),
            reader = ::bool,
        )
    }

    fun snapshotFromBundle(bundle: Bundle): SettingsSnapshot {
        val values = HashMap<String, Any?>(bundle.size())
        bundle.keySet().forEach { key -> values[key] = bundle.get(key) }
        return snapshotFromMap(values)
    }

    fun snapshotFromJson(json: JSONObject): SettingsSnapshot {
        val values = HashMap<String, Any?>(json.length())
        json.keys().forEach { key -> values[key] = json.opt(key) }
        return snapshotFromMap(values)
    }

    fun snapshotToJson(snapshot: SettingsSnapshot): JSONObject {
        return JSONObject().apply {
            snapshotToMap(snapshot).forEach { (key, value) -> put(key, value) }
        }
    }

    fun snapshotToBundle(snapshot: SettingsSnapshot): Bundle {
        return Bundle().apply {
            snapshotToMap(snapshot).forEach { (key, value) ->
                // 与旧形态保持一致:布尔写 Boolean,避免跨进程 Bundle 类型漂移
                if (value is Boolean) putBoolean(key, value) else putString(key, value.toString())
            }
        }
    }

    fun writeSnapshotToPreferences(prefs: SharedPreferences, snapshot: SettingsSnapshot) {
        prefs.edit().apply {
            snapshotToMap(snapshot).forEach { (key, value) ->
                if (value is Boolean) putBoolean(key, value) else putString(key, value.toString())
            }
        }.apply()
    }

    fun defaultSnapshot(): SettingsSnapshot = SettingsSnapshot(
        enabled = true,
        autoSkip = true,
        manualSkip = false,
        muteSegments = false,
        enabledCategories = SettingsKeys.CATEGORY_MAP.values.toSet(),
        showToast = true,
        showSeekbarMarker = true,
        showTimeDeduction = true,
        showSkipStats = true,
        hideFullVideoLabelCards = true,
    )

    /**
     * 三条通道的公共装配点:先把三键归一成唯一模式,再填其余字段。
     *
     * @param reader 按 key 读布尔的读取器(prefs / Map 各一套)
     */
    private fun assemble(
        autoSkip: Boolean,
        manualSkip: Boolean,
        muteSegments: Boolean,
        enabled: Boolean,
        reader: (String, Boolean) -> Boolean,
    ): SettingsSnapshot {
        val mode = SkipMode.fromFlags(autoSkip, manualSkip, muteSegments)
        val (normalizedAuto, normalizedManual, normalizedMute) = mode.toFlags()
        return SettingsSnapshot(
            enabled = enabled,
            autoSkip = normalizedAuto,
            manualSkip = normalizedManual,
            muteSegments = normalizedMute,
            enabledCategories = SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
                reader(key, true)
            }.values.toSet(),
            showToast = reader(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = reader(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = reader(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSkipStats = reader(SettingsKeys.SHOW_SKIP_STATS, true),
            hideFullVideoLabelCards = reader(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS, true),
        )
    }
}
