package io.github.bitstandbyyou.bilisb.player

import android.content.Context

data class PlayerHandle(
    val contextHash: Int,
    val container: Any,
    val core: Any,
    /**
     * 绑定当下解析出的 Android Context。
     *
     * 容器（`PlayerContainer` 的实现类）在部分时机 `getContext()` 会拿不到，
     * 而静音控制等操作需要 Context，故随 handle 一并保存，避免二次反射取空。
     */
    val context: Context,
)
