package io.github.bitstandbyyou.bilisb.host

/**
 * 目标宿主（哔哩哔哩国内版 `tv.danmaku.bili` 9.12.0 / versionCode 9120300）的类名与方法名清单。
 *
 * 国内版 9.12.0 的播放器栈**大量保留真名**（`PlayerProgressObserver`、`VideoDirectorObserver`、`PlayerContainer`、
 * `MenuGroup` 等），所以这里以真名为主，仅对确实被混淆的短名（如 `seek.v3.g`、`mine.d`）保留候选。
 *
 * 候选按顺序取第一个存在的实现，命中/缺失由 [HookProbe] 记录。
 * 依据：对已安装宿主 `base.apk` 的静态分析（dexdump + jadx）。
 */
object HostTargets {
    /** 目标宿主包名（哔哩哔哩国内版）。 */
    const val HOST_PACKAGE = "tv.danmaku.bili"

    /** 宿主数据目录候选（写 JSON 镜像 / 统计文件）。 */
    val HOST_DATA_DIRS = listOf(
        "/data/data/$HOST_PACKAGE",
        "/data/user/0/$HOST_PACKAGE",
    )

    /** 宿主非主进程（命中即跳过 Hook）。 */
    val HOST_SUB_PROCESSES = listOf(
        ":web", ":download", ":pushservice", ":ijkservice", ":widgetProvider",
        ":dd_update", ":heap_analysis", ":safemode",
        ":sandboxed_process0", ":sandboxed_process1", ":sandboxed_process2",
        ":sandboxed_process3", ":sandboxed_process4",
    )

    // ---------------------------------------------------------------- 进度 / 时长

    /** 进度文本控件：进度回调、`setText` 时间扣减的落点。 */
    val PROGRESS_TEXT_WIDGET_CLASSES = listOf(
        "com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget",
        "com.bilibili.app.gemini.player.widget.progress.GeminiProgressTextWidget",
        "com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget",
    )

    /**
     * 进度回调候选（int,int）。9.12.0 实测：`PlayerProgressTextWidget implements
     * PlayerProgressObserver`，回调方法为 `onPlayerProgressChange(int position, int duration)`。
     * 其余为旧版兜底。
     */
    val PROGRESS_CALLBACK_INT_METHODS = listOf(
        "onPlayerProgressChange",
        "updateTime",
    )

    /**
     * 进度回调候选（long,long）：仅探针用，不喂 controller。
     * 9.12.0 未确认存在；保留以防某些 widget 用 long 形态。
     */
    val PROGRESS_CALLBACK_LONG_METHODS = listOf("j0", "k0")

    // ---------------------------------------------------------------- 播放器容器

    /**
     * 容器注入入口：widget 实现 `IControlWidget`，其 `bindPlayerContainer(PlayerContainer)`
     * 在拿到播放器容器时被调用（9.12.0 实测：`PlayerSeekWidget3` / `PlayerProgressTextWidget`
     * 都实现该接口）。
     */
    val CONTAINER_BINDING_CLASSES = listOf(
        "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3",
        "com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget",
        "com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget",
    )
    const val BIND_CONTAINER_METHOD = "bindPlayerContainer"

    /** 9.12.0 容器类型 `tv.danmaku.biliplayerv2.PlayerContainer`。 */
    const val CONTAINER_INTERFACE = "tv.danmaku.biliplayerv2.PlayerContainer"

    /** 从容器取 Android Context：9.12.0 是 `getContext()`。 */
    val CONTAINER_CONTEXT_METHODS = listOf("getContext", "t")

    /**
     * widget 的「从窗口分离」回调 —— 作为**播放器离开**的信号。
     * 退出播放页/全屏切换时会触发，用于清理静音、倒计时、浮层与按钮。
     */
    const val WIDGET_DETACH_METHOD = "onDetachedFromWindow"

    // ---------------------------------------------------------------- 播放器 core

    /** 从 widget/容器取 core 服务。9.12.0 实测保留 `getPlayerCoreService()`。 */
    const val GET_CORE_METHOD = "getPlayerCoreService"

    /** core 服务接口名（9.12.0：`tv.danmaku.biliplayerv2.service.IPlayerCoreService`）。 */
    const val CORE_SERVICE_TYPE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"

    /**
     * 平滑 seek：9.12.0 是 `seekTo(int, boolean)`（`seekTo(int)` 等价于 `seekTo(pos,false)`）。
     */
    val SEEK_SMOOTH_METHODS = listOf("seekTo")

    /** 只有位置参数的 seek（兜底）。 */
    val SEEK_PLAIN_METHODS = listOf("seekTo")

