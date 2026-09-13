# VELORA（曜流）分阶段开发计划

> 当前已授权阶段（2026-09-10）：Stage 27 移动数据秒开优化。固定两实例复用、预算改为秒级（目标 5 秒、硬上限 20MiB）、480p 低清备用、分档快速起播、移动授权贯通、滑动交接保留、GitHub 方案比较、红米 Note 11 Pro+ 移动数据验证及总体指导同步；不再以单播放器验证为前置。完整阶段合同见 [Stage 27](STAGE27_MOBILE_FAST_START.md)，方案比较见 [Stage 27 调研](STAGE27_GITHUB_LOADING_RESEARCH.md)。

> 2026-09-09：用户已授权在当前未提交候选上继续实施、操作 Redmi 移动数据验收、禁止提交、推送和发布。当前实现与本轮 Proof 见 [Stage 25 实施结果](STAGE25_OPTIMIZATION_RESULTS.md)。以下 Stage 24 状态与既有阶段编号为正式版历史记录。

Stage 25 收尾已重新验证横屏全屏入口：常规 Compose 101/101，三种尺寸各 40/40；最新完整主机 test/lint/build 通过。Robolectric Android 16 的历史环境阻塞在原配置正常重跑时已解除。生产默认已切换为 C1 双播放器；Redmi 移动数据路径正在验收，Wi-Fi、iQOO 12、双 Codec 性能和功耗仍尚未验证。

文档版本：2.4
日期：2026-09-02
状态：Stage 24 用户自行配置凭证、主机 Proof 与正式签名静态 Proof 已完成；仓库所有者报告当前版本 ARM64 真机安装和正常使用通过，并确认已取得 Telegram 对本次无 sponsored messages/广告发行的书面例外许可

## 1. 计划原则

本项目包含授权、同步、数据库、筛选、播放器、分段文件读取、缓存和设备策略等多个独立风险域，不适合一次实现。以下路线是项目级阶段图；每一阶段开始前还必须根据当时仓库写一份该阶段的精确实施计划，列出具体文件、接口、测试和命令，并获得用户确认。

任何阶段只能在前一阶段的 Proof 通过并完成汇报后开始。用户未明确批准时停止。不得用假登录、假频道或假视频掩盖真实 TDLib 路径尚未完成。

## 2. 当前实施状态：Stage 24

Stage 24 把公开发行路径从“每位使用者修改 local.properties 后自行构建”升级为“下载同一份免凭证 release APK，在首次启动填写自己的 API 参数”。所有非 debug BuildConfig 值强制为空；设备端值通过 Android Keystore AES-GCM 加密并保存到 noBackupFilesDir。格式错误、密文/密钥损坏和 TDLib 参数拒绝都有可恢复状态；修改参数时唯一客户端先 Close/Closed 再读取新值创建，保持单客户端所有权。详细合同与证据见 `STAGE24_USER_CONFIGURED_CREDENTIALS.md`。

本阶段不改变 TDLib 协议、账号授权状态来源、权限、Room、Media3、播放器、缓存、预加载或内容保护。主机 `test` 1106/1106、lint、debug/release 构建、BuildConfig 空值、APK 凭证反向扫描、manifest、ABI 与正式签名审计已通过。仓库所有者报告当前版本 ARM64 真机安装和正常使用通过；Codex 本次设备列表为空，未重复执行正式签名 APK 安装、Android Keystore instrumentation 或真实账号逐项验收。仓库所有者确认的书面例外许可解除本次 sponsored messages 发布门槛；许可文件不进入仓库。

## 2.1 上一实施状态：Stage 23

