package io.github.bitstandbyyou.bilisb.sponsor

import io.github.bitstandbyyou.bilisb.model.SponsorBlockQuery
import io.github.bitstandbyyou.bilisb.model.SponsorBlockConfig
import io.github.bitstandbyyou.bilisb.model.SponsorSegment
import io.github.bitstandbyyou.bilisb.net.SponsorBlockClient
import io.github.bitstandbyyou.bilisb.player.PlayerActions
import io.github.bitstandbyyou.bilisb.player.AudioMuteController
import io.github.bitstandbyyou.bilisb.player.PlayerBridge
import io.github.bitstandbyyou.bilisb.player.PlayerHandle
import io.github.bitstandbyyou.bilisb.player.PlayerState
import io.github.bitstandbyyou.bilisb.settings.SettingsSnapshot
import io.github.bitstandbyyou.bilisb.ui.PlayerToastBridge
import io.github.bitstandbyyou.bilisb.ui.ManualSkipButton
import io.github.bitstandbyyou.bilisb.util.AidBvidConverter
import io.github.bitstandbyyou.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Note: TTL=0 / 过期仍用 lastKnown 决策、unmute 必带 hash — 见 .agents/notes/implemented/bug-fix/2026-03-21-full-audit-fixes.md
class SponsorBlockController(
    private val module: XposedModule,
    private val settings: SettingsSnapshot = SettingsSnapshot.DEFAULT,
) {
    // 服务器地址与缓存 TTL 不再是设置项:直接用模型与仓库里的固定默认值。
    private val repository = SponsorBlockRepository(
        client = SponsorBlockClient(
            config = SponsorBlockConfig(
                enabled = settings.enabled,
                autoSkip = settings.autoSkip,
                enabledCategories = settings.enabledCategories,
            )
        ),
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    // 正在拉取中的视频 key,防止 onStart 短时间多次触发导致并发重复请求。
    // 缓存有效期由 repository TTL 控制,过期后这里会清掉允许重拉。
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val fetchGeneration = ConcurrentHashMap<String, Long>()

    private val latestStateByContext = ConcurrentHashMap<Int, PlayerState>()
    private val latestContainerByContext = ConcurrentHashMap<Int, Any>()
    private val playerHandles = ConcurrentHashMap<Int, PlayerHandle>()

    /**
     * 已跳过片段,按 `bvid:cid` 分桶。
     *
     * 以前是一个全局 `Set<String>` 只增不减:会话内重看同一视频时 key 还在,
     * 片段再也不会被跳过;集合还会随观看量单调增长。切集/换视频时(见 [resetContextPolicyState])
     * 会删掉旧视频的桶,重新看同一视频时又是干净的。
     */
    private val skippedSegmentsByVideo = ConcurrentHashMap<String, MutableSet<String>>()

    // 手动模式下当前正在展示跳过按钮的片段 key,用于避免每个进度回调都重设按钮。
    private val manualButtonSegmentKeyByContext = ConcurrentHashMap<Int, String>()

    /**
     * 容器创建时绑定播放器 handle(core 用于 seek,container 用于 toast / context)。
     *
     * 此时还没有 video id —— aid/cid 由 [onVideoIds] 在 video director `onStart`
     * 回调里异步喂入,与 APK `PlayerHookProvider.h/g` 链路一致。
     */
    fun bindPlayerHandle(handle: PlayerHandle) {
        if (closed.get()) return
        val previous = playerHandles.put(handle.contextHash, handle)
        val previousContainer = latestContainerByContext.put(handle.contextHash, handle.container)
        // 全屏重绑同一 context 时不能 resetContextPolicyState:那会清 skipped 桶,片段会被再跳一次。
        // 仅在首次绑定或 container 换了时,对旧/新 container unmute+hide,并清 UI 记账。
        if (previous == null || previous.container !== handle.container) {
            val oldContainer = previous?.container ?: previousContainer
            if (oldContainer != null) {
                AudioMuteController.unmute(module, oldContainer, handle.contextHash)
                ManualSkipButton.hide(module, oldContainer)
            }
            if (handle.container !== oldContainer) {
                AudioMuteController.unmute(module, handle.container, handle.contextHash)
                ManualSkipButton.hide(module, handle.container)
            }
            manualButtonSegmentKeyByContext.remove(handle.contextHash)
        }
        module.info("player handle bound context=${handle.contextHash}")
    }

    /**
     * video director `onStart(aid, cid)` 回调入口。
     *
     * 对应 APK `so.d(aid, cid)` → `SponsorBlockPatch.c(aid, cid, epId, duration, ...)`:
     * aid 实时转 bvid(对应 APK `i6.H(aid)`),拼成 query 后异步拉片段。
     * 切集时同一 context 会再次回调,按 `bvid:cid` 去重只拉新视频。
     *
     * **切集/换视频时先重置上一集的策略状态**(见 [resetContextPolicyState]):
     * 新片段还没拉回来的那段时间里,旧视频的静音 / 手动按钮若继续存活,
     * 会把新视频 seek 到旧片段位置并虚记一次统计。
     */
    fun onVideoIds(contextHash: Int, aid: Long, cid: Long) {
        if (closed.get()) return
        if (aid <= 0 || cid <= 0) {
            return
        }
        val bvid = AidBvidConverter.aidToBvid(aid)
        // duration 在 director onStart 触发时从 core 取(对应 APK so.d 内部经
        // PlayerHookProvider.o(obj) 取 getDuration)。onStart 早于首帧准备的极端情况下
        // getDuration 可能返回 0,后续进度回调会用进度文本 hook 里的 duration 兜底。
        val core = playerHandles[contextHash]?.core
        val durationMs = core?.let { PlayerActions.durationMs(module, it) } ?: 0L
        val state = PlayerState(
            aid = aid,
            bvid = bvid,
            cid = cid,
            durationMs = durationMs,
            currentPositionMs = core?.let { PlayerActions.currentPositionMs(module, it) } ?: 0L,
        )
        val previous = latestStateByContext[contextHash]
        latestStateByContext[contextHash] = state
        if (isVideoChanged(previous, state)) {
            val container = playerHandles[contextHash]?.container ?: latestContainerByContext[contextHash]
            if (container != null) {
                resetContextPolicyState(contextHash, container)
            }
        } else {
            // 重看/循环播放同一视频:onVideoIds 是「播放条目重新就绪」的信号,语义是
            // 「重新开始播放」—— 同一视频从头看也应重新可跳,否则 skippedSegmentsByVideo
            // 里的 key 会让整个片段在重看时永远不跳。清掉该视频的跳过记录。
            val key = videoKey(state)
            skippedSegmentsByVideo[key]?.clear()
        }

        val query = SponsorBlockQuery(state.bvid, state.cid)
        // 缓存未过期(repository TTL 内)直接复用,不再发起请求。
        if (repository.getCached(query) != null) {
            return
        }
        scheduleSegmentFetch(videoKey(state), query, false, "segments fetched aid=$aid")
    }

    private fun scheduleSegmentFetch(key: String, query: SponsorBlockQuery, ignoreCache: Boolean, logPrefix: String) {
        val gen = fetchGeneration.merge(key, 1L) { cur, _ -> cur + 1 } ?: 1L
        inFlight.add(key)
        executor.execute {
            try {
                if (closed.get()) return@execute
                val result = repository.fetchAndCache(query, ignoreCache)
                module.info("$logPrefix video=${query.bvid} cid=${query.cid} status=${result.statusCode} count=${result.segments.size}")
            } finally {
                if (fetchGeneration[key] == gen) inFlight.remove(key)
            }
        }
    }

    private fun currentPositionMs(contextHash: Int): Long? {
        val core = playerHandles[contextHash]?.core ?: return null
        return PlayerActions.currentPositionMs(module, core)
    }

    /** 该 context 是否还有播放状态（供 Hook 侧判断要不要补绑）。 */
    fun latestStateExists(contextHash: Int): Boolean =
        !closed.get() && latestStateByContext.containsKey(contextHash)

    fun progressMarkers(contextHash: Int): Pair<Long, List<SponsorSegment>>? = latestSegments(contextHash)

    fun segmentsForContext(contextHash: Int): List<SponsorSegment>? {
        if (closed.get()) return null
        val state = latestStateByContext[contextHash] ?: return null
        return stateSegments(contextHash, state)
    }

    fun latestSegments(contextHash: Int): Pair<Long, List<SponsorSegment>>? {
        if (closed.get()) return null
        val state = latestStateByContext[contextHash] ?: return null
        val segments = stateSegments(contextHash, state) ?: return null
        return state.durationMs to segments
    }

    fun onProgress(contextHash: Int, positionMs: Long, durationMs: Long) {
        if (closed.get()) return
        // 手动模式 / 静音模式也需要进度回调,所以这里不再因 autoSkip 关闭而早退,
        // 改在拿到命中片段后按 manualSkip / autoSkip / muteSegments 分流。
        if (!settings.autoSkip && !settings.manualSkip && !settings.muteSegments) {
            return // 所有片段策略都关闭,无需处理
        }

        val state = latestStateByContext[contextHash] ?: return
        // 回退超过阈值视为用户拖回/重看:清掉该视频已跳过集合,否则拖回片段前不会再跳。
        if (state.currentPositionMs > 0 && positionMs + REWIND_RESET_MS < state.currentPositionMs) {
            skippedSegmentsForVideo(videoKey(state)).clear()
        }
        // 进度文本 hook 的 duration 比 onStart 时刻更准(首帧已就绪),
        // 持续把非零 duration 与当前位置回填到 state,供进度条标记与回退检测使用。
        // replace 校验 map 里仍是同一个 state:onVideoIds 若已换成新视频则放弃回写,避免串台。
        latestStateByContext.replace(
            contextHash,
            state,
            state.copy(
                durationMs = if (durationMs > 0) durationMs else state.durationMs,
                currentPositionMs = positionMs.coerceAtLeast(0),
            ),
        )
        val handle = playerHandles[contextHash] ?: return

        // 关键:片段没拉到(cache miss)不再是「直接 return」,而是带着空列表进入下面的
        // 清理逻辑 —— 否则上一集的静音 / 手动按钮会一直活着(清理盲区)。
        val segments = stateSegments(contextHash, state).orEmpty()
        // 时长未知(0)时不参与决策,交给 SkipDecision 直接返回 null。
        val decisionDurationMs = if (durationMs > 0) durationMs else state.durationMs

        // 静音策略(片段静音模式):命中 mute 片段就静音,离开(或没有片段)就取消静音。
        if (settings.muteSegments) {
            val muteSeg = SkipDecision.findActiveMuteSegment(
                positionMs = positionMs,
                segments = segments,
                durationMs = decisionDurationMs,
            )
            if (muteSeg != null) {
                AudioMuteController.mute(module, handle.container, contextHash)
            } else {
                AudioMuteController.unmute(module, handle.container, contextHash)
            }
        }

        val segment = SkipDecision.findActiveSkipSegment(
            positionMs = positionMs,
            segments = segments,
            durationMs = decisionDurationMs,
        )

        // 手动跳过优先:命中片段时浮出按钮,由用户点按跳过;离开片段时收起。
        if (settings.manualSkip) {
            if (segment == null) {
                if (manualButtonSegmentKeyByContext.remove(contextHash) != null) {
                    ManualSkipButton.hide(module, handle.container)
                }
                return
            }
            val skipKey = segmentKey(contextHash, segment)
            if (manualButtonSegmentKeyByContext[contextHash] == skipKey) {
                return // 同一片段已在展示,避免重复重设按钮
            }
            manualButtonSegmentKeyByContext[contextHash] = skipKey
            val categoryName = getCategoryDisplayName(segment.category)
            // segmentKey 必须显式传给按钮:ManualSkipButton 用它做「点击后短期抑制」,
            // 不传的话 seek 生效前的几帧进度回调会让按钮闪回来。
            ManualSkipButton.show(module, handle.container, categoryName, skipKey) {
                // 手动跳过由用户显式触发,但位置已经越过片段尾时同样不该回跳。
                if (performSkipIfStillValid(contextHash, handle, segment, "manual", state)) {
                    manualButtonSegmentKeyByContext.remove(contextHash)
                    // 统计开关关闭时不累计（面板/设置页的「跳过次数统计」）
                    if (settings.showSkipStats) SkipStatsStore.record(segment.category, segment.endMs - segment.startMs)
                    if (settings.showToast) {
                        val durationSec = (segment.endMs - segment.startMs) / 1000.0
                        PlayerToastBridge.showSkipToast(
                            module, handle.container,
                            String.format(Locale.US, "%s (%.1f秒)", categoryName, durationSec),
                        )
                    }
                }
            }
            module.info(
                "manual skip button shown video=${state.bvid} cid=${state.cid} " +
                    "segment=${segment.startMs}-${segment.endMs} category=${segment.category}",
            )
            return
        }

        // 以下为自动跳过。autoSkip 关闭则只剩静音生效,直接返回。
        if (!settings.autoSkip) {
            return
        }

        if (segment == null) {
            return
        }
        val skipKey = segmentKey(contextHash, segment)
        val skipped = skippedSegmentsForVideo(videoKey(state))

        // 立即自动跳过。
        if (!skipped.add(skipKey)) {
            return
        }

        // APK behavior uses PlayerHookProvider.z(playerCore, endMs, true).
        // This is the direct Hook equivalent, with the handle coming from the
        // player container/context binding.
        if (!performSkipIfStillValid(contextHash, handle, segment, "auto", state)) {
            skipped.remove(skipKey)
            return
        }
        // 统计开关关闭时不累计（面板/设置页的「跳过次数统计」）
        if (settings.showSkipStats) SkipStatsStore.record(segment.category, segment.endMs - segment.startMs)
        if (settings.showToast) {
            val categoryName = getCategoryDisplayName(segment.category)
            val durationSec = (segment.endMs - segment.startMs) / 1000.0
            val message = String.format(Locale.US, "%s (%.1f秒)", categoryName, durationSec)
            PlayerToastBridge.showSkipToast(module, handle.container, message)
        }
        module.info(
            "auto-skipped video=${state.bvid} cid=${state.cid} " +
                "position=$positionMs duration=$durationMs " +
                "segment=${segment.startMs}-${segment.endMs} category=${segment.category}",
        )
    }

    /**
     * 执行 seek,但**只在还没有越过片段尾时**才做。
     *
     * 进度回调稀疏(或用户手动拖到了片段之后)时,position 可能已经 >= endMs:
     * 此时 seek(endMs) 是往回跳,而且一点时长都没省下,必须放弃而不是照跳照记统计。
     *
     * 位置优先取 core 的实时值,读不到才退化为最近一次进度回调记下的
     * [PlayerState.currentPositionMs](须 > 0);两者都拿不到时放弃,避免用 0 去 seek。
     *
     * @param decisionState 做出跳过决策时的播放状态:执行前重读该 context 的最新 state,
     *   视频(bvid:cid)已切换时放弃 —— 切集瞬间在飞的旧进度回调不能拿旧片段 seek 新视频。
     * @return true 表示已经 seek;false 表示放弃跳过(不 seek、不记统计)。
     */
    private fun performSkipIfStillValid(
        contextHash: Int,
        handle: PlayerHandle,
        segment: SponsorSegment,
        reason: String,
        decisionState: PlayerState,
    ): Boolean {
        if (closed.get()) return false
        val currentState = latestStateByContext[contextHash]
        if (currentState != null && videoKey(currentState) != videoKey(decisionState)) {
            module.info(
                "skip abandoned ($reason, video changed) context=$contextHash " +
                    "decision=${videoKey(decisionState)} current=${videoKey(currentState)}",
            )
            return false
        }
        val position = currentPositionMs(contextHash)
            ?: currentState?.currentPositionMs?.takeIf { it > 0 }
            ?: return false
        val duration = currentState?.durationMs ?: 0L
        if (position >= segment.endMs || (duration > 0L && position >= duration)) {
            module.info(
                "skip abandoned ($reason, already past segment) context=$contextHash " +
                    "position=$position segment=${segment.startMs}-${segment.endMs}",
            )
            return false
        }
        val target = if (duration > 0L) segment.endMs.coerceIn(0, duration) else segment.endMs
        return PlayerActions.seekTo(module, handle.core, target)
    }

    /**
     * 切集 / 换容器时的策略状态重置:解除静音、收起手动按钮、清空手动按钮 key 表,
     * 并删掉**上一个视频**的已跳过集合(重新看同一视频时才能再跳过)。
     *
     * 必须在拿到新片段之前执行 —— 这正是「缓存未命中」窗口期旧状态会误伤新视频的地方。
     */
    /**
     * 是否切换到了另一个视频（bvid:cid 变化）。
     *
     * 首次拿到 state（previous == null）不算切换；只有真正的换视频/切集才需要重置上一集的
     * 静音、手动按钮与跳过桶，否则会把上一集的状态用到新视频上。
     */
    private fun isVideoChanged(previous: PlayerState?, current: PlayerState): Boolean {
        if (previous == null) return false
        return previous.bvid != current.bvid || previous.cid != current.cid
    }

    private fun resetContextPolicyState(contextHash: Int, container: Any) {
        AudioMuteController.unmute(module, container, contextHash)
        ManualSkipButton.hide(module, container)
        manualButtonSegmentKeyByContext.remove(contextHash)
        removeOrphanSkippedBucket(contextHash)
    }

    /**
     * 删除「不再有任何 context 引用」的视频跳过集合。
     *
     * 同一 context 切集时由 [onVideoIds] 先写入新 state 再调用,所以此时旧视频已经没人引用,
     * 桶会被删掉;而多 context(小窗/多实例)播放同一视频时不会互相踩掉。
     */
    private fun removeOrphanSkippedBucket(contextHash: Int) {
        val keys = skippedSegmentsByVideo.keys.toList()
        for (key in keys) {
            val stillUsed = latestStateByContext.any { (hash, other) ->
                hash != contextHash && videoKey(other) == key
            }
            if (!stillUsed) {
                skippedSegmentsByVideo.remove(key)
            }
        }
    }

    private fun skippedSegmentsForVideo(key: String): MutableSet<String> =
        skippedSegmentsByVideo.getOrPut(key) { ConcurrentHashMap.newKeySet() }

    /**
     * sanitize 结果缓存:键 videoKey,值 (源列表引用, 过滤后列表)。
     *
     * `stateSegments` 位于进度条每帧绘制、每 tick 进度回调、每 setText 时间扣减三条高频路径上;
     * 片段列表只在重新拉取时才会变(新列表 = 新实例),用**引用同一性**判定缓存是否可用,
     * 命中时零分配。repository 缓存过期/被清后 `getCached` 返回 null,天然短路不会用到旧值。
     */
    private val sanitizedCache = ConcurrentHashMap<String, Pair<List<SponsorSegment>, List<SponsorSegment>>>()

    /** 已打过「dropped N invalid」日志的视频,避免该日志按 tick/帧频率刷屏。 */
    private val droppedSegmentsLogged = ConcurrentHashMap.newKeySet<String>()

    /** 该 context 对应视频的片段(已过一遍健全性过滤)。没缓存时返回 null。 */
    private fun stateSegments(contextHash: Int, state: PlayerState): List<SponsorSegment>? {
        val query = SponsorBlockQuery(state.bvid, state.cid)
        val fresh = repository.getCached(query)
        val last = repository.getLastKnown(query)
        // TTL>0 且新鲜缓存过期时后台重拉;TTL=0 只在进页/手动刷新拉,避免每 tick 打网。
        if (fresh == null && last != null && !inFlight.contains(videoKey(state))) {
            scheduleSegmentFetch(videoKey(state), query, false, "segments refetch")
        }
        val cached = fresh ?: last ?: return null
        val key = videoKey(state)
        sanitizedCache[key]?.let { (source, filtered) ->
            if (source === cached) return filtered
        }
        val filtered = sanitizeSegments(cached, state.bvid, state.cid, state.durationMs)
        if (sanitizedCache.size > SANITIZED_CACHE_MAX) {
            sanitizedCache.clear()
        }
        sanitizedCache[key] = cached to filtered
        return filtered
    }

    /**
     * 分段解析后的最后一道健全性兜底(网络层已经过滤过一遍):
     *   - `startMs < 0`:起点越界;
     *   - `endMs <= startMs`:零长 / 逆序片段,seek 过去等于原地回跳;
     *   - `endMs - startMs < MIN_SEGMENT_MS`:过短片段跳起来只是闪一下,不参与决策;
     *   - `durationMs > 0 && startMs >= durationMs`:起点已越过片长,无效。
     *
     * 这些片段一律丢弃,避免它们进入 [SkipDecision] 与进度条标记绘制。
     */
    private fun sanitizeSegments(
        segments: List<SponsorSegment>,
        bvid: String,
        cid: Long,
        durationMs: Long,
    ): List<SponsorSegment> {
        if (segments.isEmpty()) return segments
        val filtered = segments.filter { segment ->
            val startMs = segment.startMs
            val endMs = segment.endMs
            startMs >= 0L &&
                endMs > startMs &&
                (endMs - startMs) >= MIN_SEGMENT_MS &&
                !(durationMs > 0L && startMs >= durationMs)
        }
        if (filtered.size != segments.size) {
            // 这条日志曾经没有去重:调用方在高频路径上,一旦有片段被过滤就会按 tick/帧刷屏。
            if (droppedSegmentsLogged.add("$bvid:$cid")) {
                module.info(
                    "dropped ${segments.size - filtered.size} invalid segment(s) video=$bvid cid=$cid",
                )
            }
        }
        return filtered
    }

    private fun videoKey(state: PlayerState?): String =
        if (state == null) "" else "${state.bvid}:${state.cid}"

    private fun segmentKey(contextHash: Int, segment: SponsorSegment): String =
        "$contextHash:${segment.uuid}:${segment.startMs}-${segment.endMs}"

    /**
     * 按 contextHash 清理播放器状态(延迟清理路径专用)。
     *
     * 与 [onPlayerDestroyed] 的区别:销毁时机上宿主 widget 往往已经 detach,
     * 反射取 Context 会失败,`PlayerBridge.contextHash(host)` 会返回 0,
     * 按 host 对象清理不可靠 —— 这里直接收 hash。
     *
     * 静音的解除:用 controller 里记录的 container(仍在内存里,Context 可反射)
     * 做 AudioManager 宿主;拿不到时只清记账,流层 unmute 由剩余 context 的回调兜底。
     */
    fun onPlayerContextDestroyed(contextHash: Int) {
        if (contextHash == 0) return
        val container = latestContainerByContext[contextHash]
        if (container != null) {
            AudioMuteController.unmute(module, container, contextHash)
            ManualSkipButton.hide(module, container)
        }
        removeContext(contextHash)
    }

    /** 播放器销毁时调用:确保静音被取消,避免静音状态泄漏到其它媒体。 */
    fun onPlayerDestroyed(host: Any) {
        val contextHash = PlayerBridge.contextHash(host)
        if (contextHash != 0) {
            AudioMuteController.unmute(module, host, contextHash)
        } else {
            AudioMuteController.unmute(module, host)
        }
        ManualSkipButton.hide(module, host)

        if (contextHash != 0) {
            removeContext(contextHash)
        } else {
            val staleKeys = latestContainerByContext
                .filterValues { it === host }
                .keys
                .toList()
            for (key in staleKeys) {
                removeContext(key)
            }
        }

        // 不在 destroy 路径 close():executor 被 shutdown 后 closed 置位,bindPlayerHandle/
        // onVideoIds 全部静默失效。宿主 onVideoIds 未到就先触发 destroy 时(时序不确定),
        // 后续播放会整体失活且无日志。close 只由显式生命周期入口(模块关闭)调用,
        // destroy 路径只清理 per-context 状态。
    }

    private fun removeContext(contextHash: Int) {
        latestStateByContext.remove(contextHash)
        latestContainerByContext.remove(contextHash)
        playerHandles.remove(contextHash)
        manualButtonSegmentKeyByContext.remove(contextHash)
        removeOrphanSkippedBucket(contextHash)
    }

    /**
     * 继承上一个 controller 的播放状态(设置变更重建 controller 时调用,必须在 close 之前)。
     *
     * `onVideoIds` 只在播放条目变化时触发,同一视频内不会再来一次 —— 不迁移的话,
     * 面板里改任意开关后,当前视频的跳过/静音/手动按钮会整体失效到下一集。
     */
    fun adoptStateFrom(previous: SponsorBlockController) {
        if (previous === this || closed.get()) return
        latestStateByContext.putAll(previous.latestStateByContext)
        latestContainerByContext.putAll(previous.latestContainerByContext)
        playerHandles.putAll(previous.playerHandles)
        // 不迁移 manualButton key 表:旧 controller close() 时会隐藏按钮(模块级 UI,作用于容器本身),
        // 若迁移 key 表,同片段的下个进度回调会误判「按钮已在展示」而不再重设 —— 让下个 tick 自然重现即可。
        for ((key, bucket) in previous.skippedSegmentsByVideo) {
            skippedSegmentsByVideo.getOrPut(key) { ConcurrentHashMap.newKeySet() }.addAll(bucket)
        }
        // 片段缓存也一并继承:否则重建后 repository 缓存为空,当前视频的片段要等下一次
        // onVideoIds(同一视频内不会来)才重新拉取 —— 期间进度条标记/跳过全部消失。
        repository.transferCacheFrom(previous.repository)
        // 旧 controller 关闭时,在途的 fetch 任务会被丢弃、已返回的结果落在旧库缓存里
        // (transfer 已执行完,拷不走)。给这些视频在新 executor 上补一次拉取,
        // 否则"改设置恰逢拉取在途"时当前视频片段会丢失到下一集。
        // 注意:不能直接复制 previous.inFlight —— 那会把 inFlight 防并发永久毒化
        // (旧任务的 finally remove 的是旧集合,新集合里的 key 永远清不掉)。
        for (pendingKey in previous.inFlight) {
            val bvid = pendingKey.substringBeforeLast(':')
            val cid = pendingKey.substringAfterLast(':').toLongOrNull() ?: 0L
            if (repository.getCached(SponsorBlockQuery(bvid, cid)) != null) continue
            if (!inFlight.add(pendingKey)) continue
            executor.execute {
                if (closed.get()) return@execute
                try {
                    repository.fetchAndCache(SponsorBlockQuery(bvid, cid))
                } finally {
                    inFlight.remove(pendingKey)
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 关闭前先把挂在播放器上的策略 UI/音频状态清干净:否则总开关一关,
        // 静音记账没有人管 —— STREAM_MUSIC 的静音会跨 App 泄漏。
        for ((hash, container) in latestContainerByContext) {
            AudioMuteController.unmute(module, container, hash)
            ManualSkipButton.hide(module, container)
        }
        inFlight.clear()
        fetchGeneration.clear()
        latestStateByContext.clear()
        latestContainerByContext.clear()
        playerHandles.clear()
        skippedSegmentsByVideo.clear()
        sanitizedCache.clear()
        manualButtonSegmentKeyByContext.clear()
        executor.shutdownNow()
        repository.close()
    }

    private fun getCategoryDisplayName(category: String): String =
        io.github.bitstandbyyou.bilisb.model.SponsorCategories.displayName(category)

    companion object {
        /** 片段健全性下限:短于 250ms 的片段不参与跳过/静音/标记。 */
        private const val MIN_SEGMENT_MS = 250L

        /** 进度回退超过该阈值时清掉该视频已跳过集合,允许重新跳过。 */
        private const val REWIND_RESET_MS = 2000L

        /** sanitize 缓存条目软上限(键是 videoKey,正常一个会话远达不到)。 */
        private const val SANITIZED_CACHE_MAX = 64
    }
}
