package io.github.bitstandbyyou.bilisb.net

import io.github.bitstandbyyou.bilisb.model.SponsorBlockConfig
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockClientTest {
    // 注入空日志出口:单测里 android.util.Log 未被 mock,触碰到会抛 RuntimeException。
    private val client = SponsorBlockClient(
        config = SponsorBlockConfig(
            enabledCategories = setOf("sponsor", "poi_highlight", "intro"),
            enabledActionTypes = setOf("skip", "poi", "mute"),
        ),
        logger = {},
    )

    @After
    fun tearDown() {
        client.close()
    }

    @Test
    fun endpointForBvidUsesFirstFourHexCharsOfSha256() {
        assertEquals(
            "https://bsbsb.top/api/skipSegments/5759",
            client.endpointForBvid("BV14741127BN"),
        )
        // 结尾不带任何 query(真机实测带 ?videoID= / ?cid= 会 400)。
        assertFalse(client.endpointForBvid("BV14741127BN").contains("?"))
    }

    @Test
    fun parsesSegmentsForTargetVideoAndFiltersInvalidOnes() {
        val raw = """
            [
              {
                "videoID": "BV17x411w7KC",
                "segments": [
                  {"segment": [15.0, 20.0], "category": "poi_highlight", "actionType": "poi", "UUID": "u2"},
                  {"segment": [0.0, 10.5], "category": "sponsor", "actionType": "skip", "UUID": "u1"},
                  {"segment": [30.0, 35.0], "category": "intro", "actionType": "mute", "UUID": "u3"},
                  {"segment": [40.0, 39.0], "category": "sponsor", "actionType": "skip", "UUID": "bad"}
                ]
              },
              {
                "videoID": "BV_OTHER",
                "segments": [
                  {"segment": [1.0, 2.0], "category": "sponsor", "actionType": "skip", "UUID": "other"}
                ]
              }
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV17x411w7KC", raw)

        assertEquals(listOf("u1", "u2", "u3"), segments.map { it.uuid })
        assertEquals(listOf(0L, 15_000L, 30_000L), segments.map { it.startMs })
        assertEquals(listOf(10_500L, 20_000L, 35_000L), segments.map { it.endMs })
    }

    @Test
    fun returnsEmptyListForBlankPayload() {
        assertTrue(client.parseSegmentsForVideo("BV17x411w7KC", " ").isEmpty())
    }

    @Test
    fun dropsSegmentsWithDisabledCategoryOrActionType() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [0.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "kept"},
                {"segment": [10.0, 15.0], "category": "outro", "actionType": "skip", "UUID": "category-off"},
                {"segment": [20.0, 25.0], "category": "sponsor", "actionType": "full", "UUID": "action-off"}
              ]}
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV1", raw)

        assertEquals(listOf("kept"), segments.map { it.uuid })
    }

    @Test
    fun parsesFullVideoLabelsWhenExplicitlyRequestedWithoutEnablingThemForSkipping() {
        val fullClient = SponsorBlockClient(
            config = SponsorBlockConfig(
                enabledCategories = setOf("sponsor", "exclusive_access", "selfpromo"),
                enabledActionTypes = setOf("full"),
            ),
            logger = {},
        )
        try {
            val raw = """
                [{"videoID":"BV1","segments":[
                  {"segment":[0.0,0.0],"category":"sponsor","actionType":"full","UUID":"full-sponsor"},
                  {"segment":[0.0],"category":"exclusive_access","actionType":"full","UUID":"full-brand"},
                  {"segment":[10.0,20.0],"category":"sponsor","actionType":"skip","UUID":"ordinary-sponsor"},
                  {"segment":[0.0,0.0],"category":"intro","actionType":"full","UUID":"unsupported-full"}
                ]}]
            """.trimIndent()

            val segments = fullClient.parseSegmentsForVideo("BV1", raw)

            assertEquals(listOf("full-sponsor", "full-brand"), segments.map { it.uuid })
            assertTrue(segments.all { it.actionType == "full" })
            assertTrue(segments.all { it.startMs == 0L && it.endMs == 0L })
        } finally {
            fullClient.close()
        }
    }

    @Test
    fun dropsSegmentsFailingSanityChecks() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [-1.0, 10.0], "category": "sponsor", "actionType": "skip", "UUID": "negative-start"},
                {"segment": [5.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "zero-length"},
                {"segment": [10.0, 10.1], "category": "sponsor", "actionType": "skip", "UUID": "too-short"},
                {"segment": [30.0, 1e400], "category": "sponsor", "actionType": "skip", "UUID": "infinite-end"},
                {"segment": [1.0, 40.0], "category": "sponsor", "actionType": "skip", "UUID": "beyond-duration", "videoDuration": 30.0},
                {"segment": [1.0, 20.0], "category": "sponsor", "actionType": "skip", "UUID": "within-duration", "videoDuration": 30.0},
                {"segment": [0.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "kept"}
              ]}
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV1", raw)

        // 按 startMs 排序:kept(0ms) < within-duration(1000ms)
        assertEquals(listOf("kept", "within-duration"), segments.map { it.uuid })
        assertEquals(listOf(0L, 1_000L), segments.map { it.startMs })
    }

    @Test
    fun treatsJsonNullFieldsAsMissingInsteadOfLiteralNull() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [0.0, 5.0], "category": null, "actionType": null, "UUID": "nulls-fall-back", "description": null},
                {"segment": [10.0, 15.0], "category": "sponsor", "actionType": "skip", "UUID": null},
                {"segment": [20.0, 25.0], "category": "sponsor", "actionType": "skip"}
              ]}
            ]
        """.trimIndent()

        val segments = client.parseSegmentsForVideo("BV1", raw)

        // 只有第一条能留下:category/actionType 的 null 退回默认值,不再变成字面量 "null";
        // UUID 为 null 或缺键的片段必须丢弃。
        assertEquals(listOf("nulls-fall-back"), segments.map { it.uuid })
        assertEquals("sponsor", segments.first().category)
        assertEquals("skip", segments.first().actionType)
        assertEquals("", segments.first().description)
    }

    @Test
    fun stopsAfterFirstBucketMatchingTargetVideo() {
        val raw = """
            [
              {"videoID": "BV1", "segments": [
                {"segment": [0.0, 5.0], "category": "sponsor", "actionType": "skip", "UUID": "first-bucket"}
              ]},
              {"videoID": "BV1", "segments": [
                {"segment": [10.0, 15.0], "category": "sponsor", "actionType": "skip", "UUID": "second-bucket"}
              ]}
            ]
        """.trimIndent()

        // 命中目标 videoID 后立即 break:后面同名桶不再解析。
        val segments = client.parseSegmentsForVideo("BV1", raw)

        assertEquals(listOf("first-bucket"), segments.map { it.uuid })
    }




    @Test
    fun anyTwoHundredStatusCountsAsSuccess() {
        assertTrue(SponsorBlockClient.FetchResult(200, null, emptyList()).isSuccess)
        assertTrue(SponsorBlockClient.FetchResult(299, null, emptyList()).isSuccess)
        assertFalse(SponsorBlockClient.FetchResult(404, null, emptyList()).isSuccess)
    }

    @Test
    fun retryRequestDoesNotRetryNonIoExceptions() {
        var calls = 0
        val failure = client.retryRequest(
            maxRetries = 3,
            onFailure = { e -> "failed:${e.javaClass.simpleName}" },
        ) {
            calls += 1
            throw IllegalArgumentException("bad argument")
        }

        assertEquals(1, calls)
        assertEquals("failed:IllegalArgumentException", failure)
    }

    @Test
    fun retryRequestDoesNotRetryMalformedUrlEvenThoughItIsAnIoException() {
        var calls = 0
        val failure = client.retryRequest(
            maxRetries = 3,
            onFailure = { e -> "failed:${e.javaClass.simpleName}" },
        ) {
            calls += 1
            throw MalformedURLException("bad url")
        }

        assertEquals(1, calls)
        assertEquals("failed:MalformedURLException", failure)
    }

    @Test
    fun retryRequestRetriesIoExceptionsUntilMaxAttempts() {
        var calls = 0
        val failure = client.retryRequest(
            maxRetries = 2,
            onFailure = { e -> "failed:${e.message}" },
        ) {
            calls += 1
            throw SocketTimeoutException("timeout $calls")
        }

        assertEquals(2, calls)
        assertEquals("failed:timeout 2", failure)
    }

    @Test
    fun retryRequestRestoresInterruptFlagAndStopsImmediately() {
        var calls = 0
        try {
            val failure = client.retryRequest<String>(
                maxRetries = 3,
                onFailure = { "interrupted" },
            ) {
                calls += 1
                throw InterruptedException("stop")
            }

            assertEquals(1, calls)
            assertEquals("interrupted", failure)
            assertTrue("中断标记必须被恢复", Thread.currentThread().isInterrupted)
        } finally {
            // 清掉中断标记,避免污染后续用例。
            Thread.interrupted()
        }
    }

}