Stage 23 将生产初始索引从 `GetChatHistory` 全普通消息遍历替换为固定 TDLib 1.8.66 的 `SearchChatMessages + SearchMessagesFilterVideo`。Room v5 新增独立策略版本、过滤 cursor、完成事实、候选/页数与近似总数；4→5 迁移保留旧完整频道的完成事实，旧未完成频道保留已有索引并从安全 cursor 0 幂等重扫。页面热路径已改为 existing-key 查询、videos/tags/cross-ref 批量写和一次频道状态更新；全局孤儿标签清理移到完成/编辑/删除等低频边界。协调器以最多 2 个 worker 做近期首轮和公平轮转，任一 FLOOD_WAIT 共享扫描闸门，非零游标停滞进入可恢复错误。UI 只显示视频结果页、候选、唯一索引、完成频道和带“约”的 TDLib 估算。详细合同、审计和分层结果见 `STAGE23_VIDEO_INDEX_SCAN_OPTIMIZATION.md`、`STAGE23_GITHUB_REUSE_AUDIT.md`、`STAGE23_PERFORMANCE_RESULTS.md`。

Stage 23 不修改 TDLib/Media3/缓存/播放器/权限/备份/品牌/授权，也不下载视频字节。用户明确禁止本阶段使用 iQOO 12 或任何实体机；真实账号、真实超长频道、实体机性能与 ARM64 native 均为尚未验证。

## 2.1 上一实施状态：Stage 22

Stage 22 在 Stage 21 既有品牌 locale 资源和登录页品牌区内增加创造者名称与署名模板：中文显示“创造者：麒轩”，英文及默认回退显示 `Created by Kirxuan`。没有新增页面或平行本地化架构；为保持验证码等授权卡片在默认视口可见，仅收紧既有纵向间距，未缩小品牌名称。主机 `test` 1052/1052、`lint`、`assembleDebug` 与 API 36 x86_64 emulator Compose UI 98/98 已通过；英文和 `zh-HK` 冷启动视觉/UI 树通过。ARM64/Vivo 真机 install+launch 尚未验证。详细合同与证据见 `STAGE22_CREATOR_LOCALIZATION.md`。

以下保留 Stage 21 及更早实施记录。

Stage 21 将 Android `versionName` 更新为 `1.0`，把启动器与登录页品牌拆分为 Android locale 资源：中文语言（含简繁体及 CN/HK/MO/SG/TW）显示“曜流”和中文标语，英文与默认资源显示 `VELORA` 和英文标语；登录页品牌名提升为 `headlineSmall`。既有功能 UI 明确保持中文，没有改变 applicationId、namespace、依赖、权限、TDLib、Room、Media3 或缓存边界。主机 `test` 1052/1052、`lint`、`assembleDebug` 与 API 36 x86_64 emulator Compose UI 98/98 已通过；英文和 `zh-HK` 冷启动视觉/UI 树通过。ARM64/Vivo 真机 install+launch 与系统级语言切换尚未验证。详细合同与证据见 `STAGE21_V1_RELEASE_BRAND_LOCALIZATION.md`。

以下保留 Stage 20 及更早实施记录。

Stage 20 将对外品牌更新为 VELORA（曜流），落地中英文标语、新 Android 启动器图标与授权页品牌区；保留 applicationId、namespace、数据库名、Application/Theme 与 Kotlin 类型等内部稳定标识。主机 `test` 1050/1050、`lint`、`assembleDebug` 与 API 36 x86_64 emulator Compose UI 96/96 已通过；ARM64/Vivo 真机 install+launch 尚未验证。详细合同与证据见 `STAGE20_VELORA_BRAND_IDENTITY.md`。

以下保留 Stage 19 及更早实施记录。

Stage 19 在既有 `DesignTokens`、`PremiumBackdrop`、`GlossCard`、`PremiumTopBar`、`SegmentedControl`、`StatePanel`、枚举导航和视频沉浸式栏控制上增量实现，没有建立平行设计系统或迁移到 Navigation Compose。五个页面统一有限状态动画和语义，Android 16 页面按职责处理 safe drawing、IME、cutout 与 gesture 区，根页面不拦截 back-to-home。主机 test/lint/assemble、API 36 x86_64 emulator 95/95 Compose、gestural/three-button、非零 display cutout、横屏、大字体和视觉证据通过；iQOO 12 按用户限制未连接，完整 Vivo Compose Proof 仍为尚未验证。详细合同与结果见 `STAGE19_UI_MOTION_ANDROID16.md`。

