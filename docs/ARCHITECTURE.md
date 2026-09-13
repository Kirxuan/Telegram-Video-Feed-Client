# VELORA（曜流）架构设计

> 当前合同（2026-09-10）：Stage 27 在固定 ACTIVE/STANDBY 双播放器基础上把预加载预算改为**按秒**（目标 5 秒、硬上限 20MiB），低清 480p 备用与分档快速起播用于缩短移动数据首帧与真正可播放时间；不再要求先做单播放器实验。最多两实例、只有 ACTIVE 发声；移动数据显式授权贯穿策略/预算/池准入；手势开始保留精确下一目标，稳定后按账号/质量/网络/队列代次校验交接。正常目标改变清除旧媒体并复用槽位，后台和资源压力释放备用。详见 [Stage 27](STAGE27_MOBILE_FAST_START.md) 与 [方案调研](STAGE27_GITHUB_LOADING_RESEARCH.md)，下文各阶段描述保留为历史。

文档版本：2.7
日期：2026-09-10
状态：Stage 27 移动数据秒开优化；正式发行基线仍为 Stage 24。固定最多两实例，唯一下一目标以秒为预算、低清预留、手势保留与精确晋升；架构边界仍使用官方 TDLib、Media3 与唯一私有缓存。

> 阶段 5 已新增 `player`、`TelegramFileManager`、`TelegramMediaDataSource` 和单例 `VideoPlayerManager`。阶段 6 以一个 `PlayerView` 和该唯一播放器驱动 `VerticalPager`。阶段 7 在稳定当前项之外只把逻辑下一项交给 `VideoPreloadManager`，请求 256KiB、优先级 8 的 TDLib 区间；当前播放仍为优先级 32 且不存在第二播放器。阶段 8 补齐音频焦点、耳机拔出策略、解码错误分类、播放器完整释放、受保护窗口标志和系统返回路径，并把启动期文件统计及设备信号读取移到显式 I/O dispatcher。阶段 9 修正已知文件长度的 Media3 返回语义，把范围等待从固定总截止改为 15 秒无进展截止加 90 秒硬上限，并使 owner 释放立即唤醒等待线程。阶段 10 增加不持久化的 Debug 播放会话指标，将初始加载、暂停和 seek 与真实 rebuffer 分开，并用真机基线验证缓冲前瞻及释放行为。阶段 11 在稳定绑定时刷新官方消息，从 `alternativeVideos` 中选择直接 H.264 服务端版本，并让当前播放与唯一下一条预加载共享同一质量策略。`TdLibMediaCacheManager` 继续使用官方精确存储统计和 `FileTypeVideo` 删除能力，以运行时 pin、Room v4 LRU 与 DataStore 配额限制唯一 TDLib 私有媒体缓存。

## 1. 架构目标

架构优先保证五件事：

1. 官方 TDLib 与 Compose UI 完全隔离。
2. 授权、消息同步、媒体请求和播放器都有唯一生命周期所有者。
3. 视频字节只存在一份受限的 TDLib 私有缓存，不形成双缓存。
4. 快速滑动、网络变化和生命周期切换都能取消过期工作。
5. 业务策略可在没有真实 Telegram 账号和 native 库时用 Fake 测试。

## 2. 采用增量多模块

不在阶段 1 一次创建所有空模块。模块只在第一次承载真实生产类型时创建，但最终目标边界如下：

| 模块 | 职责 |
|---|---|
| app | Application、Activity、导航、Hilt 组合根和窗口安全策略 |
| core:model | 不依赖 Android/TDLib 的频道、视频、标签、筛选、授权和错误模型 |
| core:domain | Repository 接口、UseCase、队列和策略接口 |
| core:common | 调度器、时间、重试、日志脱敏和通用结果类型 |
| core:database | Room 数据库、Entity、DAO、迁移和事务 |
| core:ui | Material 3 主题、共享状态组件和简体中文资源 |
| telegram | 官方 TDLib Java/JNI 绑定、客户端生命周期、映射器和 Repository 实现 |
| player | Telegram DataSource、共享 ExoPlayer、单视频播放生命周期和唯一下一条预加载 |
| feature:auth | 启动与授权 UI/ViewModel |
| feature:channels | 频道搜索、多选和扫描状态 |
| feature:tags | 标签计数和 OR/AND 筛选 |
| feature:feed | 竖向信息流和播放交互 |
| feature:settings | 缓存、网络、播放设置和退出账号 |

当前实现的模块为 `core:model`、`core:domain`、`core:database`、`telegram:tdlib`、`telegram` 和 `app`；授权与频道 UI 暂位于 `app`。其余表列模块保持未来边界，尚未创建或尚未承载对应业务类型。禁止为了“看起来完整”生成没有生产类型和测试价值的空 feature 模块。

## 3. 依赖方向

    Compose UI
        ↓
    ViewModel
        ↓
    UseCase / core:domain Repository interface
        ↓
    Repository implementation
        ↓
    telegram / core:database / player infrastructure
        ↓
    official TDLib / Room / Media3

规则：

- feature 模块只依赖 core:model、core:domain、core:ui 和必要的稳定控制接口。
- telegram 依赖 core:model、core:domain、core:common 和 core:database，并实现领域接口。
- player 依赖 core:model、core:domain 和 core:common，通过 TelegramFileGateway 接口取媒体，不依赖 TDLib 类型。
- app 负责 Hilt 绑定实现；领域层不知道实现类。
- core 模块不得反向依赖 feature、telegram 或 player。

