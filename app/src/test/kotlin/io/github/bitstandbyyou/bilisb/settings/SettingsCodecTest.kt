package io.github.bitstandbyyou.bilisb.settings

import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettingsCodec 的编解码单测。
 *
 * 覆盖两件事:
 *   1. 可见设置的往返(JSON / Map / Bundle 三条通道);
 *   2. **已废弃设置不会被读进来** —— 老 prefs / 镜像里残留的 server_address、user_id、
 *      min_skip_duration、countdown、color_* 一律忽略,快照取固定默认值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCodecTest {
    @Test
    fun parsesVisibleSettingsAndIgnoresRemovedKeys() {
        val json = JSONObject(
            mapOf(
                SettingsKeys.ENABLED to false,
                SettingsKeys.AUTO_SKIP to false,
                SettingsKeys.MANUAL_SKIP to true,
                SettingsKeys.MUTE_SEGMENTS to true,
                SettingsKeys.CAT_SPONSOR to true,
                SettingsKeys.CAT_INTRO to false,
                SettingsKeys.SHOW_TOAST to false,
                SettingsKeys.SHOW_SEEKBAR_MARKER to false,
                SettingsKeys.SHOW_TIME_DEDUCTION to false,
                SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS to false,
                // ↓ 历史残留键:已从 UI 与读取路径移除,必须被忽略
                "min_skip_duration" to "12.5",
                "skip_countdown" to "3.5",
                "server_address" to "https://mirror.example",
                "cache_ttl_minutes" to "120",
                "user_id" to "0123456789abcdef0123456789abcdef",
                "default_submit_category" to "intro",
                "show_submit_button" to false,
                "color_sponsor" to "#112233",
            )
        )

        val snapshot = SettingsCodec.snapshotFromJson(json)

        assertFalse(snapshot.enabled)
        // manual_skip 优先,旧配置的 auto+mute 同开被归一
        assertFalse(snapshot.autoSkip)
        assertTrue(snapshot.manualSkip)
        assertFalse(snapshot.muteSegments)
        assertTrue("sponsor" in snapshot.enabledCategories)
        assertFalse("intro" in snapshot.enabledCategories)
        assertFalse(snapshot.showToast)
        assertFalse(snapshot.showSeekbarMarker)
        assertFalse(snapshot.showTimeDeduction)
        assertFalse(snapshot.hideFullVideoLabelCards)
    }

    @Test
    fun skipModeIsNormalizedToSingleModeOnRead() {
        // 旧 UI 允许「自动跳过 + 片段静音」同开:归一到自动跳过
        val autoAndMute = SettingsCodec.snapshotFromJson(
            JSONObject(
                mapOf(
                    SettingsKeys.AUTO_SKIP to true,
                    SettingsKeys.MANUAL_SKIP to false,
                    SettingsKeys.MUTE_SEGMENTS to true,
                )
            )
        )
        assertTrue(autoAndMute.autoSkip)
        assertFalse(autoAndMute.muteSegments)

        // 纯静音配置保持静音
        val pureMute = SettingsCodec.snapshotFromJson(
            JSONObject(
                mapOf(
                    SettingsKeys.AUTO_SKIP to false,
                    SettingsKeys.MANUAL_SKIP to false,
                    SettingsKeys.MUTE_SEGMENTS to true,
                )
            )
        )
        assertFalse(pureMute.autoSkip)
        assertTrue(pureMute.muteSegments)

        // 三键全 false 的旧配置没有对应选项,回落为自动跳过
        val allOff = SettingsCodec.snapshotFromJson(
            JSONObject(
                mapOf(
                    SettingsKeys.AUTO_SKIP to false,
                    SettingsKeys.MANUAL_SKIP to false,
                    SettingsKeys.MUTE_SEGMENTS to false,
                )
            )
        )
        assertTrue(allOff.autoSkip)
    }

    @Test
    fun jsonRoundTripIsWholeObjectEqual() {
        val original = richSnapshot()

        val restored = SettingsCodec.snapshotFromJson(SettingsCodec.snapshotToJson(original))

        assertEquals(original, restored)
    }

    /**
     * 字段映射层（Bundle/JSON 共用）整对象等价。
     *
     * Bundle 通道自身无法在纯 JVM 单测里跑（见 [bundleRoundTripIsWholeObjectEqual]），
     * 但它只是 Map ↔ Bundle 的薄适配，字段映射的全部逻辑都由这条用例覆盖。
     */
    @Test
    fun mapRoundTripIsWholeObjectEqual() {
        val original = richSnapshot()

        val restored = SettingsCodec.snapshotFromMap(SettingsCodec.snapshotToMap(original))

        assertEquals(original, restored)
    }

    /**
     * `snapshotToBundle → snapshotFromBundle` 整对象等价。
     *
     * Robolectric 提供真 Bundle 实现，这一条会真正执行（不再被 Assume 永久跳过）。
     */
    @Test
    fun bundleRoundTripIsWholeObjectEqual() {
        Assume.assumeTrue("android.os.Bundle 在本地单测中被 stub，需要 Robolectric", bundleWorks())

        val original = richSnapshot()

        val restored = SettingsCodec.snapshotFromBundle(SettingsCodec.snapshotToBundle(original))

        assertEquals(original, restored)
    }

    /** 空 JSON `{}` → 全默认（缺失字段逐个走各自默认值，等价于 [SettingsSnapshot.DEFAULT]）。 */
    @Test
    fun emptyJsonObjectYieldsAllDefaults() {
        val snapshot = SettingsCodec.snapshotFromJson(JSONObject())

        assertEquals(SettingsSnapshot.DEFAULT, snapshot)
    }

    /** 写出的快照只包含可见设置 + 互斥三元组,不含任何已废弃键。 */
    @Test
    fun snapshotToMapOnlyWritesVisibleSettings() {
        val keys = SettingsCodec.snapshotToMap(richSnapshot()).keys

        val expected = buildSet {
            add(SettingsKeys.ENABLED)
            add(SettingsKeys.AUTO_SKIP)
            add(SettingsKeys.MANUAL_SKIP)
            add(SettingsKeys.MUTE_SEGMENTS)
            addAll(SettingsKeys.CATEGORY_MAP.keys)
            add(SettingsKeys.SHOW_TOAST)
            add(SettingsKeys.SHOW_SEEKBAR_MARKER)
            add(SettingsKeys.SHOW_TIME_DEDUCTION)
            add(SettingsKeys.SHOW_SKIP_STATS)
            add(SettingsKeys.HIDE_FULL_VIDEO_LABEL_CARDS)
        }
        assertEquals(expected, keys)
    }

    /** 非互斥快照在经过 codec 写出时被归一（防止旧配置绕过 UI 传播出去）。 */
    @Test
    fun snapshotToMapNormalizesMutexTriple() {
        val map = SettingsCodec.snapshotToMap(
            SettingsSnapshot.DEFAULT.copy(autoSkip = true, manualSkip = false, muteSegments = true)
        )

        assertEquals(true, map[SettingsKeys.AUTO_SKIP])
        assertEquals(false, map[SettingsKeys.MANUAL_SKIP])
        assertEquals(false, map[SettingsKeys.MUTE_SEGMENTS])
    }

    /**
     * malformed JSON 字段级容错：坏字段只影响该字段，不抛异常、不影响其它字段。
     */
    @Test
    fun malformedFieldsOnlyAffectTheirOwnField() {
        val json = JSONObject(
            """
            {
              "enabled": 1,
              "show_toast": "yes",
              "cat_intro": false
            }
            """.trimIndent()
        )

        val snapshot = SettingsCodec.snapshotFromJson(json)

        // 坏字段：回退各自默认值，不做隐式强转
        assertTrue(snapshot.enabled)
        assertTrue(snapshot.showToast)
        // 好字段：照常解析
        assertFalse("intro" in snapshot.enabledCategories)
    }

    private fun richSnapshot(): SettingsSnapshot = SettingsSnapshot(
        enabled = false,
        autoSkip = false,
        manualSkip = false,
        muteSegments = true,
        enabledCategories = setOf("intro", "outro"),
        showToast = false,
        showSeekbarMarker = false,
        showTimeDeduction = false,
        showSkipStats = false,
        hideFullVideoLabelCards = false,
    )

    /** Bundle 在纯 JVM 单测里是否真的可用（Robolectric / 完整 android.jar 时才为 true）。 */
    private fun bundleWorks(): Boolean = runCatching {
        val bundle = Bundle().apply { putString("probe", "x") }
        bundle.getString("probe") == "x"
    }.getOrDefault(false)
}