以下保留 Stage 18 及更早实施记录。

## 2.1 Stage 18A～18F

Stage 18 已在既有 `TelegramFileManager`、`TelegramMediaDataSource`、`VideoPlayerManager`、`VideoPreloadManager`、网络估计、质量选择、Repository 和 owner token 边界内落地：官方 TDLib HLS 描述、Media3 HLS/direct fallback、真实 TDLib 新网络字节 fast/slow EWMA 与 TTFB、混合 ABR、duration-first 唯一下一条和 10 MiB ceiling，以及可独立关闭的 Media3 SampleQueue 预热。HLS/ABR/dynamic preload 默认开启；SampleQueue 因真实 A/B 尚未验证默认关闭。Stage 18F 固定 seed 主机矩阵和完整 Proof 记录见 `STAGE18F_FINAL_PERFORMANCE_AND_SECURITY_ACCEPTANCE.md`；真实账号、真实 CDN 和 iQOO 12 验证均保持“尚未验证”。

以下段落保留早期交付阶段的实施记录。

仓库所有者已明确把原阶段 2、3、4 合并为交付阶段 2，交付阶段 3 对应原路线“阶段 5：频道发现与多选”；其后又批准把原路线阶段 6–9 的视频元数据、历史分页、增量同步和标签筛选数据能力合并为交付阶段 4。该历史交付当时实现 `TelegramMessageRepository`、Room 版本 2 视频/标签表、近期优先的前台分页协调器、持久游标恢复、增量新建/编辑/删除、标签解析与频道/标签组合查询；当前 schema 与扫描策略以本文件 Stage 23 状态为准。

阶段 4 只读取消息元数据，不调用 `downloadFile`，不增加 Media3、播放器、媒体缓存或完整下载。主机 fresh `test`/`lint`/`assembleDebug`、真机安装、Room 9/9 和 TDLib native 1/1 instrumentation 已通过。真实账号查询得到：所选频道扫描 1,189 条/13 页、索引 540 个视频；全库 553 条视频记录与 553 个联合唯一键，重复记录 0；无标签/单标签/多标签/中文标签/英文标签分别为 117/90/333/378/350。暂停、继续和退到后台自动暂停已人工验证。Compose Path B 已通过：host 编译、Robolectric 共享 suite、无 native instrumentation target APK、API 36 x86_64 AOSP emulator 的 18/18 Compose UI instrumentation，以及 Vivo install + launch smoke 均已取证。历史完整 Vivo instrumentation 被 `fast_freezer`/`single-cleaner` 中止，保留为 OriginOS 环境诊断，不作为 Compose 代码失败。

交付阶段 5 已新增单个视频播放测试页、唯一的 `VideoPlayerManager` 所管理 ExoPlayer、`TelegramFileManager` 与基于 `DataSpec.position` 的自定义 Media3 DataSource。实际数据读取使用官方 `TdApi.DownloadFile(fileId, priority, offset, limit, false)`，只访问 TDLib 的应用内部临时分段缓存；未建立 Media3 完整缓存，未构造 HTTP/消息链接，也不会写入公共目录。主机 `test`、`lint` 和 `assembleDebug` 已通过。真实 `supportsStreaming=true` 视频播放、seek、网络断开重试、logcat、首次播放等待和临时文件增长仍等待仓库所有者真机验收，不能视为已验证。

交付阶段 6 已将信息流接入 Compose `VerticalPager`，页面稳定 250ms 后才由唯一 `VideoPlayerManager` 绑定目标项；滚动期间先暂停旧音频，连续滑动只保留最后一个稳定页任务。最新模式使用发布时间、chatId、messageId 的稳定降序；随机模式逐轮无重复洗牌，下一轮首项避免与上一轮末项相同。失效消息、频道/标签筛选或顺序改变均释放旧绑定并重建队列。详情见 `STAGE6_FEED_HANDOFF.md`。

