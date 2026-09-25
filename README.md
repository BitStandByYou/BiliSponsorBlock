# 哔哩哔哩空降助手

一个独立的 **LSPosed / Vector** 模块：在 **哔哩哔哩国内版（`tv.danmaku.bili`）** 客户端里自动跳过
**赞助广告、片头、自我推广、互动提醒**等片段，并在进度条上把它们标出来。

数据来自社区众包的 [SponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock/wiki/API) 公开接口。
模块**只改本机客户端的播放行为**，不登录、不接管账号、不改任何服务端请求。

> 本仓库是 [`ch6vip/lsposed-bili-sponsorblock`](https://github.com/ch6vip/lsposed-bili-sponsorblock)（MIT）
> 的**国内版移植**：复用了其业务层（SponsorBlock 客户端、跳过决策、设置、UI、进度条标记），
> **Hook 层针对国内版 9.12.0 重新实现**（国内版与国际版的混淆形态完全不同，上游的 Hook 点名不可用）。

## 目标宿主

| 项 | 值 |
| --- | --- |
| 包名 | `tv.danmaku.bili`（哔哩哔哩国内版） |
| 版本 | **9.12.0 / versionCode 9120300** |
| 框架 | Vector / LSPosed，现代 libxposed API 101 |
| 设备 | arm64-v8a，Android 16 |

**只适配 9.12.0 这一个版本**。宿主改版 R8 重排混淆名后可能静默失效；
被混淆的锚点已尽量交给 DexKit 运行时定位（详见 [`docs/宿主适配.md`](docs/宿主适配.md)）。

## 功能

- **自动跳过** —— 进入片段时自动跳到片段末尾，可选「N 秒后跳过 + 取消」
- **手动跳过** —— 改为在片段内显示跳过按钮，点按才跳
- **片段静音** —— 对 `mute` 类片段静音而非跳过
- **最小片段时长过滤** —— 太短的片段不跳，避免进度条抖动
- **进度条彩色标记** —— 9 个分类各自着色，颜色可自定义
- **剩余时长扣减** —— 总时长减去已跳过时长
- **跳过 Toast 提示** / **跳过次数统计**（累计次数与节省时长，可重置）
- **片段提交** —— 标记片段起点/终点并提交到数据源
- **设置入口**：宿主「我的」页里注入的 **哔哩哔哩空降助手** 行（点开即同一套设置 UI）

可识别的分类：赞助/恰饭、自我推广、互动提醒、开场动画、结束画面、回顾/概要、非音乐片段、填充内容、精彩时刻。

## 环境要求

| 项 | 要求 |
| --- | --- |
| Root | 需要（KernelSU / Magisk 均可） |
| 框架 | [Vector](https://github.com/JingMatrix/Vector) 或 LSPosed，需支持 **libxposed API 101** |
| Android | 8.0+（`minSdk 26`，DexKit 需要未压缩 so） |
| 宿主客户端 | **哔哩哔哩国内版 `tv.danmaku.bili` 9.12.0** |

## 安装与启用

```bash
# 构建
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

adb install -r app/build/outputs/apk/debug/app-debug.apk

# Vector（KernelSU 系统模块）启用与作用域；路径随框架安装方式可能不同
su -c '/data/adb/modules/zygisk_vector/cli modules enable io.github.idongyou.bilisb'
su -c '/data/adb/modules/zygisk_vector/cli scope set io.github.idongyou.bilisb tv.danmaku.bili/0'

# 强制停止宿主再打开，让 Hook 生效
adb shell am force-stop tv.danmaku.bili
```

启用后无需任何操作即生效。设置页在宿主「我的」页里的 **哔哩哔哩空降助手** 行。
模块**没有桌面图标**（不声明 LAUNCHER）；调试时可直接打开设置页：

```bash
adb shell am start -n io.github.idongyou.bilisb/.settings.LauncherActivity
```

## 设置项

| 分组 | 项 | 默认 | 说明 |
| --- | --- | --- | --- |
| 总开关 | 启用 SponsorBlock | 开 | 关闭后模块完全不工作 |
| 自动跳过 | 自动跳过 | 开 | 检测到片段时自动跳过 |
| | 手动跳过 | 关 | 片段内显示跳过按钮，点按才跳 |
| | 片段静音 | 关 | 对 `mute` 类片段静音而非跳过 |
| | 最小片段时长（秒） | 0 | 短于此值的片段不跳、不显示按钮；0 = 不过滤 |
| | 自动跳过倒计时（秒） | 0 | >0 时先显示「N 秒后跳过 [取消]」 |
| 跳过类别 | 9 个分类开关 | 全开 | 逐个分类启用/禁用 |
| 标记颜色 | — | 见设置页 | 点色块自定义各分类颜色 |
| 界面显示 | 跳过提示 / 进度条标记 / 时间扣减 / 跳过次数统计 | 开 | — |
| 服务器 | 服务器地址 | `https://bsbsb.top` | 任何兼容 SponsorBlock API 的实例 |
| | 缓存 TTL（分钟） | 60 | 拉取结果的本地缓存时长 |
| | 默认标记类别 | `sponsor` | 提交片段时的默认分类 |
| 提交配置 | 用户 ID | 首次使用自动生成 | 本地 UUID，与 B 站账号无关 |

## 数据与隐私

模块只和设置里填的 SponsorBlock 实例通信：

- 播放时 `GET {服务器}/api/skipSegments/{prefix}`，`{prefix}` 是视频 ID 的 **SHA-256 前 4 个十六进制字符**，
  服务端只看到不完整前缀，客户端本地按完整 videoID 过滤（SponsorBlock 官方的隐私设计）。
- 提交片段时 `POST {服务器}/api/skipSegments`，带 videoID、时间区间、分类与本机生成的用户 ID。
- 无任何遥测 / 埋点。设置只存本地（宿主进程的 `SharedPreferences` + JSON 镜像兜底）。

## 已知限制

- **宿主版本锁死 9.12.0**：改版后可能静默失效（混淆锚点有 DexKit 兜底，但方法语义变化需人工跟进）。
- **播放器「更多」面板未适配**：9.12.0 的面板已改为 Compose 渲染，上游的 RecyclerView 注入方式不适用；
  片段查看/手动跳过/提交/刷新等功能目前只在设置弹窗里提供，播放器内暂无「空降助手」行。
- 小窗、切集、番剧/OGV、深色模式、切换账号尚未逐一验证。

## 构建

要求 JDK 21（AGP 9.4.1，不支持 JDK 26）。本机 JDK 路径写在用户级 `~/.gradle/gradle.properties`，
不入库。

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # 无签名材料时产出未签名包
./gradlew :app:testDebugUnitTest # 单元测试
```

release 刻意**不启用 R8**：模块靠反射与动态代理对接宿主混淆成员，混淆自己收益极低。

## 致谢与许可

- [`ch6vip/lsposed-bili-sponsorblock`](https://github.com/ch6vip/lsposed-bili-sponsorblock)（MIT，© 2026 ch6vip）
  —— 本项目由其移植而来，复用了业务层代码与文档结构。
- [小电视空降助手 · hanydd/BilibiliSponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock)
  —— `bsbsb.top` 数据源与分类体系。
- [SponsorBlock](https://sponsor.ajay.app/) —— 片段数据与 API 协议。
- [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit) —— 运行时 dex 解析，用于定位宿主混淆成员。
- [Vector](https://github.com/JingMatrix/Vector) / [LSPosed](https://github.com/LSPosed/LSPosed) —— 框架与 API。

许可：[MIT](LICENSE)。