    val GET_DURATION_METHODS = listOf("getDuration", "getRealDuration")
    val GET_POSITION_METHODS = listOf("getCurrentPosition", "getRealCurrentPosition")

    // ---------------------------------------------------------------- 视频信息（aid/cid）

    /** 三类信息流进入视频页共用的播放路由（9.12.0 真机验证）。 */
    const val VIDEO_DETAIL_ROUTE_SCHEME = "bilibili"
    const val VIDEO_DETAIL_ROUTE_HOST = "united_video"
    const val VIDEO_DETAIL_ACTIVITY = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"

    /** director 服务实现类（类名未被混淆）。 */
    val DIRECTOR_SERVICE_CLASSES = listOf(
        "tv.danmaku.biliplayerimpl.videodirector.PlayDirectorServiceV3",
        "tv.danmaku.biliplayerimpl.videodirector.VideosPlayDirectorService",
    )

    /** 注册/注销观察者：9.12.0 实测为 `addVideoDirectorObserver` / `removeVideoDirectorObserver`。 */
    val DIRECTOR_ADD_OBSERVER_METHODS = listOf("addVideoDirectorObserver")
    val DIRECTOR_REMOVE_OBSERVER_METHODS = listOf("removeVideoDirectorObserver")

    /** 9.12.0 观察者接口（未混淆）：`tv.danmaku.biliplayerv2.service.VideoDirectorObserver`。 */
    const val DIRECTOR_OBSERVER_INTERFACE = "tv.danmaku.biliplayerv2.service.VideoDirectorObserver"

    /** 从 director 服务取当前播放条目：`getCurrentPlayableParams()` → `Video$PlayableParams`。 */
    const val DIRECTOR_CURRENT_VIDEO_METHOD = "getCurrentPlayableParams"

    /** 从 widget 取 director 服务的方法候选（9.12.0 未确认，主路径走观察者注册）。 */
    val DIRECTOR_GET_SERVICE_METHODS = listOf(
        "getPlayDirectorServiceV3",
        "getPlayDirectorServiceV2",
        "getPlayDirectorService",
        "getPlayDirector",
    )

    /** `Video$PlayableParams` → `getDanmakuResolveParams()` → `Video$DanmakuResolveParams`。 */
    const val VIDEO_INTERFACE = "tv.danmaku.biliplayerv2.service.Video\$PlayableParams"
    const val VIDEO_PARAMS_CLASS = "tv.danmaku.biliplayerv2.service.Video\$DanmakuResolveParams"
    const val VIDEO_PARAMS_ACCESSOR = "getDanmakuResolveParams"

    /** `DanmakuResolveParams`：`a:J`=avid、`b:J`=cid，另有 `getAvid()` / `getCid()`。 */
    const val VIDEO_PARAMS_AID_FIELD = "a"
    const val VIDEO_PARAMS_CID_FIELD = "b"

    // ---------------------------------------------------------------- 进度条标记

    /**
     * 覆写 `draw(Canvas)` 的进度条绘制候选（按优先级）：
     *   g = 实色矩形轨道层（Drawable，首选）；f = SeekBar 本体（备选）。
     * 9.12.0 实测覆写 `draw(Canvas)` 的类只有 `seek.v3.a`（热度曲线，不能挂）、`f`、`g`。
     */
    val SEEK_TRACK_CLASSES = listOf(
        "com.bilibili.playerbizcommonv2.widget.seek.v3.g",
        "com.bilibili.playerbizcommonv2.widget.seek.v3.f",
    )
    const val DRAW_METHOD = "draw"

    // ---------------------------------------------------------------- 「我的」页入口

    /**
     * 「我的」页 adapter：9.12.0 外层类被混淆成 `tv.danmaku.bili.ui.main2.mine.d`
     * （`HomeUserCenterAdapter$collectPageVisibility$1` 仅作为内部 lambda 名残留）。
     */
    val MINE_ADAPTER_CLASSES = listOf(
        "tv.danmaku.bili.ui.main2.mine.d",
    )

    /** 菜单数据模型（9.12.0 完整保留）。 */
    const val MENU_GROUP_CLASS = "com.bilibili.lib.homepage.mine.MenuGroup"
    const val MENU_ITEM_CLASS = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"

    /**
     * 点击链路拦截候选。
     *
     * 说明：9.12.0 上「我的」页入口采用**直接给注入行绑点击监听**（见 MineMenuInjector 的
     * direct bind 路径），不依赖宿主 URI 路由，因此这里只留少量候选做兜底。
     */
    val ROUTER_CLASSES = listOf(
        "tv.danmaku.bili.ui.intent.IntentHandlerActivity",
    )
}
