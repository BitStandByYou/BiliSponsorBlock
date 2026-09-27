package io.github.bitstandbyyou.bilisb.sponsor

import android.os.Handler
import android.os.Looper
import io.github.bitstandbyyou.bilisb.model.SponsorBlockConfig
import io.github.bitstandbyyou.bilisb.model.SponsorBlockQuery
import io.github.bitstandbyyou.bilisb.net.SponsorBlockClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放前整段标签查询。按服务地址 + bvid 缓存正/负结果，并合并同一视频的并发查询。
 * 网络访问始终在后台线程；回调统一切回主线程供路由门禁更新 UI。
 */
internal class FullVideoLabelLookup(
    private val serverAddress: String,
    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL_FETCHES) { task ->
        Thread(task, "BiliSB-full-video-label-${workerIds.incrementAndGet()}").apply { isDaemon = true }
    },
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    sealed interface Result {
        data class Checked(val label: FullVideoLabel?) : Result
        data class Failed(val statusCode: Int) : Result
    }

    private data class Cached(val label: FullVideoLabel?, val atMs: Long)

    private val lock = Any()
    private val cache = ConcurrentHashMap<String, Cached>()
    private val callbacksByBvid = HashMap<String, MutableList<(Result) -> Unit>>()
    private val clients = ConcurrentLinkedQueue<SponsorBlockClient>()
    private val clientByThread = ThreadLocal<SponsorBlockClient>()

    fun lookup(bvid: String, callback: (Result) -> Unit) {
        if (bvid.isBlank()) {
            callback(Result.Failed(-1))
            return
        }

        var cachedResult: Result? = null
        var startFetch = false
        synchronized(lock) {
            val cached = cache[bvid]
            if (cached != null && nowMs() - cached.atMs <= CACHE_TTL_MS) {
                cachedResult = Result.Checked(cached.label)
            } else {
                if (cached != null) cache.remove(bvid, cached)
                val callbacks = callbacksByBvid[bvid]
                if (callbacks != null) {
                    callbacks += callback
                } else {
                    callbacksByBvid[bvid] = mutableListOf(callback)
                    startFetch = true
                }
            }
        }
        cachedResult?.let {
            post(callback, it)
            return
        }
        if (!startFetch) return

        executor.execute {
            val result = runCatching {
                val fetched = clientForCurrentWorker().fetchSkipSegments(SponsorBlockQuery(bvid, 0L))
                when {
                    fetched.parseFailed -> Result.Failed(fetched.statusCode)
                    fetched.statusCode == 404 -> Result.Checked(null)
                    fetched.isSuccess -> Result.Checked(FullVideoLabel.select(fetched.segments))
                    else -> Result.Failed(fetched.statusCode)
                }
            }.getOrElse { Result.Failed(-1) }

            val callbacks = synchronized(lock) {
                if (result is Result.Checked) {
                    cache[bvid] = Cached(result.label, nowMs())
                    trimCacheIfNeeded()
                }
                callbacksByBvid.remove(bvid).orEmpty().toList()
            }
            callbacks.forEach { post(it, result) }
        }
    }

    private fun post(callback: (Result) -> Unit, result: Result) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(result)
        } else {
            mainHandler.post { callback(result) }
        }
    }

    private fun trimCacheIfNeeded() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        val removeCount = cache.size - TARGET_CACHE_ENTRIES
        cache.entries.sortedBy { it.value.atMs }.take(removeCount).forEach { cache.remove(it.key, it.value) }
    }

    fun close() {
        executor.shutdownNow()
        clients.forEach(SponsorBlockClient::close)
        clients.clear()
        clientByThread.remove()
        synchronized(lock) {
            callbacksByBvid.clear()
            cache.clear()
        }
    }

    /** 每个工作线程持有自己的客户端，绕过 SponsorBlockClient 单实例串行队列的排队延迟。 */
    private fun clientForCurrentWorker(): SponsorBlockClient = clientByThread.get() ?: SponsorBlockClient(
        config = SponsorBlockConfig(
            serverAddress = serverAddress,
            enabledCategories = FULL_VIDEO_CATEGORIES,
            enabledActionTypes = setOf(FullVideoLabel.ACTION_TYPE),
        ),
    ).also { client ->
        clients += client
        clientByThread.set(client)
    }

    companion object {
        private val sharedByServer = ConcurrentHashMap<String, FullVideoLabelLookup>()

        fun shared(serverAddress: String): FullVideoLabelLookup =
            sharedByServer.computeIfAbsent(serverAddress) { FullVideoLabelLookup(it) }

        private const val CACHE_TTL_MS = 60L * 60_000L
        private const val MAX_CACHE_ENTRIES = 128
        private const val TARGET_CACHE_ENTRIES = 96
        private const val MAX_PARALLEL_FETCHES = 3
        private val workerIds = AtomicInteger()
        private val FULL_VIDEO_CATEGORIES = setOf(
            FullVideoLabel.CATEGORY_SPONSOR,
            FullVideoLabel.CATEGORY_EXCLUSIVE_ACCESS,
            FullVideoLabel.CATEGORY_SELFPROMO,
        )
    }
}
