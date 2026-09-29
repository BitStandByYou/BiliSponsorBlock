package io.github.bitstandbyyou.bilisb.player

import android.content.Context
import io.github.bitstandbyyou.bilisb.host.HookResolve
import io.github.bitstandbyyou.bilisb.host.HostTargets

/**
 * 播放器容器桥接。
 *
 * 只负责从播放器容器上取 core / android context，用于 seek 跳过与 toast 提示。
 *
 * 9.12.0 / 9.14.0 的容器是 `tv.danmaku.biliplayerv2.PlayerContainer`（由 widget 的
 * `bindPlayerContainer(PlayerContainer)` 传入），取 Context 的方法是 `getContext()`。
 *
 * video id(aid / cid)不由这里取 —— 见 [VideoDirectorListener]。
 */
object PlayerBridge {
    /** core 服务接口名（9.12.0 / 9.14.0：`tv.danmaku.biliplayerv2.service.IPlayerCoreService`）。 */
    private const val CORE_SERVICE_TYPE = HostTargets.CORE_SERVICE_TYPE

    fun coreService(playerContainer: Any): Any? {
        return HookResolve.invokeNoArg(playerContainer, listOf(HostTargets.GET_CORE_METHOD))
    }

    /**
     * 从 director 服务实例上取 core。
     *
     * 经验：`bindPlayerContainer` 触发时 widget 的 `getPlayerCoreService()` 可能还是 null
     * （core 是稍后注入的），而 `PlayDirectorServiceV3` 里持有 core 字段，
     * 所以用「字段类型名匹配」把它读出来，作为 core 的兜底来源。
     */
    fun coreServiceFromDirector(directorService: Any?): Any? {
        if (directorService == null) return null
        return runCatching {
            directorService.javaClass.declaredFields.firstOrNull { it.type.name == CORE_SERVICE_TYPE }
                ?.apply { isAccessible = true }
                ?.get(directorService)
        }.getOrNull()
    }

    fun context(playerContainer: Any): Context? {
        if (playerContainer is Context) return playerContainer
        if (playerContainer is android.view.View) return playerContainer.context
        val value = HookResolve.invokeNoArg(playerContainer, HostTargets.CONTAINER_CONTEXT_METHODS)
        return value as? Context
    }

    /**
     * 从宿主对象（容器 / widget / View）解包出 Activity。
     *
     * 容器的 Context 可能是主题包装后的 ContextWrapper，不是 Activity，
     * 所以必须逐层解包 `baseContext`；同时取 Context 的方法名是 `t()` 而不是 `getContext()`。
     */
    fun activity(host: Any): android.app.Activity? {
        var current: Context? = context(host)
        var depth = 0
        while (current != null && depth < 10) {
            if (current is android.app.Activity) return current
            current = (current as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    fun contextHash(playerContainer: Any): Int {
        return context(playerContainer)?.hashCode() ?: 0
    }

    fun contextHash(context: Context): Int {
        return context.hashCode()
    }
}