交付阶段 7 实现唯一下一条 256KiB TDLib 区间预加载、当前/下一条运行时 pin、500MB 默认上限与八档 DataStore 设置、TDLib 精确 `FileTypeVideo` 统计、Room v4 LRU 元数据、启动/阈值/低存储清理、手动清空及网络/省电/热策略。当前播放仍由原唯一 ExoPlayer 承担；没有 Media3 第二份缓存或公共目录写入。完整主机 Proof、Compose Path B emulator 29/29、Room 设备测试 12/12 和真机安装/冷启动已通过；因当前真机没有登录会话，真实账号缓存增长与播放能耗观察尚未验证。结果见 `STAGE7_CACHE_HANDOFF.md`。

交付阶段 8 不增加产品功能，只修复验收中确认的稳定性和安全缺口：受保护窗口标志、音频焦点/耳机拔出配置、解码与超时分类、最多 3 次 Media3 加载重试、信息流离开时完整释放播放器、启动期磁盘/设备信号 I/O 调度，以及系统返回与顶部返回的一致路径。Fresh 主机 Proof、API 36 x86_64 emulator Compose 30/30、Room 12/12、最终 APK 保留数据安装、真实登录恢复、100 次连续滑动、30 组快速往返、进程/锁屏/后台恢复和热状态注入均已取证。真实退出登录、生产缓存清空、真实低存储、物理耳机、外部音频焦点、当前设备上的完整网络断开和真实受保护消息仍为尚未验证；结果见 `STAGE8_FINAL_ACCEPTANCE.md`。

每阶段固定说明 Outcome、Scope、Boundary、Failure states、Proof。以下原始阶段列表保留为产品路线；原路线阶段 6–9 已由交付阶段 4 合并实施，播放器和媒体路线仍须逐阶段获批。

## 3. 阶段 0：环境检查和设计准备（历史基线）

### Outcome

建立可审核的产品、架构、安全、测试和开发基线，不创建业务代码。

### Scope

- AGENTS.md
- README.md
- docs/PRODUCT_SPEC.md
- docs/ARCHITECTURE.md
- docs/SECURITY.md
- docs/ACCEPTANCE_TESTS.md
- docs/DEVELOPMENT_PLAN.md

### Boundary

不创建 Gradle 工程、Kotlin/XML、TDLib、登录 UI、模拟数据或 APK；不安装工具。

### Failure states

- 非 Git 仓库：已取得用户批准并初始化。
- 环境工具缺失：记录为缺失，不安装。
- 无连接设备：ABI 和真机验证标记“尚未验证”。
- 文档矛盾：在交付前修正文档，不进入阶段 1。

### Proof

- 检查七份文件存在且仅为 Markdown。
- git status 和 git diff --check。
- 搜索关键约束、敏感值和禁止项。
- 文档交叉一致性审查。

## 4. 阶段 1：最小可构建 Android 安全骨架（已纳入当前代码基线）

### Outcome

生成可安装的 debug 应用骨架；缺少 Telegram 凭证时真实显示“未配置开发凭证”，不显示假登录。

### Scope

计划创建：

- settings.gradle.kts、根 build.gradle.kts、gradle.properties。
- gradle/libs.versions.toml 和 Gradle Wrapper。
- .gitignore、secrets.defaults.properties。
- app 模块、Application、MainActivity、Material 3 主题。
- AndroidManifest.xml，只有 INTERNET/ACCESS_NETWORK_STATE。
- data_extraction_rules.xml、backup_rules.xml，禁用/排除备份。
- 启动状态模型、凭证配置读取和不含真实值的单元测试。

构建配置：

- namespace/applicationId=com.qixuan.channelvideoflow。
- minSdk=26。
- compileSdk 使用本机 android-36.1 的 release(36)/minorApiLevel=1 DSL。
- targetSdk=36，并通过稳定 AGP 实际同步确认。
- Android Studio 内置 JDK 21。
- AGP 选择满足 Android 16 QPR2 官方下限（8.13.0 或更高）的当日稳定版；所有插件和依赖集中到 libs.versions.toml。

