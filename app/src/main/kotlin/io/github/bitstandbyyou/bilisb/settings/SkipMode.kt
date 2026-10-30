package io.github.bitstandbyyou.bilisb.settings

/**
 * 跳过方式(三选一)。
 *
 * 设置页只有一个单选组,但持久化仍是三个布尔键([SettingsKeys.AUTO_SKIP] /
 * [SettingsKeys.MANUAL_SKIP] / [SettingsKeys.MUTE_SEGMENTS]) —— 保持与旧版本 APK、
 * JSON 镜像、Provider IPC 的互通。读写两端都必须经过本对象归一:
 *   - 读:旧配置可能三键同时为 true(旧 UI 允许自动跳过 + 静音同开),归一到恰好一种模式;
 *   - 写:[toFlags] 产出互斥三元组,避免"隐藏的第二个模式"继续生效。
 *
 * 优先级:手动跳过 > 片段静音 > 自动跳过。三键全 false 的旧配置视为自动跳过(单页没有"全关"项)。
 */
enum class SkipMode(val key: String) {
    AUTO("auto"),
    MANUAL("manual"),
    MUTE("mute"),
    ;

    /** 是否是与自动跳过互斥的"非自动"模式(手动 / 静音)。 */
    val isNonAuto: Boolean get() = this != AUTO

    /** 该模式对应的三键三元组(autoSkip, manualSkip, muteSegments),恰好一个为 true。 */
    fun toFlags(): Triple<Boolean, Boolean, Boolean> = when (this) {
        AUTO -> Triple(true, false, false)
        MANUAL -> Triple(false, true, false)
        MUTE -> Triple(false, false, true)
    }

    companion object {
        /** 从三个布尔归一出唯一模式。 */
        fun fromFlags(autoSkip: Boolean, manualSkip: Boolean, muteSegments: Boolean): SkipMode = when {
            manualSkip -> MANUAL
            muteSegments && !autoSkip -> MUTE
            else -> AUTO
        }

        /** 设置页单选组的初始选中项。 */
        fun fromPreferences(prefs: android.content.SharedPreferences): SkipMode = fromFlags(
            autoSkip = prefs.getBoolean(SettingsKeys.AUTO_SKIP, true),
            manualSkip = prefs.getBoolean(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = prefs.getBoolean(SettingsKeys.MUTE_SEGMENTS, false),
        )

        /** 写入互斥三元组(其余旧组合会被这里覆盖掉)。 */
        fun writeTo(prefs: android.content.SharedPreferences, mode: SkipMode) {
            val (auto, manual, mute) = mode.toFlags()
            prefs.edit()
                .putBoolean(SettingsKeys.AUTO_SKIP, auto)
                .putBoolean(SettingsKeys.MANUAL_SKIP, manual)
                .putBoolean(SettingsKeys.MUTE_SEGMENTS, mute)
                .apply()
        }
    }
}
