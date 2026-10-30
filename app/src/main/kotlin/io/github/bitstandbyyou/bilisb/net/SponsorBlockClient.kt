package io.github.bitstandbyyou.bilisb.net

import android.util.Log
import io.github.bitstandbyyou.bilisb.model.SponsorBlockConfig
import io.github.bitstandbyyou.bilisb.model.SponsorBlockQuery
import io.github.bitstandbyyou.bilisb.model.SponsorSegment
import io.github.bitstandbyyou.bilisb.util.HashUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

interface SponsorBlockApi {
    fun fetchSkipSegments(query: SponsorBlockQuery, ignoreCache: Boolean = false): SponsorBlockClient.FetchResult
}

class SponsorBlockClient(
    private val config: SponsorBlockConfig = SponsorBlockConfig(),
    /**
     * 日志出口。默认走 [Log.w];单测里注入空实现即可(android.util.Log 在 JVM 单测中未实现会抛异常)。
     */
    private val logger: (String) -> Unit = { message -> Log.w(TAG, message) },
) : SponsorBlockApi {
    data class FetchResult(
        val statusCode: Int,
        val body: String?,
        val segments: List<SponsorSegment>,
        /** 200 但 body 解析失败(非 JSON/截断/CDN 错误页):结果不可信,调用方不应缓存。 */
        val parseFailed: Boolean = false,
    ) {
        /** 2xx 即视为成功(不要只认 200,服务端可能返回 201/204)。 */
        val isSuccess: Boolean get() = statusCode in 200..299
    }

    /**
     * 拉取请求使用独立的单线程队列:线程是 daemon,宿主进程退出即回收;模块关闭时可调用 [close] 主动停掉。
     */
    private val fetchExecutor: ExecutorService = newSingleThreadExecutor("SponsorBlock-fetch")
    private val closed = AtomicBoolean(false)

    fun endpointForBvid(bvid: String): String {
        val prefix = HashUtils.videoIdHashPrefix(bvid)
        return "${config.serverAddress.trimEnd('/')}/api/skipSegments/$prefix"
    }

    /**
     * 拉取片段。失败(网络 IO 异常)时最多重试 2 次(共 3 次尝试);
     * 最终仍失败返回 statusCode=-1,不抛异常。
     */
    override fun fetchSkipSegments(query: SponsorBlockQuery, ignoreCache: Boolean): FetchResult {
        if (closed.get()) {
            log("fetch skipped: client closed")
            return FETCH_FAILURE
        }
        return runOn(fetchExecutor, "fetch", FETCH_FAILURE) {
            retryRequest(
                maxRetries = FETCH_MAX_ATTEMPTS,
                onFailure = { FETCH_FAILURE },
            ) {
                fetchSegmentsInternal(query, ignoreCache)
            }
        }
    }

    private fun fetchSegmentsInternal(query: SponsorBlockQuery, ignoreCache: Boolean): FetchResult {
        val url = URL(endpointForBvid(query.bvid))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Origin", ORIGIN_HEADER)
            setRequestProperty("X-EXT-VERSION", EXT_VERSION_HEADER)
            if (ignoreCache) {
                setRequestProperty("cache-control", "no-cache")
                setRequestProperty("X-SKIP-CACHE", "1")
            }
        }

        return try {
            val status = conn.responseCode
            val body = readBody(conn, status, "fetch")
            log("fetch GET $url -> status=$status body=${body?.take(LOG_BODY_PREVIEW_CHARS)}")
            // 解析失败(如 CDN 错误页 body 不是 JSON)按空结果处理,不触发重试,但标记 parseFailed
            val parsed = runCatching { parseSegmentsForVideo(query.bvid, body) }
            val segments = parsed
                .onFailure { log("parse segments failed status=$status: ${it.message}") }
                .getOrElse { emptyList() }
            FetchResult(status, body, segments, parseFailed = parsed.isFailure)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 读取响应体,带 [MAX_RESPONSE_BYTES] 上限保护:超过上限就当截断处理并记 warn,避免超大响应把进程撑爆。
     * 非 2xx 读 errorStream(errorStream 为 null 时返回 null)。
     */
    private fun readBody(conn: HttpURLConnection, status: Int, label: String): String? {
        val stream: InputStream = if (status in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: return null
        }
        return stream.use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val out = ByteArrayOutputStream()
            var truncated = false
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                val remaining = MAX_RESPONSE_BYTES - out.size()
                if (read > remaining) {
                    out.write(buffer, 0, remaining.coerceAtLeast(0))
                    truncated = true
                    break
                }
                out.write(buffer, 0, read)
            }
            if (truncated) {
                log("$label response body exceeded ${MAX_RESPONSE_BYTES / (1024 * 1024)}MB, truncated")
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /**
     * 单方向的重试执行器(拉取使用)。
     *
     * 只有网络 IO 异常([IOException],含 [java.net.SocketTimeoutException])才重试;
     * [MalformedURLException] 虽然是 IOException 子类,但属于参数/配置错误,重试不会变好;
     * [IllegalArgumentException]、InterruptedException 等其它异常同样不重试。
     * 被中断时恢复中断标记并返回 [onFailure],不吞掉中断。
     *
     * @param maxRetries 总尝试次数(传 1 表示不重试)
     */
    fun <T> retryRequest(maxRetries: Int, onFailure: (Exception) -> T, block: () -> T): T {
        val attempts = maxRetries.coerceAtLeast(1)
        var lastException: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                return block()
            } catch (e: InterruptedException) {
                // 中断是调用方要求停止,不是可重试的失败:恢复中断标记后立即返回失败结果。
                Thread.currentThread().interrupt()
                log("request interrupted: ${e.message}")
                return onFailure(e)
            } catch (e: MalformedURLException) {
                // URL 非法 = 参数/配置错误,重试没有意义。
                log("request aborted (malformed url): ${e.message}")
                return onFailure(e)
            } catch (e: IOException) {
                // 只有网络 IO 异常才重试(超时、连接失败、读响应中途断开等)。
                lastException = e
                log("request attempt ${attempt + 1}/$attempts failed: ${e.message}")
                if (attempt < attempts - 1) {
                    try {
                        Thread.sleep((attempt + 1) * RETRY_BASE_DELAY_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log("retry delay interrupted: ${interrupted.message}")
                        return onFailure(interrupted)
                    }
                }
            } catch (e: Exception) {
                // 其它异常(IllegalArgumentException / JSONException 等)不重试。
                log("request failed without retry (${e.javaClass.simpleName}): ${e.message}")
                return onFailure(e)
            }
        }
        log("request failed after $attempts attempts: ${lastException?.message}")
        return onFailure(lastException ?: IOException("request failed"))
    }

    /**
     * 把请求丢到 executor 上执行并等结果(单线程队列 = 请求天然串行)。
     *
     * 注意:调用方(`SponsorBlockController`)仍在自己的单线程上顺序调用本方法。
     */
    private fun <T> runOn(executor: ExecutorService, label: String, onFailure: T, block: () -> T): T {
        val future = try {
            executor.submit(Callable { block() })
        } catch (e: RejectedExecutionException) {
            // executor 已关闭:不再起新线程,退回调用线程同步执行,避免功能静默失效。
            log("$label executor rejected task, running inline: ${e.message}")
            return runCatching(block).getOrElse { onFailure }
        }
        return try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log("$label interrupted while waiting: ${e.message}")
            onFailure
        } catch (e: ExecutionException) {
            log("$label failed: ${e.cause?.message}")
            onFailure
        }
    }

    fun parseSegmentsForVideo(bvid: String?, raw: String?): List<SponsorSegment> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        val buckets = JSONArray(raw)
        val result = mutableListOf<SponsorSegment>()
        for (i in 0 until buckets.length()) {
            val bucket = buckets.optJSONObject(i) ?: continue
            val videoId = bucket.optString("videoID", "")
            if (bvid != null && videoId != bvid) {
                continue
            }
            val segments = bucket.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val segment = parseSegment(segments.optJSONObject(j) ?: continue) ?: continue
                if (segment.category !in config.enabledCategories) {
                    continue
                }
                if (segment.actionType !in config.enabledActionTypes) {
                    continue
                }
                result += segment
            }
            // 接口返回的是"该 hash 前缀下所有视频"的桶,命中目标 videoID 后剩下的桶与目标无关,
            // 直接 break,不再解析后面的元素(省 CPU,也避免被无关的大数组拖慢)。
            if (bvid != null) {
                break
            }
        }
        return result.sortedBy { it.startMs }
    }

    private fun parseSegment(json: JSONObject): SponsorSegment? {
        val segmentArray = json.optJSONArray("segment") ?: return null
        // org.json 的 optString 会把 JSON null 变成字面量 "null",所以统一走 optStringOrNull。
        val category = optStringOrNull(json, "category")?.takeIf { it.isNotBlank() } ?: DEFAULT_CATEGORY
        val actionType = optStringOrNull(json, "actionType")?.takeIf { it.isNotBlank() } ?: DEFAULT_ACTION_TYPE
        // `full` 是整段视频标签，不是普通时间区间；协议规定 startTime=endTime=0。
        // 先识别它，避免被普通片段的零长度校验丢弃。它不进入跳过/绘制链路。
        val isFullVideoLabel = actionType == "full"
        if (segmentArray.length() < if (isFullVideoLabel) 1 else 2) {
            return null
        }
        // UUID 是去重 key(跳过记录依赖它),缺失或为 null 时丢弃该片段。
        val uuid = optStringOrNull(json, "UUID")?.takeIf { it.isNotBlank() }
            ?: optStringOrNull(json, "uuid")?.takeIf { it.isNotBlank() }
            ?: return null

        // SponsorBlock API 返回秒,播放器 API 用毫秒,这里统一换算成毫秒。
        val videoDurationSec = json.optDouble("videoDuration", 0.0)
        val videoDurationMs = if (videoDurationSec.isFinite() && videoDurationSec > 0.0) {
            (videoDurationSec * 1000.0).toLong()
        } else {
            0L
        }

        val range = if (isFullVideoLabel) {
            sanitizeFullVideoMarker(segmentArray) ?: return null
        } else {
            sanitizeSegmentRange(segmentArray, videoDurationMs) ?: return null
        }

        return SponsorSegment(
            category = category,
            actionType = actionType,
            segment = range,
            uuid = uuid,
            videoDuration = if (videoDurationSec.isFinite()) videoDurationSec else 0.0,
            locked = json.optBoolean("locked", false),
            votes = json.optLong("votes", 0L),
            description = optStringOrNull(json, "description") ?: "",
        )
    }

    /** `actionType=full` 的时间字段按协议为 0/0，是类型标记而非可跳过区间。 */
    private fun sanitizeFullVideoMarker(segmentArray: JSONArray): LongArray? {
        if (segmentArray.length() !in 1..2) return null
        for (index in 0 until segmentArray.length()) {
            val value = segmentArray.optDouble(index, Double.NaN)
            if (!value.isFinite() || value != 0.0) return null
        }
        return longArrayOf(0L, 0L)
    }

    /**
     * 单条片段的健全性过滤,集中在这里(返回 null 表示丢弃):
     *  1. start/end 非有限值(NaN / Infinity,例如 JSON 里的 `1e400`)—— 直接 `toLong` 会溢出成 Long.MAX_VALUE;
     *  2. start/end 超过 [MAX_SEGMENT_SECONDS] —— 明显是脏数据,也防 `*1000` 之后溢出;
     *  3. `startMs < 0`;
     *  4. `endMs <= startMs`;
     *  5. 片段短于 [MIN_SEGMENT_DURATION_MS](250ms)—— 跳过/静音这类操作对极短片段没有意义;
     *  6. 已知视频总时长时,`endMs` 超出总时长(留 [VIDEO_DURATION_TOLERANCE_MS] 误差余量)—— 服务端 duration 可能是旧值。
     */
    private fun sanitizeSegmentRange(segmentArray: JSONArray, videoDurationMs: Long): LongArray? {
        val startSec = segmentArray.optDouble(0, Double.NaN)
        val endSec = segmentArray.optDouble(1, Double.NaN)
        if (!startSec.isFinite() || !endSec.isFinite()) {
            return null
        }
        if (startSec > MAX_SEGMENT_SECONDS || endSec > MAX_SEGMENT_SECONDS) {
            return null
        }
        val startMs = (startSec * 1000.0).toLong()
        val endMs = (endSec * 1000.0).toLong()
        if (startMs < 0L) {
            return null
        }
        if (endMs <= startMs) {
            return null
        }
        if (endMs - startMs < MIN_SEGMENT_DURATION_MS) {
            return null
        }
        if (videoDurationMs > 0L && endMs > videoDurationMs + VIDEO_DURATION_TOLERANCE_MS) {
            return null
        }
        return longArrayOf(startMs, endMs)
    }

    /**
     * JSON null 安全的字符串读取:显式 null 与键缺失都返回 null。
     * (org.json 的 `isNull` 对"键不存在"同样返回 true,正好符合这里的语义。)
     */
    private fun optStringOrNull(json: JSONObject, key: String): String? {
        return if (json.isNull(key)) null else json.optString(key)
    }

    /*
     * 拉取接口不带任何 query 参数(历史注记,参数实测全部 400)。
     * 真机实测（`https://bsbsb.top`）：
     *   `?videoID=...&cid=...&actionType=skip` → HTTP 400
     *   `?videoID=...` / `?cid=...`          → HTTP 400
     *   `?actionType=skip` / 无 query         → HTTP 200（返回该 hash 前缀下所有视频的片段）
     *
     * 官方 SponsorBlock 协议里 `/api/skipSegments/{prefix}` 只按前缀返回、由客户端按 videoID 过滤，
     * 并不存在 `videoID` / `cid` 这两个参数（[parseSegmentsForVideo] 负责过滤）。
     * 另外不带 `actionType=skip` 还能拿到 `actionType=poi` 的 POI 片段，供进度条圆点标记使用。
     */

    private fun log(message: String) {
        logger(message)
    }

    /** 停掉拉取 executor(幂等)。关闭后新的请求会直接返回失败结果。 */
    fun close() {
        shutdown()
    }

    /** [close] 的别名,兼容可能用 `shutdown()` 命名的调用方。 */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        fetchExecutor.shutdownNow()
        log("client closed")
    }

    private fun newSingleThreadExecutor(name: String): ExecutorService {
        return Executors.newSingleThreadExecutor(
            object : ThreadFactory {
                override fun newThread(r: Runnable): Thread {
                    return Thread(r, name).apply { isDaemon = true }
                }
            },
        )
    }

    companion object {
        private const val TAG = "SponsorBlockClient"

        private const val ORIGIN_HEADER = "BiliRoamingX"
        private const val EXT_VERSION_HEADER = "1.27.3"
        private const val DEFAULT_CATEGORY = "sponsor"
        private const val DEFAULT_ACTION_TYPE = "skip"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000

        /** 拉取总尝试次数 = 首次 + 最多 2 次重试(降低重试的时间成本)。 */
        private const val FETCH_MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 500L

        /** 响应体读取上限 4MB,超出截断并记 warn。 */
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val LOG_BODY_PREVIEW_CHARS = 200

        /** 短于 250ms 的片段直接丢弃(跳过/静音没有意义,还会让进度条抖动)。 */
        private const val MIN_SEGMENT_DURATION_MS = 250L

        /** 允许片段末端超出视频总时长的误差(服务端 duration 可能是旧值)。 */
        private const val VIDEO_DURATION_TOLERANCE_MS = 5_000L

        /** 片段时间的可接受上限(24 小时),超过视为脏数据,同时防 `*1000` 溢出。 */
        private const val MAX_SEGMENT_SECONDS = 86_400.0

        private val FETCH_FAILURE = FetchResult(-1, null, emptyList())
    }
}
