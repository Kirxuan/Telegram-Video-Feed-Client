<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="112" alt="VELORA 应用图标">
</p>

<h1 align="center">VELORA（曜流）</h1>

<p align="center">
  <strong>把散落在 Telegram 频道里的视频，重新汇成一条属于你的流。</strong><br>
  曜流，让精彩自然流动。<br>
  <sub>VELORA — Let Content Flow.</sub>
</p>

<p align="center">
  <a href="https://github.com/Kirxuan/TG-Video-Feed-Client/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Kirxuan/TG-Video-Feed-Client?display_name=tag&sort=semver"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="ARM64" src="https://img.shields.io/badge/ABI-arm64--v8a-6A5ACD">
  <a href="LICENSE"><img alt="Apache License 2.0" src="https://img.shields.io/badge/License-Apache%202.0-blue.svg"></a>
</p>

<p align="center">
  <a href="https://github.com/Kirxuan/TG-Video-Feed-Client/releases/latest"><strong>⬇️ 下载最新版 APK</strong></a>
  ·
  <a href="#三分钟开始使用">🚀 开始使用</a>
  ·
  <a href="#隐私不是一句口号">🛡️ 隐私设计</a>
</p>

---

Telegram 里关注的频道越来越多，真正想看的视频却常常被聊天、转发和新消息冲散。

**VELORA（曜流）** 是一款原生 Android Telegram 频道视频浏览器。它使用你自己的 Telegram 个人账号和官方 TDLib，把你已经有权访问的频道视频整理为本地索引，再通过频道、话题标签和播放顺序，组成一条干净、连续、可筛选的竖向视频流。

```text
你已经加入的频道
        ↓
选择频道并建立本地索引
        ↓
用频道 + Hashtag 找到想看的内容
        ↓
上下滑动，连续播放
```

它不是另一个内容平台，也不会把频道内容上传到项目自建服务。没有广告、推荐算法、统计埋点、遥测服务器和 Web 后台——**看到什么，由你的 Telegram 频道和你的选择决定。**

> VELORA 由 **麒轩（Kirxuan）** 创建，是非官方 Telegram 客户端，与 Telegram 官方没有隶属、认可或合作关系。每位使用者需要自行申请并妥善保管 Telegram API 参数。

## 界面一览

<p align="center">
  <a href="docs/images/channel-selection.png"><img src="docs/images/channel-selection.png" width="31%" alt="VELORA 频道选择界面"></a>
  <a href="docs/images/tag-filter.png"><img src="docs/images/tag-filter.png" width="31%" alt="VELORA 标签筛选界面"></a>
  <a href="docs/images/vertical-video-feed.png"><img src="docs/images/vertical-video-feed.png" width="31%" alt="VELORA 竖向视频流界面"></a>
</p>

<p align="center">
  <sub>频道选择 · 标签筛选 · 竖向视频流（点击图片查看大图）</sub>
</p>

> 截图来自项目 UI 验收流程。频道名、用户名、标签、数量与视频说明均为脱敏测试数据；视频画面使用无人物、无文字、无品牌的合成示例背景，不包含真实 Telegram 频道内容。

## 为什么你可能会喜欢它

### 一次看完多个频道

不用在频道列表里来回跳转。多选你关心的频道，VELORA 会把其中的普通视频消息建立为本地元数据索引，之后新增、编辑或删除的消息也会继续同步。

### 用标签把内容变成自己的专题

想只看某个主题？直接组合频道与 Hashtag。频道之间使用 OR，标签支持 OR/AND 两种模式，中文、英文及 Unicode 标签都能参与筛选。

### 像刷短视频一样自然，但内容仍属于 Telegram

最新优先或随机播放，上下滑动切换视频；当前页面自动播放，离开的页面立即停止。需要回到上下文时，还可以打开 Telegram 原消息。

### 在画质、流量与等待之间自己做决定

提供自动、省流、720p 和原画偏好；通过 TDLib 分段读取可流式播放的视频。1.2 使用固定双播放器，当前条播放时有界准备唯一下一条。移动数据预加载需在设置中开启，开启后同样支持备用准备；当前缓冲不足、断网或设备压力较高时优先保障当前视频。

