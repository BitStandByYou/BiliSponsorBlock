package io.github.idongyou.bilisb.player

data class PlayerState(
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val durationMs: Long,
    val currentPositionMs: Long,
)