## 4. 依赖注入决定

第一版使用 Hilt：

- TelegramClientManager、数据库、Repository 实现、VideoPlayerManager、CacheManager 和策略观察器为应用级单例或清晰限定的作用域。
- Hilt 创建 TelegramClientManager 对象不等于立即创建 TDLib 客户端；只有凭证检查通过且启动 UseCase 请求后才初始化 native 客户端。
- TelegramMediaDataSource 由 Factory 按 Media3 加载请求创建，不是单例。
- ViewModel 使用构造函数注入接口。

目前没有证据表明 Hilt 与 TDLib 初始化冲突，因此不采用手动服务定位器。若以后出现冲突，必须先提供最小复现、测试和架构决定，再考虑手动构造函数注入。

## 5. 线程与状态模型

- TDLib 回调进入专用串行 CoroutineDispatcher 或 actor，保证客户端状态和请求关联顺序。
- TDLib 回调先映射为应用模型，再写入 StateFlow/SharedFlow；回调线程不直接更新 Compose。
- Room 和文件 I/O 在 IO dispatcher。
- 标签、队列洗牌等纯计算在 Default dispatcher，数据量小时可由调用协程执行。
- ViewModel 在 viewModelScope 收集领域 Flow，并输出不可变 ScreenState。
- Media3 DataSource 的 open/read 是同步接口，只允许在 Media3 Loader 线程等待；检测到主线程调用时立即失败。
- 所有 suspend 路径保留 CancellationException，不使用吞掉取消的 broad catch。

一次性导航、Snackbar 和打开外部链接使用不重放的 Effect 通道；可恢复的加载、空、内容和错误属于 ScreenState。

## 6. 核心组件

### 6.1 TelegramClientManager

阶段 2 已实现一个应用级 manager 与专用串行 dispatcher：它持有官方 TDLib client 的生命周期、将 callback 映射为不可变领域 `StateFlow`，且不允许 callback 直接操作 Compose。该实现的来源、版本和 native 边界见 `docs/TDLIB_BUILD.md` 与 `telegram/tdlib/TDLIB_PROVENANCE.md`。

职责：

- 加载固定版本的官方 TDLib native 库和 Java 绑定。
- 创建、关闭唯一 TDLib Client。
- 关联请求 ID、响应和错误。
- 接收 updateAuthorizationState、updateFile、消息和聊天更新。
- 将原始 TDLib 类型映射为内部事件或交给 mapper。
- logout 后等待 closed 状态再释放资源。

不负责 UI、Room 查询、筛选或播放器控制。

### 6.2 TelegramAuthRepository

阶段 2 已实现 `TdLibTelegramAuthRepository`，Stage 24 在同一接口新增 `configureCredentials(apiId, apiHash)`；它经 Hilt 注入给 `AuthViewModel`，UI 只调用领域接口。当前覆盖凭证格式/存储、TDLib 参数、手机号、验证码、密码、Ready、LoggingOut、Closing/Closed、未知授权步骤和脱敏失败（包括 FLOOD_WAIT）。这说明主机代码路径存在，不构成任何真实账号登录、会话恢复或退出成功的声明。

领域接口提供：

- authState: StateFlow<AuthState>
- start()
- submitPhoneNumber(phoneNumber)
- submitCode(code)
- submitPassword(password)
- logout()

AuthState 是封闭集合：UnconfiguredCredentials、Initializing、WaitingPhoneNumber、WaitingCode、WaitingPassword、Authorized、LoggingOut、Closed、Error。错误保存脱敏类别和中文消息，不暴露 TDLib 对象。

TDLib 首个状态 authorizationStateWaitTdlibParameters 触发私有目录参数设置。非 debug BuildConfig 凭证恒为空；`SecureTelegramCredentialsProvider` 优先读取 `noBackupFilesDir/credentials/telegram-api.v1` 的 AES-GCM 密文，debug 在密文不存在时才回退 `local.properties` 注入值。Keystore 密钥不可导出，密文带随机 IV、AAD 和版本边界。凭证、数据库目录和本地密钥只在数据层构造；API Hash 提交后从 UI 状态清除，code/password 的既有清除规则不变。

若 TDLib 拒绝一组格式正确的参数，Repository 返回带脱敏失败的 `UnconfiguredCredentials`。使用者保存新参数后，唯一 `TelegramClientManager` 向旧 Client 发送 `Close`，等待其 `AuthorizationStateClosed`，清空旧 generation，再从安全存储读取新参数并创建唯一新 Client；不会并行保留两个可用 Client。

### 6.3 TelegramChatRepository

阶段 3 已实现 `TdLibTelegramChatRepository`，经 Hilt 以领域接口注入 `ChannelSelectionViewModel`：

- 对 TDLib 主列表和归档列表分别调用 `loadChats(chatList, limit)`，以 update-driven 方式加载并在 TDLib 返回 404 时结束分页，再调用 `getChats`/`getChat` 获取当前可见聊天。
- 同时要求 `ChatTypeSupergroup.isChannel=true` 和对应 `Supergroup.isChannel=true`；任一类型未知、矛盾或查询失败均不放宽为频道。
- 只接受 `Member`、`Administrator` 和 `Creator(isMember=true)`；排除 `Left`、`Banned`、`Restricted`、`Creator(isMember=false)` 与未知状态。
- 完整刷新成功后在 Room 事务中对账；分页/网络/超时失败时保留已有缓存并上报脱敏错误，不把未完成结果当作“用户退出”。
- 处理 `UpdateNewChat`、`UpdateChatTitle` 和 `UpdateSupergroup`；标题/username 更新保留选择，失去访问权限立即标记不可用并取消选择。
- 授权退出事件清空单账号频道索引，并用 generation 忽略旧刷新结果。
- 本仓库的消息索引路径调用频道内普通视频过滤搜索 API，但不调用文件下载 API，不请求视频字节。