### Boundary

不下载/集成 TDLib，不提交真实 local.properties，不实现手机号/验证码输入，不建立 Room 或播放器。

### Failure states

- 缺少凭证：应用显示中文配置说明。
- 凭证格式错误：只显示缺失/格式错误键名，不输出值。
- SDK/AGP/JDK 不兼容：保留首个构建错误，修正工具链，不降低需求或切换预览依赖。

### Proof

    .\gradlew.bat test
    .\gradlew.bat lint
    .\gradlew.bat assembleDebug

另检查 merged manifest 权限和备份规则；有授权真机时执行 installDebug。

## 5. 原阶段 2：目标 ABI 与官方 TDLib 可复现构建（已合并）

### Outcome

为唯一测试手机 ABI 构建并加载固定官方 TDLib，不同时构建无关 ABI。

### Scope

1. 运行 adb devices 和 adb shell getprop ro.product.cpu.abi。
2. 审查官方 v1.8.0 标签与当前官方源码的所需 API 字段。
3. 选择 Telegram 官方标签或完整 40 位提交 SHA，并在 README/构建记录固定。
4. 根据该源码官方说明确定并安装兼容 NDK、CMake、Command-line Tools；安装动作需用户另行批准。
5. 创建 telegram 模块/清晰 native 目录、官方 Java/JNI 绑定和可复现构建脚本。
6. abiFilters 只包含实测手机 ABI。
7. 记录源码 URL、SHA、NDK/CMake 版本、命令、产物哈希和许可证。

### Boundary

不实现授权 UI、频道、消息、Room 或播放；不使用来源不明预编译 .so。

### Failure states

- 无设备/unauthorized/offline：停止，ABI 尚未验证。
- v1.8.0 缺少所需当前字段：不自行使用不明二进制，提交官方 commit 选型证据供用户审核。
- native 构建失败：定位首个 CMake/NDK/ABI 错误，不改为全 ABI 或跳过加载测试。

### Proof

- 目标 ABI 的 native 构建成功。
- Gradle 打包只含目标 ABI。
- 仪器 smoke test 能加载 TDLib 并安全创建/关闭 Client，不登录账号。
- test、lint、assembleDebug 和真机 installDebug。

## 6. 原阶段 3：TDLib 客户端生命周期与参数（已合并）

### Outcome

真实启动 TDLib，接收 updateAuthorizationState，并在凭证有效/无效和数据库失败时输出安全状态。

### Scope

- core:model、core:domain、core:common 的最小真实类型。
- TelegramClientManager、请求关联器、串行更新 dispatcher。
- TDLib 参数、本地私有数据库/文件目录和脱敏错误映射。
- Hilt 应用级绑定。
- Fake client 与生命周期测试。

### Boundary

只到 WaitingPhoneNumber/初始化错误，不提交手机号，不创建完整登录页面。

### Failure states

未配置凭证、native 加载失败、TDLib 参数错误、数据库打开失败、客户端关闭。

### Proof

- 授权状态序列单元测试。
- 真机启动日志只出现状态名，不含 api_hash/数据库材料。
- test、lint、assembleDebug、installDebug。

## 7. 原阶段 4：真实 Telegram 单账号授权（已合并；真机人工验收尚未验证）

### Outcome

在真机完成手机号、验证码、两步验证密码、会话恢复和退出账号。

### Scope

- feature:auth。
- TelegramAuthRepository 和授权 UseCase。
- 启动/登录 Compose 页面与全部中文状态。
- 输入清理、FLOOD_WAIT、错误映射和会话恢复。
- 退出时停止请求并清除账号数据的初始协调器。

### Boundary

不显示频道，不扫描消息，不播放视频。

### Failure states

错误手机号、验证码、密码、FLOOD_WAIT、断网、未知授权状态、logout/closed。

### Proof

