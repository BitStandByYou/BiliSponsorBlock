package io.github.bitstandbyyou.bilisb.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳过方式三选一的归一口径。
 *
 * 持久化仍是三个布尔键,但运行时必须恰好一个为 true;旧配置(三键独立勾选)在读取时归一。
 */
class SkipModeTest {
    @Test
    fun autoOnlyMapsToAuto() {
        assertEquals(SkipMode.AUTO, SkipMode.fromFlags(autoSkip = true, manualSkip = false, muteSegments = false))
        assertEquals(Triple(true, false, false), SkipMode.AUTO.toFlags())
    }

    @Test
    fun manualOnlyMapsToManual() {
        assertEquals(SkipMode.MANUAL, SkipMode.fromFlags(autoSkip = false, manualSkip = true, muteSegments = false))
        assertEquals(Triple(false, true, false), SkipMode.MANUAL.toFlags())
    }

    @Test
    fun muteOnlyMapsToMute() {
        assertEquals(SkipMode.MUTE, SkipMode.fromFlags(autoSkip = false, manualSkip = false, muteSegments = true))
        assertEquals(Triple(false, false, true), SkipMode.MUTE.toFlags())
    }

    @Test
    fun legacyAutoAndMuteCollapseToAuto() {
        assertEquals(SkipMode.AUTO, SkipMode.fromFlags(autoSkip = true, manualSkip = false, muteSegments = true))
    }

    @Test
    fun manualWinsOverMute() {
        assertEquals(SkipMode.MANUAL, SkipMode.fromFlags(autoSkip = false, manualSkip = true, muteSegments = true))
    }

    @Test
    fun allOffFallsBackToAuto() {
        assertEquals(SkipMode.AUTO, SkipMode.fromFlags(autoSkip = false, manualSkip = false, muteSegments = false))
    }

    @Test
    fun everyModeYieldsExactlyOneFlag() {
        SkipMode.values().forEach { mode ->
            val (auto, manual, mute) = mode.toFlags()
            val count = listOf(auto, manual, mute).count { it }
            assertEquals("$mode 必须恰好一个布尔为 true", 1, count)
            assertEquals("$mode 往返必须稳定", mode, SkipMode.fromFlags(auto, manual, mute))
        }
    }

    @Test
    fun muteIsTheOnlyNonAutoMuteSideMode() {
        assertFalse(SkipMode.AUTO.isNonAuto)
        assertTrue(SkipMode.MANUAL.isNonAuto)
        assertTrue(SkipMode.MUTE.isNonAuto)
    }
}