### 6.4 TelegramMessageRepository

Stage 23 的 `TdLibTelegramMessageRepository` 经 Hilt 以领域接口注入 `ChannelSelectionViewModel`。它是唯一前台扫描协调器，并通过 `MessageIndexStore` 适配 Room，不向领域/UI 暴露 TDLib 类型：

- 初始/恢复扫描：`SearchChatMessages(chatId, null, "", null, cursor, 0, 100, SearchMessagesFilterVideo())`；完成只看 TDLib 返回的 `nextFromMessageId == 0`。
- 增量同步：实时处理更新；前台恢复时先从过滤搜索最新页向旧对账，直到越过 `lastNewMessageId`，随后从独立 `videoSearchCursor` 公平回填历史。
- recent 对账的请求游标只有与当前持久 `videoSearchCursor` 精确相等时，才让该页同步推进历史游标；因此首次/迁移重扫不会在 recent 结束后重放已提交页，而持有不同恢复位置的频道不会被新的 recent 游标覆盖。
- 只映射 messageVideo。
- 页面内复合键去重后，以一次 existing-key 查询和批量 videos/tags/cross-ref 操作写入；在同一事务更新候选数、页数、next cursor、最近位置和状态。
- 内容编辑时替换 caption 和该视频全部标签关联。
- 删除时置 isDeleted=true、删除标签关联，并从队列 Flow 排除。
- 权限错误将频道标记不可用并停止该频道任务。
- 用户暂停状态、FLOOD_WAIT 截止时间、视频结果页数、候选数、异常计数和两个不同语义的位置均持久化；退到后台后取消请求并标记为非用户暂停。
- 同频道串行；跨频道最多 2 个 worker，近期首轮优先且每轮每频道一页，轮次起点旋转。网络错误采用可取消的指数退避与抖动，按频道最多 3 次；FLOOD_WAIT 在共享扫描闸门中阻断所有新分页请求。
- 非零 next cursor 没有严格向更老 message id 推进时，页面数据和 `PAGINATION_STALLED` 原子提交，但保留上一个有效 cursor 并停止循环。
- 频道页折叠摘要分两行显示处理视频数与唯一索引数；展开详情用独立统计块显示已处理视频、搜索页数、唯一索引和完整频道。单频道行把状态/处理量与页数/索引量拆开，近似总数始终单独标成仅供参考。
- 播放刷新读取 `messageVideo.alternativeVideos` 中的直接文件，但只把原始消息视频元数据写入 Room；候选版本只存在于当前内存模型，TDLib 类型不会跨越 `telegram` 边界。

### 6.5 TelegramFileManager

通过 core:domain 中的 TelegramFileGateway 暴露应用自有类型：

- acquireRange(fileId, offset, length, priority, ownerToken)
- observeFile(fileId)
- release(ownerToken)
- deleteCachedFile(fileId)

内部每个 fileId 有一个 RangeRequestCoordinator。所有者分为 CURRENT_PLAYBACK、NEXT_PRELOAD 和显式重试。Coordinator 合并仍需要的范围、跟踪 generation，并忽略过期回调。

只有最后一个所有者释放且仍在下载时，才调用 cancelDownloadFile。不得取消仍由当前播放持有的请求。

### 6.6 TelegramMediaDataSource

DataSource 使用内部 URI 方案标识 fileId，例如 telegram-file://file/123；该 URI 只是一种应用内标识，不是网络地址，也不得外传。

open(DataSpec) 流程：

1. 验证调用不在主线程并解析 fileId。
2. 读取 DataSpec.position 作为绝对起始 offset。
3. DataSpec.length 已知时取请求剩余长度与集中配置块大小的较小值；未知时请求一个固定块。首段只等待 256 KiB 连续可读窗口，当前播放的 TDLib 请求可继续有界前读到最多 4 MiB；这是受限当前播放请求，不是完整下载或总缓存配额。
4. 用唯一 ownerToken 调用 TelegramFileGateway.acquireRange。
5. 等待 updateFile 表明 local.path 非空，且 downloadOffset 到 downloadOffset + downloadedPrefixSize 覆盖所需区间。相关连续字节每次增长都会刷新 15 秒无进展窗口；无进展时超时，即使持续进展也不超过 90 秒硬上限。
6. 用只读 FileChannel/RandomAccessFile 定位绝对 offset。
7. DataSpec.length 已知时返回该请求长度；否则在 file.size 已知时返回从 DataSpec.position 起的剩余长度，只有总大小仍未知时才返回 Media3 LENGTH_UNSET。

read 流程：

- 只读取已确认连续可用的字节。
- 到达当前连续区间末端但未到媒体末尾时，请求下一个相邻块并等待有界超时。
- 顺序换段时先取得下一段 owner，再释放当前 owner，避免在区间边界取消仍有用的有界前读；seek 会关闭旧 DataSource/owner，创建新 generation 并从新 DataSpec.position 请求。
- 文件被清理、网络断开、TDLib 错误或超时映射为明确 IOException 子类，供 LoadErrorHandlingPolicy 做有限重试。