### 私人账号工具，就应该保持克制

视频只留在应用内部缓存，不写入相册或公共目录；缓存上限可选，退出账号会清理账号相关索引与媒体。受保护内容不提供保存、导出或分享入口。

## 功能一览

| 你想做的事 | VELORA 提供的能力 |
|---|---|
| 登录自己的 Telegram | 官方 TDLib 个人账号授权，支持手机号、验证码和两步验证 |
| 找到想看的频道 | 发现、搜索、多选当前账号已有权访问的频道 |
| 整理频道视频 | TDLib 视频过滤搜索、本地 Room 元数据索引、断点恢复与增量同步 |
| 精准筛选 | 频道组合、Hashtag 多选、标签 OR/AND 语义 |
| 连续浏览 | 原生 Compose 竖向信息流、最新优先或随机轮次 |
| 控制播放 | 暂停、静音、重播设置、打开 Telegram 原消息 |
| 适应不同网络 | 自动/省流/720p/原画偏好、分段读取、有界下一条预加载 |
| 管理占用空间 | 200MB 至 20GB 可选缓存上限、占用统计、一键清理 |
| 保护私密数据 | Android Keystore AES-GCM、应用私有目录、禁用备份、Release 关闭详细日志 |

## 它适合谁

VELORA 可能很适合你，如果你：

- 已经关注了不少以视频为主的 Telegram 频道；
- 希望按频道或 Hashtag 重组内容，而不是被消息时间线牵着走；
- 喜欢竖向滑动的浏览方式，但不想引入新的账号、广告或推荐算法；
- 愿意使用自己的 Telegram API ID/API Hash，并重视凭证与媒体的本地存储边界；
- 使用 Android 8.0 或更高版本的 ARM64 手机。

它目前可能不适合你，如果你需要 iOS、x86/32 位 Android、多账号同时在线、视频下载/导出、Stories、直播、机器人账号，或完全不想自行申请 Telegram API 参数。当前功能界面以简体中文为主。

## 三分钟开始使用

### 1. 下载并安装