- 授权转换单元测试和登录 UI 状态测试。
- 日志敏感字段测试。
- test、lint、assembleDebug、connectedDebugAndroidTest。
- 真实账号人工登录/重启恢复/退出，结果逐项记录；不自动化保存真实秘密。

## 7. 阶段 5：频道发现与多选（已作为交付阶段 3 实现）

### Outcome

显示当前账号已加入且可访问的频道，并保存多选结果。

### Scope

- core:database 初始 Room、ChannelEntity/DAO。
- TelegramChatRepository。
- feature:channels 搜索、多选、选择计数、加载/空/错误状态。
- chatTypeSupergroup、isChannel 和成员访问状态映射。

### Boundary

不读取历史消息，不下载媒体，不展示标签/Feed。

### Failure states

空频道列表、网络断开、频道信息暂缺、权限丢失、Room 错误。

### Proof

- Repository Fake 测试只返回合法频道。
- Room/DAO 联合状态测试。
- Compose 频道多选测试。
- 真实账号频道列表人工核对。
- test、lint、assembleDebug、适用设备测试。

### 实施结果（2026-07-26）

- 生产 Repository、严格频道/成员状态映射、主列表/归档分页、更新事件和 Room 对账已实现。
- 搜索、多选、保存、加载/空/错误/FLOOD_WAIT 状态和 Fake Repository 测试已实现。
- `test`、`lint`、`assembleDebug` 与 `installDebug` 通过；Room 三条真机 DAO 测试通过。
- 真实账号列表、搜索、选择两个频道、保存及进程重启恢复通过；未读取或记录频道标题。
- `connectedDebugAndroidTest` 在应用 Compose 测试的 `ActivityScenario` 宿主启动处失败；同设备的纯 Room instrumentation 正常，故记录为设备/UI 测试环境阻塞，不虚报通过。
- 退出真实频道后的生产路径验收通过：完整刷新后可用数减少 1，旧记录标记不可用且选择清零，UI 不再显示为可用频道。

## 8. 阶段 6：视频元数据、标签与数据库模型（已作为交付阶段 4 实现）

### Outcome

将一页 Fake/真实映射输入中的普通 messageVideo、说明文字和标签以复合键写入 Room，不下载视频。

### Scope

- VideoEntity、TagEntity、VideoTagCrossRef、PlayHistoryEntity、迁移/DAO。
- TDLib messageVideo mapper。
- formattedText 实体解析和 Unicode 回退解析。
- canBeSaved、supportsStreaming、file remoteUniqueId 映射。

### Boundary

不分页、不持续扫描、不创建播放器。

### Failure states

不支持消息类型、损坏实体范围、未知文件大小、重复消息、Room 事务失败。

### Proof

- 标签 12 类用例。
- chatId+messageId 联合主键测试。
- mapper 和事务回滚测试。
- 验证扫描无 downloadFile 请求。
- test、lint、assembleDebug。

## 9. 阶段 7：历史分页扫描与恢复（已作为交付阶段 4 实现）

### Outcome

选定频道先产生近期可用索引，再以前台分页方式继续更早历史，并在重启后从游标恢复。

### Scope

- TelegramMessageRepository 历史路径。
- per-channel 扫描协调器、每页事务、游标和扫描状态。
- feature:channels 扫描进度显示。
- 有界重试和 FLOOD_WAIT 门控。

### Boundary

不处理实时更新、不播放视频。

### Failure states

空历史、TDLib 实际返回少于 limit、边界重复、Room 失败、权限丢失、FLOOD_WAIT、取消。

### Proof

- 分页/游标/重试/权限单元测试。
- 进程重启恢复集成测试。
- 真实频道近期页和续扫人工观察。
- test、lint、assembleDebug。

## 10. 阶段 8：增量同步、编辑与删除（已作为交付阶段 4 实现）

### Outcome

前台收到新普通视频，编辑说明更新标签，删除消息从本地有效队列移除；离线恢复后完成对账。

### Scope