close 必须幂等：关闭文件句柄、释放 ownerToken，并通过 owner 表通知立即唤醒正在等待的 Loader；旧 generation 随后到达的更新必须被忽略。Debug 范围指标只允许包含 fileId、offset/length、等待毫秒、连续进度字节和有效 KiB/s，不得包含私有路径、消息正文或凭证。

### 6.7 VideoPlayerManager

- 应用级只维护一个主要 ExoPlayer。
- 通过 PlayerBindingGeneration 接受 bind(videoKey)、play、pause、retry、mute 和 release。
- 新 bind 原子地停止旧媒体、释放旧 DataSource 所有权并设置新 MediaSource。
- bind 前由 ViewModel 根据 DataStore 偏好和网络类型选择 `playbackFileId`：AUTO 在 Wi-Fi 取最高不超过 720p 的安全版本，在移动数据/OTHER/OFFLINE 取省流版本；也可固定省流、720p 或原画。
- 第一版只选择 Telegram 提供的直接 H.264 文件。刷新失败、超过 3 秒、候选无效或无更低成本候选时使用索引原画；不做本地转码、HLS 或播放中无缝切换。
- 使用 ProgressiveMediaSource 和 TelegramMediaDataSource.Factory。
- 由播放器管理器以 250ms 节奏采样 position、duration、bufferedPosition 和 seekable 状态，作为不可变快照交给 ViewModel；`seekTo` 在该边界内校验时长和 seekable 后调用 ExoPlayer。暂停、切页、释放和播放错误会停止采样任务。
- LoadControl 优先按播放时间维持 50–60 秒缓冲；rebuffer 后的恢复阈值固定为 12 秒，以避免高码率视频反复短暂恢复。首次开始阈值不再固定为 2.5 秒：`conditionalStartupMillis` 按观测吞吐余量分档（低清 800ms / 1200ms，高清晰度 1800ms，未知或弱网 2500ms），未满足条件时自动退回保守值。
- Debug 会在播放器状态变化和每 5 秒采样时记录 position、bufferedPosition、前瞻毫秒、rebuffer 次数及累计时长，并在释放绑定时输出汇总。只有首次 READY 后、仍期望自动播放且不是显式 seek 的 BUFFERING 才计为 rebuffer；指标不保存视频元数据，Release 不输出详细日志。
- 以 Media3 `AudioAttributes` 配置媒体/电影内容和自动音频焦点处理，并启用 `handleAudioBecomingNoisy`。
- `ProgressiveMediaSource` 使用最多 3 次的显式加载重试；解码器初始化、查询、解码和格式类错误统一映射为 `DECODER_UNSUPPORTED`，网络超时映射为 `TIMEOUT`。
- 应用进入后台暂停；恢复策略从 DataStore 读取。
- 播放器错误转换为加载失败、网络、超时、不支持编码、文件失效或未知错误。
- 离开信息流或 ViewModel 清理时调用完整 `release()`：停止采样、释放 DataSource/owner、清空媒体项、移除回调并释放唯一 ExoPlayer。

Compose 页面只提供 AndroidView/PlayerView 的可视容器和用户意图（点按暂停/继续、中央继续播放提示、进度线拖动），不拥有播放器。

### 6.8 VideoPreloadManager

- 输入是稳定当前项及其下一条。
- 当前项由播放器拥有，预加载器只持有 NEXT_PRELOAD 所有权。
- 仅请求 256KiB、TDLib 优先级 8 的首段，不创建第二个长期 ExoPlayer。
- 下一条先通过可取消消息刷新和同一 `VideoQualitySelector` 得到最终 fileId，再申请 256KiB；不会先预加载原画后播放省流版本。
- 频道/标签/顺序变化或快速滑动时 generation 增加并释放旧 owner。
- Wi-Fi 默认允许；移动数据默认关闭且可显式开启。无网络、省电、低存储及 MODERATE 以上热状态立即释放预加载 owner。

### 6.9 FeedRepository

- Room Flow 是队列元数据的事实来源。
- 频道筛选用所选 chatId 的 IN/EXISTS。
- 标签 OR 用 EXISTS + IN。
- 标签 AND 用关联表分组并要求 COUNT(DISTINCT normalizedTagName) 等于所选标签数。
- 频道条件与标签条件永远 AND。
- 排除 isDeleted、不可访问频道和 supportsStreaming=false 的视频不会从列表完全隐藏；后者保留为可滑过的“不支持流式”卡片，但不交给播放器。
- 最新模式排序稳定。
- 随机模式由 SessionShuffleQueue 管理未播放集合；一轮完成后洗牌并避免边界立即重复。

### 6.10 CacheManager

- 使用官方 `GetStorageStatistics` 精确汇总 `FileTypeVideo`；TDLib 尚未 Ready 时只用 `stat.st_blocks × 512` 扫描私有根目录作为启动估算。该回退扫描由应用范围协程在显式 I/O dispatcher 执行，不阻塞 `Application.onCreate()`。
- 不启用 Media3 SimpleCache 或复制完整文件。
- LRU 元数据记录 fileId、最近访问和已缓存字节；可删除性始终由删除当刻的运行时 owner/pin 决定。
- CURRENT_PLAYBACK 与 NEXT_PRELOAD 是运行时 pin，不持久化为永久保护。
- 默认上限 500MB，可选 200MB、500MB、1GB、2GB、5GB、10GB、15GB、20GB；达到上限时按最旧未 pin 项调用官方 `deleteFile`。只有不存在任何 pin 时才允许 `OptimizeStorage(FileTypeVideo)` 处理启动前遗留文件。
- 一键清空先停止预加载、暂停播放器并关闭 DataSource，然后删除全部可删媒体文件。
- Room 频道/标签/播放历史和 TDLib 授权会话不属于“一键清空媒体缓存”。

