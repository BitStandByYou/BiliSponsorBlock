package io.github.bitstandbyyou.bilisb.sponsor

import io.github.bitstandbyyou.bilisb.model.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullVideoLabelTest {
    @Test
    fun selectsOnlyOneEligibleLabelUsingWikiPriority() {
        val selected = FullVideoLabel.select(
            listOf(
                segment("selfpromo", "full"),
                segment("exclusive_access", "full"),
                segment("sponsor", "full"),
            ),
        )

        assertEquals(FullVideoLabel("sponsor"), selected)
        assertEquals("整段广告", selected?.displayName)
    }

    @Test
    fun brandDealWinsOverUnpaidSelfPromotion() {
        val selected = FullVideoLabel.select(
            listOf(segment("selfpromo", "full"), segment("exclusive_access", "full")),
        )

        assertEquals("exclusive_access", selected?.category)
        assertEquals("品牌合作", selected?.displayName)
    }

    @Test
    fun ordinarySegmentsAndUnsupportedFullCategoriesAreNotLabels() {
        assertNull(FullVideoLabel.select(listOf(segment("sponsor", "skip"))))
        assertNull(FullVideoLabel.select(listOf(segment("intro", "full"))))
    }

    @Test
    fun onlyWholeVideoSponsorLabelCountsAsAnAd() {
        assertEquals(true, FullVideoLabel.isFullVideoAd(FullVideoLabel("sponsor")))
        assertEquals(false, FullVideoLabel.isFullVideoAd(FullVideoLabel("exclusive_access")))
        assertEquals(false, FullVideoLabel.isFullVideoAd(FullVideoLabel("selfpromo")))
        assertEquals(false, FullVideoLabel.isFullVideoAd(null))
    }

    private fun segment(category: String, actionType: String) = SponsorSegment(
        category = category,
        actionType = actionType,
        segment = if (actionType == FullVideoLabel.ACTION_TYPE) longArrayOf(0L, 0L) else longArrayOf(0L, 60_000L),
        uuid = "$category-$actionType",
        videoDuration = 60.0,
        locked = false,
        votes = 0L,
    )
}