- updateNewMessage、内容更新、delete 更新 mapper。
- lastNewMessageId 和恢复对账。
- 幂等事务与频道访问状态。

### Boundary

仍不播放媒体。

### Failure states

重复更新、乱序更新、删除边界、丢失消息、权限丢失、对账取消。

### Proof

- 增量测试 SYNC-05 至 SYNC-10。
- Fake 更新风暴不重复、不死锁。
- 真实测试频道新增/编辑/删除人工验收。
- test、lint、assembleDebug。

## 11. 阶段 9：标签筛选与播放队列（标签数据查询已作为交付阶段 4 实现；播放队列未实现）

### Outcome

用户按频道、标签 OR/AND、最新/随机生成稳定的视频队列。

### Scope

- feature:tags。
- FeedRepository、Room 查询和 FeedFilter。
- SessionShuffleQueue、FakeRandomSource。
- 空结果和筛选变更状态。

### Boundary

只展示视频元数据列表/静态 Feed 状态，不创建 ExoPlayer、不下载文件。

### Failure states

无频道、无标签、无结果、标签计数变化、视频删除、频道失效。

### Proof

- FILTER-01 至 FILTER-08。
- RAND-01 至 RAND-06。
- 标签筛选 Compose 测试。
- test、lint、assembleDebug。

## 12. 阶段 10：TDLib 范围请求与 Media3 DataSource

### Outcome

对一个 supportsStreaming=true 的视频，DataSource 能从任意 offset 读取正确字节，并在 seek/close/超时取消旧请求。

### Scope

- TelegramFileGateway、TelegramFileManager、RangeRequestCoordinator。
- TelegramMediaDataSource/Factory。
- updateFile、downloadFile offset/limit、cancelDownloadFile。
- 集中块大小/优先级/超时配置。

### Boundary

不做竖向 Feed、不预加载下一条、不为 supportsStreaming=false 完整下载。

### Failure states

未知长度、无连续前缀、网络断开、超时、文件消失、主线程误用、late callback、取消。

### Proof

- DATA-01 至 DATA-12。
- 真机从开头/中间读取区间和 seek 验证。
- 监控确认没有自动完整下载。
- test、lint、assembleDebug、installDebug。

## 13. 阶段 11：单播放器竖向 Feed

### Outcome

竖向滑动真实视频时，页面稳定后复用一个 ExoPlayer 播放当前项，其他项立即停止。

### Scope

- player 模块 VideoPlayerManager。
- feature:feed VerticalPager、PlayerView 容器和状态。
- 生命周期、音频焦点、耳机断开、静音/暂停/重播。
- 不支持流式、加载、空和错误 UI。

### Boundary

不预加载下一条，缓存先只满足当前读取，不完成设置页。

### Failure states

快速滑动、解码失败、超时、文件失效、后台、断网、supportsStreaming=false。

### Proof

- PLAYER-01 至 PLAYER-07。
- Feed Compose 状态测试。
- 真机滑动、seek、前后台、耳机和单音频人工验收。
- test、lint、assembleDebug、connectedDebugAndroidTest。

## 14. 阶段 12：下一条预加载与有界缓存

### Outcome

仅下一条在允许策略下预加载少量数据；500MB 默认 LRU 保护当前/下一条并支持一键清空。

### Scope

- VideoPreloadManager、CacheManager、CacheEntryEntity。
- owner token/pin、LRU、200MB/500MB/1GB/2GB/5GB/10GB/15GB/20GB。
- 设置页缓存占用/上限/清空和 Wi-Fi/移动数据开关。
- 存储不足处理。

### Boundary

不扩展预加载窗口，不创建第二播放器，不增加 Media3 SimpleCache。

### Failure states

快速滑动、删除失败、缓存被系统清理、空间不足、部分清理失败。

### Proof

- CACHE-01 至 CACHE-09 和 DataSource owner 测试。
- 设置页 Compose 测试。
- 真机缓存字节、上限、清理和无公共文件人工验收。
- test、lint、assembleDebug。

## 15. 阶段 13：网络、电量、热状态与内容保护