## 7. 数据模型

Room Stage 23 已迁移到版本 5 schema。`media_cache_entries` 仍只保存 `fileId`、已缓存字节快照和最近访问时间；4→5 只为频道增加扫描策略、过滤游标和真实统计，不删除既有频道/视频/标签/缓存记录。媒体字节、运行时 pin、路径与设置不进入 Room。

### 7.1 ChannelEntity

- chatId: Long，主键
- title: String
- username: String?，频道可能没有公开 username
- selected: Boolean；频道选择的唯一持久事实来源
- lastNewMessageId: Long?，最近增量边界
- oldestScannedMessageId: Long?，历史页最旧边界
- initialScanCompleted: Boolean
- scanStrategyVersion: Int，当前过滤扫描策略为 2
- videoSearchCursor: Long，TDLib 过滤搜索专用 next cursor，0 只在初始或完成时出现
- videoSearchCompleted: Boolean，独立于 cursor 表达完成
- videoCandidateCount / videoSearchPageCount：真实过滤候选与结果页统计
- approximateVideoCount: Int?，仅用于带“约”的 UI 观测，不能控制完成
- lastSyncTime: Long?
- accessState: 可用/失效/未知
- scanState: 未开始/扫描中/暂停/完成/错误

`ChannelDao.observeAvailableChannels()` 只返回 `accessState=AVAILABLE`；元数据 upsert 保留选择和未来扫描游标，成功全量对账将缺失频道改为不可用并清除其选择。选择替换在单个 Room 事务中完成，因此杀进程/重启后可恢复。当前没有 `accountId`；TDLib 登出时清空整张频道表，避免跨账号串数据。

### 7.2 VideoEntity

复合主键：chatId + messageId。

- fileId: Int
- remoteUniqueId: String
- caption: String
- durationSeconds: Int
- width/height: Int
- fileSize: Long?，TDLib 可能尚未知
- supportsStreaming: Boolean
- publishTime: Long
- editTime: Long?
- canBeSaved: Boolean
- isDeleted: Boolean
- indexedAt: Long

remoteUniqueId 用于诊断文件替换，不作为消息主键。

### 7.3 TagEntity

- normalizedName: String，主键，不含前导 #
- canonicalDisplayName: String，首次观察到的显示形式

### 7.4 VideoTagCrossRef

复合主键：chatId + messageId + normalizedTagName。

- displayName: String，保留该消息中的原始标签形式

相较初始建议，本表增加 displayName，因为单个 TagEntity 无法同时保留 #Kotlin 和 #kotlin 在不同消息中的原始显示文本。

### 7.5 PlayHistoryEntity

复合主键：chatId + messageId。

- lastPlayedAt: Long
- completed: Boolean
- progressMs: Long

### 7.6 CacheEntryEntity

- fileId: Int，主键
- cachedBytes: Long
- lastAccessedAt: Long

不保存媒体字节、绝对公共路径、密钥、可删除布尔值或完整 TDLib JSON。可删除性由删除当刻的运行时 pin 决定；pin 位于内存所有权表，防止进程重启后形成永久不可清理项。

### 7.7 相对初始建议的表结构调整

- ChannelEntity 增加 accessState 和 scanState：分别表达频道权限丢失与分页任务状态，避免 UI 从异常字符串猜测状态。
- ChannelEntity.username 允许 null：私有频道可能没有公开 username。
- VideoEntity 增加 editTime：用于识别编辑更新并使事务幂等。
- VideoEntity.fileSize 允许 null：TDLib 在索引时可能尚未给出确定大小。
- VideoTagCrossRef 增加 displayName：保留每条消息实际出现的标签大小写/字符形式；TagEntity 只保存聚合显示形式不足以满足该要求。
- 新增 CacheEntryEntity：LRU 需要可测试、可恢复的最近访问和缓存字节元数据；表中仍不存媒体字节。
- 不增加 accountId：第一版只有一个活动账号，退出时清除账号相关 Room 数据。这样避免伪多账号分区和旧账号串数据。

## 8. 视频过滤搜索游标算法

TDLib `SearchChatMessages` 的 limit 最大 100，允许短页甚至空页继续返回非零 next cursor；`FoundChatMessages.totalCount` 是近似值。

1. 首次使用 `fromMessageId=0`、`offset=0`、空 query 和 `SearchMessagesFilterVideo`。
2. 映射后仍只接受普通、非 secret 的 `messageVideo`；候选页不触发媒体下载。
3. 在一个 Room 事务中批量写视频/标签/关联，并提交候选数、页数、`lastNewMessageId`、`videoSearchCursor=nextFromMessageId` 和状态。
4. 只有 `nextFromMessageId == 0` 才把 `videoSearchCompleted`/兼容字段 `initialScanCompleted` 设为 true。短页、空页和近似总数均不是完成条件。
5. 非零 cursor 必须严格下降；相同或反向 next 触发可恢复的分页停滞错误，保留最后有效 cursor，禁止无限循环。
6. 每页独立事务。事务失败不会推进 cursor；重复页和边界消息依靠 `(chatId,messageId)` 复合主键与批量 upsert 幂等吸收。

