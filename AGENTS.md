# BiliSponsorBlock（哔哩哔哩空降助手）

哔哩哔哩**国内版** `tv.danmaku.bili` 9.12.0 的 SponsorBlock 跳过模块。
由 [`ch6vip/lsposed-bili-sponsorblock`](https://github.com/ch6vip/lsposed-bili-sponsorblock)（MIT）移植，
Hook 层针对国内版重新实现。

- 作者：BitStandByYou
- 框架：现代 libxposed API 101（`io.github.libxposed:api`）
- applicationId：`io.github.bitstandbyyou.bilisb`；Kotlin 包名同名
- 目标：`tv.danmaku.bili` 9.12.0 / versionCode 9120300
- **无桌面图标**：设置 UI 在宿主进程内以 Dialog 呈现（「我的」页注入的行）
- 日志 TAG：`BiliSB`

## 目录

```
app/src/main/kotlin/io/github/bitstandbyyou/bilisb/
    Entry.kt                   libxposed 模块入口（按包名/主进程过滤）
    BiliSponsorBlockHooks.kt   各 Hook 安装入口与编排
    host/
        HostTargets.kt         国内版锚点候选表（改版先改这里）
        DexKitResolver.kt      混淆锚点的运行时定位（候选落空才触发）
        HookProbe.kt           命中率探针与解析工具
    hook/MineMenuInjector.kt   「我的」页设置入口注入
    hook/MorePanelInjector.kt  播放器「更多」面板注入（UIComponent 适配器）
    player/                    播放器绑定、进度回调、aid/cid 采集、静音
    sponsor/ net/ model/ ui/ settings/ util/   业务层（上游复用）
app/src/main/resources/META-INF/xposed/
    module.prop / java_init.list / scope.list
```

## 构建

要求 JDK 21（AGP 9.4.1，不支持 JDK 26）；本机 JDK 路径写在用户级 `~/.gradle/gradle.properties`，不入库。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装启用（本机）

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
su -c '/data/adb/modules/zygisk_vector/cli modules enable io.github.bitstandbyyou.bilisb'
su -c '/data/adb/modules/zygisk_vector/cli scope set io.github.bitstandbyyou.bilisb tv.danmaku.bili/0'
adb shell am force-stop tv.danmaku.bili
```

## 验证

```bash
# 模块加载、Hook 命中汇总、运行期探针
adb logcat -s BiliSB
```

关键日志：

- `[probe] hook summary: N/M hit` —— 各 Hook 是否装上
- `player bound context=... core=...` —— 容器绑定成功
- `videoDirector: FOUND aid=... cid=...` —— aid/cid 采集成功
- `segments fetched ... status=200 count=N` —— 片段拉取成功
- `[probe] seekTrackCalled:<类名>` / `[probe] seekDraw:<类名>` —— 进度条标记绘制点

## 约定

- 宿主锚点集中写在 `host/HostTargets.kt`，不要散落到各 Hook 文件。
- 被混淆的锚点用 DexKit 运行时定位（见 `docs/宿主适配.md`），真名候选优先、缺失才触发。
- 宿主适配结论统一写进 `docs/宿主适配.md`。
- 改动一律本地提交，提交信息用中文一句话说清做了什么。
