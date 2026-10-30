package io.github.bitstandbyyou.bilisb.settings

/**
 * 设置键与默认值定义。
 *
 * 存储架构:LSPosed 模块进程(Hook 运行在目标宿主进程里)和设置 Activity(在模块 APK 里)
 * 是不同进程。模块 APK 的 SharedPreferences 是**权威存储**(由 [SettingsProvider] 暴露),
 * JSON 镜像文件只是兜底副本。读取端([ModuleSettings])走三级 fallback:
 *   1) ContentProvider IPC (权威存储,模块进程存活时)
 *   2) JSON 镜像文件 (模块进程已退出/被系统拦截时)
 *   3) 默认值
 *
 * **这里只放"用户能改"的设置**。服务器地址、缓存 TTL、最小片段时长、自动跳过倒计时、
 * 标记颜色都不再是设置项:它们固定为代码里的默认值(见 [SettingsCodec])，prefs 里的历史残留键
 * 不参与读取。提交功能已移除,用户 ID / 默认提交类别等键也一并没有。
 */
object SettingsKeys {
    const val PREFS_NAME = "sponsorblock_settings"
    const val MIRROR_FILE = "sponsorblock_settings.json"

    // 本地 ↔ 权威存储的同步状态标记(属于内部元数据,不是用户设置)
    // KEY_LOCAL_DIRTY: 本地 prefs 有还没确认推送成功的改动 —— 为 true 时不能用权威快照覆盖本地。
    const val KEY_LOCAL_DIRTY = "local_dirty"

    // 总开关
    const val ENABLED = "enabled"

    /**
     * 跳过方式三选一,由三个互斥布尔表示(旧版本三键独立勾选,读取时统一归一成单选):
     *   - auto_skip: 自动跳过
     *   - manual_skip: 手动跳过(片段内显示按钮)
     *   - mute_segments: 片段静音(只静音 mute 类片段,不跳过)
     * 归一规则见 [SkipMode.fromFlags];写入时三者恰好一个为 true。
     */
    const val AUTO_SKIP = "auto_skip"
    const val MANUAL_SKIP = "manual_skip"
    const val MUTE_SEGMENTS = "mute_segments"

    /** 镜像 / 统计 JSON 读取上限,超出视为损坏。 */
    const val MAX_LOCAL_FILE_BYTES = 256 * 1024

    // 类别开关 (每个类别一个 bool)
    const val CAT_SPONSOR = "cat_sponsor"
    const val CAT_SELFPROMO = "cat_selfpromo"
    const val CAT_INTERACTION = "cat_interaction"
    const val CAT_INTRO = "cat_intro"
    const val CAT_OUTRO = "cat_outro"
    const val CAT_PREVIEW = "cat_preview"
    const val CAT_MUSIC_OFFTOPIC = "cat_music_offtopic"
    const val CAT_FILLER = "cat_filler"
    const val CAT_POI_HIGHLIGHT = "cat_poi_highlight"

    // UI 开关
    const val SHOW_TOAST = "show_toast"
    const val SHOW_SEEKBAR_MARKER = "show_seekbar_marker"
    const val SHOW_TIME_DEDUCTION = "show_time_deduction"

    // 是否记录/展示跳过统计（设置页的「跳过次数统计」开关）
    const val SHOW_SKIP_STATS = "show_skip_stats"

    /** 首页推荐隐藏整段推广卡片；播放页更多视频仅隐藏整段广告卡片。 */
    const val HIDE_FULL_VIDEO_LABEL_CARDS = "hide_full_video_label_cards"

    /** 类别 key → SponsorBlock category 字符串 */
    val CATEGORY_MAP = mapOf(
        CAT_SPONSOR to "sponsor",
        CAT_SELFPROMO to "selfpromo",
        CAT_INTERACTION to "interaction",
        CAT_INTRO to "intro",
        CAT_OUTRO to "outro",
        CAT_PREVIEW to "preview",
        CAT_MUSIC_OFFTOPIC to "music_offtopic",
        CAT_FILLER to "filler",
        CAT_POI_HIGHLIGHT to io.github.bitstandbyyou.bilisb.model.SponsorCategories.POI_HIGHLIGHT,
    )
}