4→5 迁移不复用旧 `oldestScannedMessageId`：旧完整频道保留完成事实；旧未完成频道从过滤 cursor 0 安全重扫并保留已存在索引。

## 9. 增量同步算法

- 在线前台时实时消费 updateNewMessage 并 upsert。
- 编辑更新重新读取/映射 messageVideo 内容，原子替换标签。
- 删除更新软删除视频并清理交叉引用。
- 进程恢复后先从最新过滤搜索页向旧对账，直到遇到 `lastNewMessageId` 或更旧边界，再从持久 `videoSearchCursor` 继续完整回填。
- 每页提交后更新 lastNewMessageId 为该频道已成功处理的最大消息 ID。
- 若边界消息已删除，继续到首次小于保存边界的 ID，再结束对账。
- 频道权限错误停止对账并更新 accessState。

## 10. 标签解析

实体路径：

1. 遍历 formattedText.entities，仅选择 textEntityTypeHashtag。
2. 按 TDLib 定义的 UTF-16 offset/length 从原文安全切片，越界实体记脱敏诊断并跳过。
3. 保存原始 displayName。
4. 去前导 #，Unicode NFKC，Locale.ROOT lowercase，生成 normalizedName。
5. 拒绝空键并按 normalizedName 去重。

回退路径只在实体列表不含 hashtag 时运行。扫描器要求 # 位于文本开始或非字母/数字/下划线边界，标签体由 Unicode 字母、数字、组合标记或下划线组成。回退行为用中文、英文、数字、标点、emoji、孤立井号和错误嵌入用例测试。

## 11. 授权状态转换

    未配置凭证 → UnconfiguredCredentials
    WaitTdlibParameters → Initializing → 设置参数
    WaitPhoneNumber → WaitingPhoneNumber
    WaitCode → WaitingCode
    WaitPassword → WaitingPassword
    Ready → Authorized
    LoggingOut → LoggingOut
    Closing/Closed → Closed
    TDLib error → 对应当前步骤的 Error，可返回上一个可输入状态

验证码/密码错误不销毁 TDLib 客户端，由状态机继续等待正确输入。数据库打开失败进入不可继续的初始化错误，提供重试或清除本账号本地会话的显式操作，不自动删除数据。

## 12. UI 状态与导航

- 启动路由只能由凭证状态和 AuthState 决定。
- 每页使用 Loading、Empty、Content、RecoverableError；授权另有 SignedOut/Authorized。
- 频道保存选择后开始扫描；近期页可用后允许进入 Feed。
- 标签筛选和设置以单一数据源更新 FeedFilter。
- 信息流通过 Compose `BackHandler` 复用顶部返回的 `onBack` 路径；全屏时第一次系统返回只退出全屏，非全屏时返回频道选择并触发页面资源释放。登录必需步骤不通过伪返回跳过。
- 受保护视频的 FLAG_SECURE 由 app 层 WindowSecurityController 根据当前 VideoKey 状态设置和恢复。

## 13. 网络、电量和热状态策略

ConnectivityObserver 注册一个 registerDefaultNetworkCallback，并在释放时 unregister。使用回调携带的 NetworkCapabilities 判断 Wi-Fi/移动数据和 validated 状态，不按秒轮询。

Android 策略观察器组合：

- PowerManager.isPowerSaveMode。
- ACTION_BATTERY_LOW/OKAY 或 BatteryManager 可用状态。
- API 29+ PowerManager.addThermalStatusListener。

`VideoPreloadPolicy` 输出允许与阻断原因。MODERATE 及以上停止下一条预加载；当前视频不受预加载策略取消。API 26–28 热状态为 UNKNOWN，不假造 NONE。系统低存储广播/可用空间阈值会先停止预加载，再触发未 pin 视频清理。

## 14. 重试和错误模型

领域错误按类别封闭建模：Auth、NetworkOffline、FloodWait(retryAt)、AccessLost、MessageMissing、FileInvalid、StreamingUnsupported、Timeout、DecoderUnsupported、StorageLow、Database、TdLibInitialization、LinkUnavailable、Unknown。

- 认证输入错误等待用户操作，不自动重复提交。
- FLOOD_WAIT 由 retryAt 门控，时间到前禁用请求按钮。
- 元数据和文件请求采用有限次数指数退避并加抖动。
- Room 事务失败不会推进游标。
- 播放 seek/滑动 generation 变更立即使旧重试失效。
- 用户可见文案不包含原始异常或敏感字段；调试日志保存脱敏错误类别和关联 ID。

## 15. 账号退出与数据边界

退出顺序：

1. 阻止新扫描、播放和预加载请求。
2. 暂停并释放播放器/DataSource 所有权。
3. 调用 TDLib logout 并等待授权状态关闭流程。
4. 清除短期授权输入。
5. 删除该账号 Room 索引、TDLib 数据库/会话和媒体缓存。
6. 保留应用级非敏感设置并返回登录页。

由于第一版不并行支持多账号，选择退出时清除账号元数据比引入 accountId 分区更简单且更安全。

## 16. 构建、版本和 native 边界