前往 [GitHub Releases](https://github.com/Kirxuan/TG-Video-Feed-Client/releases/latest) 下载最新版正式签名 APK，并安装到 **ARM64、Android 8.0（API 26）或更高版本**的设备。

如果系统提示“未知来源应用”，请只为你用来下载 APK 的可信浏览器或文件管理器授予本次安装权限。建议从本仓库 Release 页面获取安装包，不要使用来源不明的二次打包版本。

当前 v1.2 安装包为 `VELORA-1.2-arm64-v8a.apk`，SHA-256：

```text
6de1a4557ae397841caf8f80fbbfe15b724de1291fa1860eddce7795609dc6d1
```

如果设备上曾安装 Android Debug 证书签名的开发版，需要先卸载旧版再安装正式版；卸载会同时清除旧版的本地登录、索引和缓存。后续版本请始终以对应 Release 页面公布的文件名和校验值为准。

### 2. 申请自己的 Telegram API 参数

用浏览器登录 [my.telegram.org](https://my.telegram.org/)，进入 **API development tools**，申请自己的 API ID 和 API Hash。

> API Hash 相当于敏感凭证。不要把真实值粘贴到 Issue、Pull Request、日志、群聊或任何公开页面；本项目也不会要求你在聊天中提供它。

### 3. 在 VELORA 中完成首次配置

首次启动后，在设备界面填写 API ID 和 API Hash。格式校验通过后，它们会由 Android Keystore 设备密钥进行 AES-GCM 加密，并保存到不可备份的应用私有目录。

接下来按界面完成手机号、验证码及两步验证密码流程。验证码和两步验证密码不会持久化。

### 4. 选择频道，开始流动

选择一个或多个频道，等待应用建立视频元数据索引；随后可以挑选 Hashtag、设置标签 OR/AND 模式与播放顺序，然后进入视频流上下滑动。

## 隐私不是一句口号

VELORA 的隐私边界尽量做到简单、明确、可检查：

- **只申请两项权限：** `INTERNET` 与 `ACCESS_NETWORK_STATE`；
- **不申请敏感权限：** 不读取联系人、短信、电话、麦克风、摄像头、位置、通知或公共存储；
- **没有第三方追踪：** 不包含广告、分析、统计、遥测 SDK 或项目自建服务器；
- **凭证不随 APK 分发：** 正式 Release 不包含维护者或构建者的 Telegram API 参数；
- **敏感输入不持久化：** 验证码与两步验证密码只在授权流程所需的内存生命周期内存在；
- **数据留在应用内：** TDLib 会话、Room 索引、设置与媒体缓存都位于应用私有目录，并被排除在 Android 备份与设备迁移之外；
- **尊重内容保护：** 视频不写入 MediaStore、Downloads、DCIM 或 Movies；受保护内容按策略启用 `FLAG_SECURE`；
- **退出就是退出：** 停止请求和播放，并清理账号相关索引、会话与媒体缓存。

完整威胁边界与剩余风险请阅读[安全设计](docs/SECURITY.md)。已 Root、被恶意调试或系统完整性失守的设备不在本项目能够完全防护的范围内。

## 当前版本与验证状态

当前正式版本为 **VELORA 1.2**，包含移动数据加载优化、播完自动切换、简介折叠、进度条调整及播放期间保持亮屏。正式签名沿用 1.1.0，正式版用户可覆盖升级。详见 [1.2 发布说明](docs/RELEASE_1.2.md)。

1.2 已纳入 Stage 25/26/27/28 的默认优化路径及后续体验修复；历史阶段报告仍按当时的候选状态与实测范围阅读。当前默认采用有界双播放器 C1（最多两个 ExoPlayer、备用无离屏 Surface）；开启移动数据预加载后准备唯一下一条，AUTO 备用优先准备真实 480p 并原样晋升。预加载预算按**秒**表达：目标是备够 **5 秒**，字节数由目标码率推导，硬上限 **20MiB**，大码率视频自然多分配、低清视频自然少分配。备用以实际缓冲 5 秒停止加载；当前缓冲达到 3 秒且未下降即可开始准备（TDLib 的 `NEXT_PRELOAD` 优先级低于当前播放，协议层不会抢占）。首次起播门槛按吞吐余量分档：低清且余量≥2×、首字节 P90≤350ms 用 **800ms**，余量≥1.6× 用 1200ms，高清晰度仅在余量≥2.5× 时用 1800ms，未知或弱网保留 2500ms，重缓冲恢复门槛不降。Stage 28 追加：同一文件只要拥有播放 owner，其残余预加载 owner 不再驱动下载窗口（消除双 owner 拉锯）；"未渲染首帧"时预加载降级为 256 KiB 有界储备而非停止；未渲染首帧时起播门槛按 500 ms/s 衰减、下限 1000 ms，把慢视频的最坏黑屏截断到个位数秒。当前首帧、seek、重缓冲和设备压力始终优先。C2 离屏解码作为独立比较候选，SampleQueue、Owner Promotion、R8/资源压缩和 TDLib 数据库加密实验保持各自显式开关。GitHub 方案比较见 [Stage 28 调研](docs/STAGE28_GITHUB_LOADING_RESEARCH.md)，红米 Note 11 Pro+ 移动数据实测、主机/模拟器检查与限制见 [Stage 28 记录](docs/STAGE28_MOBILE_FAST_START.md)；历史证据见 [Stage 27](docs/STAGE27_MOBILE_FAST_START.md)、[Stage 25](docs/STAGE25_OPTIMIZATION_RESULTS.md) 与 [Stage 26](docs/STAGE26_MOBILE_DUAL_PLAYER.md)。

1.2 发布前主机测试 1815 项全部通过，lint 与 Release 构建通过；正式签名、权限、备份与凭证隔离检查通过。本次发布未重新执行模拟器 UI、真机安装或移动数据回归，这些项目尚未验证。

首个画面和实际可播放时间分开记录，备用晋升遵守正式起播缓冲门槛；少数准备命中的快速切换不能代表任意视频都能秒开，也不能用配置值代替实测耗时。

Stage 24 完成了公开发行所需的用户自行配置路径：所有非 debug 构建强制排除本机 `local.properties` 凭证，设备端参数使用 Android Keystore AES-GCM 加密；密文损坏会失败关闭并要求重新输入，凭证改变时会安全关闭并重建唯一 TDLib Client。

<details>
<summary><strong>查看 1.1.0 验证证据与尚未验证项</strong></summary>

- 1106/1106 项主机测试通过，lint、debug/release 构建通过。
- Release BuildConfig 凭证为空；正式签名 APK 与 Git 跟踪文件的本机真实凭证反向扫描均为零命中。
- 正式签名、`zipalign`、APK Signature Scheme v2/v3、权限、备份规则与 ARM64 ABI 静态 Proof 均通过。
- 仓库所有者报告 1.1.0 已在 ARM64 真机安装并正常使用。
- Codex 未连接设备重复安装本次正式签名 APK；真实 Android Keystore instrumentation 与真实账号逐项登录证据仍为**尚未验证**。
- 历史验证不能保证所有 Telegram 账号、频道、设备和网络环境均表现一致。

详见 [Stage 24 实施与验证记录](docs/STAGE24_USER_CONFIGURED_CREDENTIALS.md)和[验收测试矩阵](docs/ACCEPTANCE_TESTS.md)。

</details>

仓库所有者确认已取得 Telegram 对本次无 sponsored messages/广告发行的书面例外许可；许可文件与账户信息不进入仓库，项目文档不公开其敏感内容。

## 工作原理

VELORA 坚持原生 Android 与清晰的数据边界：

```text
Jetpack Compose UI
        ↓
ViewModel
        ↓
UseCase / Repository 接口
        ↓
Repository 实现
        ↓
TDLib / Room / Media3 适配器
```

- UI 不直接使用 TDLib、Room DAO，也不创建 ExoPlayer；
- TDLib 类型、回调和错误不会越过 `telegram` 模块边界；
- 固定 ACTIVE/STANDBY 双播放器，只有 ACTIVE 发声；备用准备跨正常滑动保留，播放器数量不随页面增长；
- `DataSpec.position/length` 会转换为 TDLib `downloadFile(offset, limit)` 区间请求；
- 当前播放与唯一下一条预加载使用所有权令牌保护，快速滑动会取消已经过期的请求；
- Room 只保存频道、视频、标签、游标与播放历史等元数据，不保存视频字节或授权凭证。

### 技术栈

- Kotlin、Jetpack Compose、Material 3
- Coroutines、Flow、Hilt
- Room、DataStore
- AndroidX Media3 ExoPlayer
- Telegram 官方 TDLib 1.8.66
- Gradle Kotlin DSL 与 Version Catalog
- minSdk 26；当前生产 native 产物仅包含 `arm64-v8a`

## 从源代码构建

### 构建环境

- Android Studio 与 JDK 21（可直接使用 Android Studio 自带 JBR）
- Android SDK 36.1，以及 Gradle 同步时提示的构建工具
- ARM64 Android 设备，Android 8.0（API 26）或更高版本
- 自己在 [my.telegram.org](https://my.telegram.org/) 申请的 Telegram API ID 和 API Hash

Windows 是当前 TDLib 重建脚本的主要已验证主机；仓库已包含有来源记录与哈希验证的 ARM64 TDLib Java/JNI 产物。

### 1. 克隆仓库

```powershell
git clone https://github.com/Kirxuan/TG-Video-Feed-Client.git
cd TG-Video-Feed-Client
```

### 2. 配置 Android SDK

使用 Android Studio 打开仓库并完成 Gradle Sync。Android Studio 通常会在仓库根目录生成 `local.properties` 并写入本机 `sdk.dir`。

### 3. 可选：配置开发用 Telegram 凭证

在根目录 `local.properties` 中加入：

```properties
TELEGRAM_API_ID=<your_api_id>
TELEGRAM_API_HASH=<your_api_hash>
```

`local.properties` 已被 Git 忽略；可提交的 [secrets.defaults.properties](secrets.defaults.properties) 只有空白默认值。

这一步只为本机 debug 开发构建提供便捷回退。`release`、`instrumentation` 及其他非 debug 构建会强制把 BuildConfig 凭证置空，即使 `local.properties` 中有值也不会注入；它们统一使用首次启动的设备端配置流程。

### 4. 构建与测试

Windows PowerShell：

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat lint --no-daemon --console=plain
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

macOS/Linux：

```bash
./gradlew test --no-daemon --console=plain
./gradlew lint --no-daemon --console=plain
./gradlew assembleDebug --no-daemon --console=plain
```

Debug APK 位于 `app/build/outputs/apk/debug/`。连接已授权的 ARM64 Android 设备后可运行：

```powershell
adb devices
.\gradlew.bat installDebug
```

不要在自动化测试中使用真实 Telegram 账号、验证码、密码或 API Hash。

## 已知边界

- 第一版只播放支持流式传输的普通 `messageVideo`；不支持流式的视频不会自动完整下载，并会提示“该视频暂不支持流式播放。”
- 只支持当前账号已经有权访问的频道，不扩大账号权限，也不会绕过已失效或受保护内容。
- 当前仅提供 ARM64 Android 产物，不支持 x86、32 位 Android 或 iOS。
- 当前只保留一个 Telegram 账号会话；退出后可以登录另一个账号，但不并行保存多账号会话。
- 不支持 Bot API、手写 MTProto、Stories、直播、视频留言、付费媒体、视频上传、评论、转发、保存或导出。
- 当前通过 GitHub Release 提供 APK，未在 Google Play 或其他应用商店上架。

## 文档导航

- [产品规格](docs/PRODUCT_SPEC.md)
- [架构设计](docs/ARCHITECTURE.md)
- [安全设计](docs/SECURITY.md)
- [验收测试](docs/ACCEPTANCE_TESTS.md)
- [开发计划](docs/DEVELOPMENT_PLAN.md)
- [TDLib 可复现构建](docs/TDLIB_BUILD.md)
- [TDLib provenance](telegram/tdlib/TDLIB_PROVENANCE.md)
- [Stage 23 视频索引优化](docs/STAGE23_VIDEO_INDEX_SCAN_OPTIMIZATION.md)
- [Stage 24 用户自行配置凭证](docs/STAGE24_USER_CONFIGURED_CREDENTIALS.md)
- [Stage 25 优化候选与验证结果](docs/STAGE25_OPTIMIZATION_RESULTS.md)（未发布；工作树默认 C1，C2/SampleQueue 为独立候选）
- [Stage 26 移动数据双播放器优化](docs/STAGE26_MOBILE_DUAL_PLAYER.md)（历史工作与实测边界）
- [Stage 28 GitHub 视频加载方案对比](docs/STAGE28_GITHUB_LOADING_RESEARCH.md)（本轮必选调研）
- [Stage 28 移动数据秒开优化（第三轮）](docs/STAGE28_MOBILE_FAST_START.md)（当前工作与实测边界）
- [Stage 29 播完自动滑入下一条](docs/STAGE29_CONTINUOUS_PLAYBACK.md)（连续播放实现、GitHub 方案对比与验证；已纳入 1.2）
- [Stage 27 GitHub 视频加载方案对比](docs/STAGE27_GITHUB_LOADING_RESEARCH.md)
- [Stage 27 移动数据秒开优化](docs/STAGE27_MOBILE_FAST_START.md)（历史工作与实测边界）
- [GitHub 视频加载方案对比](docs/STAGE26_GITHUB_LOADING_RESEARCH.md)
- [完整阶段文档目录](docs/)

## 参与贡献

欢迎通过 Issue 和 Pull Request 提交缺陷修复、安全改进、文档完善，以及符合现有隐私和内容保护边界的功能建议。开始前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [AGENTS.md](AGENTS.md)。

如果 VELORA 正好解决了你的需求，欢迎在 GitHub 点一个 ⭐，也欢迎把真实设备与网络环境下的脱敏体验反馈给项目。

## 许可证

除明确标注的第三方组件外，本项目使用 [Apache License 2.0](LICENSE)。TDLib、OpenSSL 和其他依赖继续遵循各自许可证；详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 `telegram/tdlib/licenses/`。

## 免责声明

本项目按“现状”提供，不保证适用于所有 Telegram 账号、频道、设备、网络或司法辖区。使用者应自行遵守 Telegram 服务条款、频道规则、版权要求和所在地法律，并自行承担构建、凭证保管和使用风险。