### Outcome

预加载随网络、电量、省电和热状态变化；受保护内容启用 FLAG_SECURE；原消息链接可打开或明确失败。

### Scope

- ConnectivityObserver、PowerPolicyObserver、PolicyEngine。
- API 29+ 热状态监听与 API 26–28 UNKNOWN。
- WindowSecurityController。
- getMessageProperties/getMessageLink 和 Intent 跳转。
- 设置页应用/TDLib 版本与调试开关。

### Boundary

不申请新权限、不添加通知/后台服务/分析 SDK。

### Failure states

网络切换、callback 释放、热 API 不可用、低电量、省电、链接不可生成/不可打开、保护状态快速切换。

### Proof

- POLICY-01 至 POLICY-10。
- UI-11 和消息链接测试。
- merged manifest 权限检查。
- 真机网络、后台、FLAG_SECURE 和可行的温度/省电人工验收。

## 16. 阶段 14：完整回归与 debug APK 验收

### Outcome

生成只供个人安装的 debug APK，并用自动化和真实 Telegram 清单给出诚实的第一版状态。

### Scope

- 补齐全部单元、Room、Compose 和仪器测试。
- 执行安全审计、依赖/许可证清单、缓存/性能测量。
- 执行 ACCEPTANCE_TESTS.md 真机和人工清单。
- 修复本项目范围内的真实缺陷，不删测试/功能。

### Boundary

不配置 release 签名、不发布商店、不新增第二版功能。

### Failure states

任何失败保留首个根因；无法真实验证的项目写“尚未验证”，不改成通过。

### Proof

    .\gradlew.bat test
    .\gradlew.bat lint
    .\gradlew.bat assembleDebug
    .\gradlew.bat connectedDebugAndroidTest
    .\gradlew.bat installDebug

并记录真实账号、真实频道、真实流式播放、缓存、保护、网络和长时间滑动结果。

## 17. 外部依赖计划

所有依赖只在承载首个真实用途的阶段引入，版本统一位于 libs.versions.toml。

| 依赖 | 用途 | 官方来源 |
|---|---|---|
| Android Gradle Plugin/Kotlin | Android/Kotlin 构建 | Google/JetBrains 官方发布 |
| Compose BOM、Material 3、Activity、Lifecycle、Navigation | 原生 UI、状态和导航 | Google Maven/AndroidX |
| kotlinx-coroutines | 协程和 Flow | JetBrains/Maven Central |
| Room | 元数据数据库 | AndroidX |
| DataStore | 非敏感偏好 | AndroidX |
| Media3 ExoPlayer/UI | 播放器和 DataSource | AndroidX |
| Hilt/Dagger | 依赖注入 | Google/Dagger 官方发布 |
| TDLib | Telegram 用户账号、消息和文件 | Telegram 官方 GitHub 源码 |
| JUnit/AndroidX Test/Compose UI Test | 自动化验证 | JUnit/AndroidX 官方发布 |

不引入 Retrofit/OkHttp 作为 Telegram 媒体路径，不引入分析、广告、崩溃上报、图片下载器或第二套媒体缓存，除非未来阶段有明确需求并获得批准。

## 18. Git 与提交节奏

- 每阶段开始前 git status。
- 保留用户未提交修改，不混入无关格式化。
- 每个 Proof 通过后展示变更摘要；只有用户要求才提交。
- 不自动 push。
- 推荐提交粒度为一个已通过 Proof 的阶段或该阶段内一个可独立回退的测试驱动切片。
- 禁止 reset --hard、clean -fd 和未经授权的历史重写。

## 19. 每阶段汇报模板

1. 一、本阶段目标
2. 二、实际完成内容
3. 三、修改文件
4. 四、关键架构决定
5. 五、执行的命令
6. 六、测试结果
7. 七、真机验证结果
8. 八、已知问题
9. 九、安全检查结果
10. 十、建议的下一阶段

未执行或缺少前置条件的验证必须写“尚未验证”。