- 所有 Maven/Gradle 插件版本集中到 gradle/libs.versions.toml。
- 只选择官方稳定发布；选择依据写入阶段 1 变更摘要。
- 本机 compileSdk 候选为 release(36) + minorApiLevel=1；Android 官方要求该 SDK 使用 AGP 8.13.0 或更高版本。阶段 1 必须从满足下限的稳定 AGP 中选型，并以 targetSdk=36 实际同步验证。
- TDLib 是独立 `telegram:tdlib` 模块/目录，只从 `https://github.com/tdlib/td.git` 获取；当前固定 `1.8.66` / `022d60202e446ad1287b9fb68e687c8a0760788b`。
- OpenSSL 固定 `3.5.7 LTS`，archive SHA-256 为 `a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8`；NDK `23.2.8568313`、CMake `3.22.1`、Android API `26`、`c++_static` 和唯一 ABI `arm64-v8a`。
- 可复现入口是 `tools/tdlib/build-android.ps1` 与 `tools/tdlib/build-android-arm64.sh`，外部构建根为 `E:\tdlib-build\channel-video-flow`。精确命令、哈希、许可证和 ELF/APK 检查位于 `docs/TDLIB_BUILD.md`。
- 不提交来源不明 `.so`；当前入库 `libtdjni.so` 的 SHA-256 必须与 provenance 一致。debug APK 的 `lib/` 只能有 `arm64-v8a`，且仅允许三个已追溯文件：固定 TDLib 的 `libtdjni.so`、Compose `ui-graphics 1.11.4` 传递的官方 `androidx.graphics:graphics-path:1.0.1` 的 `libandroidx.graphics.path.so`，以及官方稳定 `androidx.datastore:datastore-core-android:1.2.1` 的 `libdatastore_shared_counter.so`。不允许其他 ABI、未知 native 文件或 `libc++_shared.so`。

## 17. 测试接缝

- FakeTelegramClientManager 驱动授权和更新序列。
- FakeTelegramFileGateway 精确控制范围可用、超时和取消。
- FakeClock/RandomSource 使 FLOOD_WAIT、退避和随机队列确定化。
- 内存 Room 测试联合主键、DAO 筛选、事务游标和迁移。
- FakeNetworkPowerThermalSource 测试策略矩阵。
- FakePlayerFacade 测试快速滑动只保留最新 generation。
- DataSource 使用临时私有文件和受控更新测试 offset、seek、unknown length、close/cancel。

详细用例见 ACCEPTANCE_TESTS.md。

## 18. Stage 18 HLS、ABR 与 duration-first 预热

- `TdLibTelegramMessageRepository` 把 `AlternativeVideo.video` 与 `AlternativeVideo.hlsFile` 映射为应用自有描述符；`TdApi.*`、manifest 和临时 fileId/token 不越过 telegram 边界，也不进入 Room。
- HLS 使用与项目相同的稳定 Media3 1.10.1 `HlsMediaSource`。严格 parser 只把 Telegram 内部资源重写为绑定账号 generation 的短期 opaque URI；所有 segment/MAP/byterange 最终仍由 `TelegramFileManager` 执行 TDLib offset/limit。
- `StreamingNetworkMetricsEstimator` 只接收 active TDLib 新网络字节，排除缓存和本地读取；fast/slow EWMA、TTFB 分位数和 network generation 输入纯 Kotlin `PlaybackRiskController`，后者约束同一播放器的 Media3 adaptive tracks。
- 下一条仍只有一个。`NextPreloadBudgetController` 以 current reservoir 决定 0/metadata/2/5/10/20 MiB tier，以**目标秒数（5 秒）**和峰值码率/segment boundary 得到实际字节目标；512 KiB chunk 后重新评估。Stage 25 曾把未缓存请求预算限制到 10 MiB，Stage 27 改为 20 MiB 硬上限配合秒级目标；请求字节不是传输层实际网络字节。
- Media3 `DefaultPreloadManager`/`PreloadMediaSource` 只能在相同 builder 创建的唯一 ExoPlayer 上交接，且 gateway/owner gate 不能绕过 duration/bytes 上限。manager 只管理正式 current 与唯一 next；8～15 秒档只到 track selection 且 payload cap=0，交接为 current 后同一 request session 才解除 preload cap。该 SampleQueue 层有独立 flag，真实 A/B 通过前默认关闭。
- HLS 解析/读取/解码失败在同一 ExoPlayer 上单次回退 direct MP4；该 Stage 18 默认路径不创建播放器池、HTTP proxy、SimpleCache 或第二媒体缓存；Stage 25 的独立双池候选见下文。

Feature flags：`cvfTelegramHlsEnabled=true`、`cvfHybridAbrEnabled=true`、`cvfDynamicNextPreloadEnabled=true`、`cvfSampleQueuePreloadEnabled=false`。

## 19. Stage 19 UI、Motion 与窗口边界

- UI 继续位于 app 层并只消费 ViewModel/领域状态；共享 Motion Tokens 和表面状态集中在既有主题与 `GlossComponents`，不创建第二套组件体系。
- Activity 仍只有一个 `enableEdgeToEdge()` 入口。页面 `Scaffold` 使用空 content insets，TopBar、内容、底部操作和 IME 按所有权各处理一次，避免同一 inset 在根、Scaffold 和子组件重复消费。
- 非视频背景只有 `drawWithCache` 的静态低透明度渐变；视频页不使用氛围背景、全屏 blur 或持续动画。有限动画不参与播放器 key、MediaSource 或 Pager settle 状态。
- 枚举导航只在非根页面使用 `BackHandler`；Channels/Login 根路由交给系统 back-to-home，以保留预测返回。没有为动画引入 Navigation Compose 或旧返回 API。
- 视频沉浸式栏由生命周期感知 effect 复用既有控制器；内容可绘制到系统栏之后，交互层使用 safe content/safe drawing，退出、后台或销毁时恢复系统栏和方向。
- API 36 未复现必须使用 exclusion 的边缘冲突，因此不设置 `systemGestureExclusion`。

## 20. Stage 25 工作树候选（2026-09-09）

正式版本仍为 Stage 24 / 1.1.0；当前工作树结果见 `STAGE25_OPTIMIZATION_RESULTS.md`。

- Feed 通过 `PlaybackFeedSession` 保持复合键和随机轮次；Room 输出轻量键，窗口内才水合完整视频/标签。排序已正确时只做线性检查。仍保留 O(N) 键与两轮随机顺序，不声称已经实现 O(1) 全局 Feed。
- Repository 的 `RepositoryObservation` 区分空结果与数据库失败，失败保留最近内容；最多三次自动订阅尝试，取消直接传播，UI 显式重试重新订阅。
- `PlaybackTargetCoordinator` 管理 pager 意图；`PlaybackPreparationContext` 绑定账号、质量、网络、队列和轮次，传入播放器准备与最终绑定。
- 单播放器仍为默认。`cvfPlaybackPoolCandidate=C1|C2` 接入真实 `ReusablePlayerLifecycle`，最多两个 ExoPlayer；STANDBY 只有一个目标，静音、关闭音频焦点、`playWhenReady=false`。旧 ACTIVE 先暂停/撤销焦点/清空，再交接新 ACTIVE。
- C1 不附加持久输出；C2 使用 `StandbyVideoSurface` + Media3 `EGLSurfaceTexture` 消费离屏帧。只对可保存内容使用 C2，保护内容走 C1；EGL 创建失败退至 C1。后台和释放关闭 EGL 线程与备用引擎。
- C1/C2 使用和单播放器相同的 HLS/MP4 选择与回退，不通过改变表示层制造对比收益。预加载读取仍经过同一个 TDLib Gateway；取消不返还目标生命周期内的未缓存请求预算。
- ACTIVE 首帧由 `AnalyticsListener` 校验回调的实际输出为当前 `PlayerView` 的可见 `SurfaceView`，离屏输出和旧代次回调不计入。该指标是显示 Surface 的渲染回调代理，合成器最终呈现仍需设备帧时间/视觉复核。
- `benchmark` 保留脱敏播放诊断以便四种候选可比较；生产 `release` 编译关闭。SampleQueue 和双池构建时互斥；Owner Promotion 保持关闭。
- 缓存元数据按 fileId 合并，最多 256 个待写键及有界删除集合，批量 Room 事务、三次有限重试；退出清理失败后阻止新账号写入。SQLite 写入只使用 API 26 可用的 INSERT/UPDATE。
- 横屏视频的全屏入口沿用 `LandscapeFullscreenPrompt`：按实际视口约束计算 fit 后的视频高度，按钮 y 坐标不超过预留底部信息区后的上限。回归用例检查按钮完整边界位于 Pager 内、实测像素高度达到当前 density 下的 48dp，并以触摸事件验证进入和退出；不改变播放器、显示 Surface 或方向所有权。

## 21. 官方依据

Stage 27 追加策略（2026-09-10）：AUTO 的 NEXT 计划优先冻结真实 480p 版本（短边上限 480，缺失时退结合法省流源），滑动晋升沿用该计划；显式原画/720p 设置仍受尊重。池预算改为**秒优先**：目标是备够 5 秒，字节数由峰值码率推导（含 1.25 峰值余量），硬上限 20MiB，取消不退款。移动数据下当前缓冲达到 3 秒且缓冲未下降即允许开始准备；池安全层不再单独重复 8 秒门槛，改为直接消费预算决策，避免同一事实出现两个阈值。备用样本与请求上限同为 20MiB，ACTIVE 样本上限仍为 32MiB，备用以实际缓冲 5 秒停止加载。首次起播门槛按吞吐余量分档：低清（短边≤480）且快慢吞吐较低值≥2.0×平均码率且首字节 P90≤350ms 用 800ms；同条件余量≥1.6×且首字节 P90≤500ms 用 1200ms；更高清晰度仅在余量≥2.5×且首字节 P90≤500ms 时用 1800ms；未知或弱网保留 2500ms，重缓冲恢复门槛不降。此条覆盖历史 Stage 25/26 的 2MiB/10MiB 与 3 秒说明；原理与来源见 STAGE27_GITHUB_LOADING_RESEARCH.md，验证见 STAGE27_MOBILE_FAST_START.md。

- [Android build 配置与 minorApiLevel](https://developer.android.com/build)
- [Android 16 QPR2 SDK 36.1 设置与 AGP 下限](https://developer.android.com/about/versions/16/qpr2/setup-sdk)
- [Media3 自定义 DataSource/MediaSource](https://developer.android.com/media/media3/exoplayer/customization)
- [TDLib 授权流程](https://core.telegram.org/tdlib/getting-started)
- [TDLib getChatHistory](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_chat_history.html)
- [TDLib downloadFile](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1download_file.html)
- [TDLib localFile 连续可读区间](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1local_file.html)
- [TDLib cancelDownloadFile](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1cancel_download_file.html)
- [TDLib getMessageLink](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_message_link.html)
