# VELORA（曜流）全局优化提案

文档版本：草案 v1（未改任何代码）
日期：2026-09-04
作者：Claude（Opus 4.8）
状态：**等待仓库所有者审阅与批准，批准前不修改代码**

---

## 0. 审查范围与总体结论

### 0.1 审查范围

本提案基于对以下全部生产源码的通读（约 60 个 Kotlin 文件 + 全部模块构建脚本 + 架构/产品/性能文档）：

| 模块 | 覆盖 |
|---|---|
| app | MainActivity、NavHost、Theme/DesignTokens/GlossComponents、五个页面（Login/Channels/Tags/Settings/Feed）+ 各自 ViewModel/UiState、WindowSecurityController、cache/onboarding/config/di |
| player | VideoPlayerManager、TelegramMediaDataSource、TelegramHlsDataSource、StrictTelegramHlsManifestParser、TelegramHlsPlaybackSession、VideoPreloadManager、Media3SamplePreloadController、SamplePreloadHandoffGate、NextHlsPreloadManifestLoader、TdLibBandwidthMeter、AdaptivePreloadPolicyManager、ReusablePlayerLifecycle、全部 metrics/observation 类 |
| telegram | TelegramClientManager、OfficialTdLibBridge、各 Client 接口、全部 mapper、TelegramFileManager、TdLibMediaCacheManager、TdLibTelegramMessageRepository、TdLibTelegramChatRepository、认证仓库与凭证加密、PrivateMediaCacheSizer、DI |
| core:model/domain | VideoModels、TelegramAuthState、VideoPlaybackQueue、VideoQualitySelector、PlaybackRiskController、NextPreloadBudgetController、StreamingNetworkMetrics、TelegramFileGateway、HashtagParser 等 |
| core:database | Room v5 全部 Entity/DAO/迁移 |
| 文档 | ARCHITECTURE v2.5、PRODUCT_SPEC v1.8、DEVELOPMENT_PLAN v2.4、Stage 17/23 性能报告、Stage 24 交付报告等 |

### 0.2 总体结论（先说真话）

**这是一个工程质量极高的项目。** generation 门控、owner 租约协调、HLS 严格解析、混合 ABR、时长优先预加载预算、透明恢复、证据分层（主机确定性测试 → 模拟器 → 真机）等设计，在我审查过的同类项目中属于第一梯队。1106/1106 测试通过、构建配置带属性强校验、供应链全部固定 provenance，这些不是"优化空间"，而是应该继续保持的资产。

因此本提案的定位是三点：

1. **修补少量真实缺口**（P0）：索引缺失、进程恢复丢状态、每秒全量重建 UI 状态等——低成本、高置信。
2. **把已有的 A/B 机制真正用起来**（P1）：项目已经建好了候选机制（`PLAYBACK_TUNING_CANDIDATE`、feature flag、transition metrics），但三个重要机制因"真机 A/B 未验证"默认关闭。这些是理论上明确更优、且基础设施已就绪的选项。
3. **对 3 个架构决定发起挑战**（P2）：单播放器、50-60 秒缓冲窗口、R8 关闭。每项都给出新旧方案对比与理论分析；**理论不足以支撑推翻的，我会明确说"建议保持"，并给出理由**——不为了推翻而推翻。

---

## 1. P0：立即修复清单（高置信、低成本）

### P0-1 `media_cache_entries` 的 LRU 查询缺索引【数据库】

- 文件：`core/database/.../MediaCacheEntryDao.kt:16-23`
- 现状：`getLruEntries()` 执行 `WHERE cached_bytes > 0 ORDER BY last_accessed_at ASC, file_id ASC`，表上没有对应索引，每次 LRU 清理都是**全表扫描 + 排序**。缓存条目数随索引视频数增长（真实频道可到数千条），每次 `trimToLimit()`（每次 bind 后都会触发）都要付这个代价。
- 修复：`MediaCacheEntryEntity` 加 `Index(value = ["cached_bytes", "last_accessed_at"])`，Room 迁移 5→6。
- 理论对比：索引命中后 LRU 查询从 O(n log n) 全表排序列降为 O(log n) 游标扫描。无争议。
- 风险：极低（迁移只加索引，不动数据）。

### P0-2 ChannelSelectionViewModel 每秒全量重建 UI 状态【UI 性能】

- 文件：`app/.../feature/channels/ChannelSelectionViewModel.kt`
- 现状：FLOOD_WAIT 重试倒计时等两个 Job **每秒 tick 一次**，每次 tick 都触发 `rebuildUiState()` 全量重算（频道列表映射、associateBy、sumOf 计数）。频道多时每秒一次 O(n) 重算 + 全屏重组。
- 修复：把"剩余秒数"拆成独立的 `StateFlow<Long>`，仅倒计时文本组件订阅它；`rebuildUiState()` 只在真正的结构性事件（扫描状态、选择变化、错误）时调用。更彻底的做法是对齐 TagFilterViewModel 的声明式风格（`combine` + `stateIn`）。
- 理论对比：每秒一次 Int 更新 + 局部重组 vs 每秒一次全量列表重算 + 大范围重组。无争议。
- 风险：低（纯 ViewModel 重构，有既有测试保护）。

### P0-3 信息流筛选状态进程恢复丢失【Bug】

- 文件：`app/.../navigation/ChannelVideoFlowNavHost.kt`
- 现状：`playbackFilter` 用 `remember` 保存（非 `rememberSaveable`）。进程死亡恢复后 FEED 页的 initialFilter 静默丢失，退化为全部视频。当前靠 `configChanges` 自处理旋转掩盖了场景，但进程被杀（低内存回收、系统重启）是真实路径。
- 修复：换 `rememberSaveable`（`VideoFilter` 数据类可用自定义 Saver 或 @Parcelize 序列化）。
- 风险：极低。

### P0-4 频道事件链路无超时 + 错误分类语义不准【网络层】

- 文件：`telegram/.../chat/TdLibTelegramChatRepository.kt`（refreshSingleChat 路径）
- 现状：事件收集协程里 `client.getSupergroup` 未包 `withRequestTimeout`，若客户端无超时该协程长期挂起，后续 ChatChanged/TitleChanged 事件排队积压；且同一 catch 把**任意**异常标为 `Failed(Database)`（网络错误被误报为数据库错误，用户看到错误文案不准）。
- 修复：补超时（对齐 REQUEST_TIMEOUT_MILLIS=15s 约定），并按失败类型分类。
- 风险：低。

### P0-5 rebuffer 后带宽估计恢复过慢【播放器】

- 文件：`core/domain/.../media/StreamingNetworkMetrics.kt` + `VideoPlayerManager.kt:512-521`
- 现状：`onRebuffer()` 只把 estimate 打 0.6 折并清样本，fast/slow EWMA 保留旧值。真实断崖（如 Wi-Fi 切蜂窝）后，slow EWMA 需要很长时间才能爬升，恢复期播放器仍按旧低带宽选轨/评估。
- 修复：rebuffer 时对 slow EWMA 施加更激进的衰减（或按 rebuffer 时长重置 slow 分量），保持 fast EWMA 的快速跟踪。
- 理论对比：恢复期升轨速度由 slow 分量主导，衰减后预计恢复时间缩短数倍。有 Stage 17 弱网模拟工具链可验证（固定 seed 重放）。
- 风险：低-中（策略常数变化，需重放 Stage 17 矩阵确认无回归）。

### P0-6 线程/时钟契约文档化【健壮性硬化】

- `PlaybackRiskController`（core/domain）持有 `upgradeStableWindows`/`lastSwitchMillis` 可变状态，当前只在主线程 250ms tick 中调用；若未来 Media3 加载线程加入调用会竞争。修复：加单线程约束注释 + `@Synchronized` 或原子化（成本极低）。
- `PlaybackTransitionMetrics` 的 gesture 事件时间戳由调用方传入（当前所有调用方都用 `System.nanoTime()/1e6`，与内部时钟一致——已核实无实际 bug）。修复：契约注释 + 内部校验（负值/量级异常时断言）。
- 风险：零。

### P0-7 重复代码收敛【工程卫生】

已定位的四处重复：

1. press-scale 动画模式在 GlossCard/GlossActionPill/GlossQuickAction/ChannelRow 重复 4 次 → 抽 `Modifier.pressScale()`。
2. failure → 中文文案映射三套并存（LoginScreen、ChannelSelection 的 TelegramChatFailure/TelegramMessageFailure 各一套）→ 统一 mapper。
3. FLOOD_WAIT/Resend 倒计时逻辑在 AuthViewModel（2 处）与 ChannelSelectionViewModel（2 处）共 4 份 → 抽 countdown 工具。
4. 两个独立 DataStore 文件各存一个 key（media_cache_settings / video_feed_onboarding）→ 合并为单 store 多 key（需迁移，低优先可后置）。

### P0-8 硬编码中文与魔法数字进资源/令牌【UI 一致性】

- LoginScreen stepper 标签、TagFilter "已选择/未选择"、CacheSettings 标题、formatByteSize 的 "GB/MB"、多处 semantics 文案为硬编码中文；品牌已走 locale 资源（Stage 21），功能文案建议逐步资源化（对无障碍朗读一致性也有益）。
- 魔法数字（surface alpha 0.44/0.46/0.48、MIN_FREE_BYTES 等）入 DesignTokens。
- 死资源清理：`video_test_open`、`cache_open`、`tags_loading` 无引用。

### P0-9 UiState 显式 `@Immutable`【Compose 跳过优化】

- `VideoPlaybackUiState`/`FeedVideoItem` 等跨模块 data class 未标注 `@Immutable`。Kotlin 2.3 强跳过默认开启，但跨模块类型的稳定性推断有失败风险；显式标注可保证 Feed 页在 4Hz 快照更新下不出现意外的全页重组。
- 风险：零（纯注解）。

---

## 2. P1：对比后建议采用（理论明确更优，走候选机制落地）

> 说明：P1 各项都遵循项目纪律——先候选 flag / 固定 seed 重放 / 真机采样，通过证据门槛后再设为生产默认。

### P1-1 Kapt → KSP【构建】

| 维度 | 现状（Kapt） | 新方案（KSP） |
|---|---|---|
| 构建速度 | kapt 全量分析 + stub 生成，是编译链最慢环节 | KSP2 原生符号处理，编译期显著缩短 |
| 生态趋势 | Kapt 已进入维护模式，AndroidX 官方新模板默认 KSP | Hilt 2.58、Room 2.8.4 均完整支持 KSP |
| 代码影响 | `correctErrorTypes = true` 等 workaround | 移除 kapt 配置；`room.schemaLocation` 用 KSP arg 等价迁移 |
| 风险 | — | 极低（官方支持矩阵内），需跑全量 test + Room schema 导出比对 |

**建议：采用。** 这是唯一不需要任何真机验证、收益确定的工程项。

### P1-2 开启 R8 压缩（isMinifyEnabled = true）【推翻现有构建配置】

- 现状：release `isMinifyEnabled = false`，正式签名 APK **40,738,734 bytes**（Stage 24 记录）。
- 新方案：开启 R8 代码 + 资源压缩。理论对比：
  - 依赖里 Compose、Media3、TDLib Java 绑定（数千个自动生成的 TdApi 类）、Hilt 运行时全部全量入包。R8 可整体瘦身至预估 **26–32MB**，dex 数量与冷启动 dex 加载也同步下降。
  - TDLib Java 绑定**不使用反射**（纯生成类 + JNI 表），官方 Android 客户端同样以 R8 发布；Compose/Media3/Hilt 的 keep 规则由官方 consumer rules 自动合并。
- 风险与对策：
  1. TdApi 类被 shrink → 用 `-keep class org.drinkless.tdlib.** { *; }` 保守规则起步，验证通过后再收紧。
  2. native 方法混淆 → TDLib JNI 是固定符号绑定，keep 规则覆盖即可。
  3. 验证清单：release 全量 test 矩阵、真机登录/扫描/播放/seek/HLS 回退、APK 静态扫描（沿用 Stage 24 的凭证反扫 + ABI 白名单检查）。
- 风险：中（需真机回归），收益：APK 体积 -25% 左右 + 启动收益。**建议：作为独立 Stage 做。**

### P1-3 打开 Owner Promotion（生产默认 false → true）【预加载】

- 文件：`player/.../VideoPreloadManager.kt:884` `PRODUCTION_OWNER_PROMOTION_ENABLED = false`
- 机制（Stage 13B 已实现、测试完备）：下一条的 256KiB 预加载 owner 在 bind 时**原地晋升**为当前播放 owner，而不是 cancel → 重新 acquire。
- 理论对比：
  - 关闭时：滑动 settle 后，当前播放的新 acquire 与预加载的旧 owner 并行短暂存在，随后 cancel 旧请求 → 多一次 TDLib cancel + 优先级切换往返，且 promotion 相关过渡路径（TARGET_PENDING/COMMITTED/SHARED_WITH_CURRENT）全部走不到。
  - 打开时：预加载请求被直接复用，省一轮 cancel/acquire，字节零浪费。
- 当初关闭的原因：真机 A/B 未验证（项目纪律，不是技术否决）。
- **建议：作为下一个真机 A/B 的默认开启候选**，用 `transitionMetrics` 的 `bindToFirstByteMs` 采样对比。风险：低（代码与测试都在，只翻开关）。

### P1-4 DataSource 顺序读复用活跃 lease【播放器微优化】

- 文件：`player/.../TelegramMediaDataSource.kt`（`advanceRange()` → `acquireAndOpen()`）
- 现状：顺序播放每 256KiB（`DEFAULT_CHUNK_SIZE_BYTES`）边界就重新 acquire lease、注册 cancellation、重开 RandomAccessFile。虽然 await 在 covers() 时立即返回（无网络代价），但每个 chunk 都产生 lease/registration/文件句柄的创建与销毁。
- 新方案：`advanceRange()` 先检查当前 lease 的 snapshot 是否仍 covers 新区间（readAhead 4MiB 意味着绝大多数情况 covers），是则**直接扩展 rangeEnd 复用句柄**，仅当不 covers 时才走新 lease 路径。这是 Media3 DataSource 顺序读的常见优化（文件句柄复用）。
- 理论对比：顺序播放是 99% 的场景；每 4MiB 减少 15 次 lease 生命周期操作。收益是 CPU/GC/文件句柄抖动，网络无变化（TDLib 下载由 readAhead 持续驱动）。
- 风险：低-中（涉及 close/取消语义，现有 DataSource 测试矩阵需全绿）。备选：同时把 chunk 提到 512KiB（初始候选 1–4MB 的文档约定下仍保守）。

### P1-5 ABR 评估节流 250ms → 1s【CPU】

- 文件：`VideoPlayerManager.refreshPlaybackProgress()` → `applyPlaybackRisk()`（250ms tick 内每次都做 bitrates 排序、映射、决策评估）
- 现状：ABR 决策与进度采样同频（4Hz）。HLS 切轨决策的时间常数在秒级，4Hz 纯属浪费。
- 新方案：ABR 评估单独 1s 节流（进度采样保持或另行调整，见 P1-6）。
- 风险：极低。

### P1-6 进度 tick 250ms → 500ms + 插值【电耗】

- 现状：`PROGRESS_UPDATE_INTERVAL_MILLIS = 250`，主线程 Handler 4Hz 更新 snapshot → 进度条 Canvas 4Hz 重绘。播放期间永续运行。
- 新方案：500ms tick + 进度条用 `Animatable` 在两次采样间线性插值，视觉平滑度不变，主线程更新量减半。
- 理论对比：2dp 进度线的 4Hz vs 2Hz+插值，人眼不可分辨；电耗/CPU 直接减半。备选方案（更激进）：只在 scrub/暂停/状态变化时更新位置，播放中用插值器自走——收益更大但实现复杂。
- 建议：先 500ms 候选；`PLAYBACK_TUNING_CANDIDATE` 机制可承载 A/B。

### P1-7 启动期 `reconcileIndexedFiles` 并发化【启动】

- 文件：`telegram/.../media/TdLibMediaCacheManager.reconcileIndexedFiles()`
- 现状：Ready 后对每个索引 fileId 串行 `getFile` 逐条 forEach。索引数千条时启动期长尾明显。
- 新方案：分批 8 并发 async（`execute` 的 send 非阻塞，并发可隐藏 round-trip 延迟），或推迟到空闲期执行。
- 风险：低（getFile 是本地操作，不触发限流）。

### P1-8 TDLib 本地数据库加密【安全增强，谨慎评估】

- 文件：`TelegramClientManager.sendParameters()` 中 `databaseEncryptionKey = byteArrayOf()`
- 现状：TDLib 的 sqlite（频道/消息元数据、本地会话缓存）在 noBackupFilesDir 明文。备份已禁用（allowBackup=false），但 root/ADB 可读。
- 新方案：随机生成 32 字节 key，用与凭证同构的 Android Keystore AES-GCM 方案持久化（`noBackupFilesDir/credentials/` 同目录），启动时解密传入 `databaseEncryptionKey`。
- 理论对比：官方 Telegram 客户端本身也只在用户设 passcode 时加密；TDLib 的数据库加密是官方支持的一等特性。威胁模型（设备丢失/取证）下收益明确。
- 风险：中——key 丢失 = 会话重登（可接受）；TDLib 加密路径需真机验证（打开已有明文库的升级迁移语义需实测：先备份→加密重开→验证）。
- 建议：列入候选 Stage，与 P0 批次独立评估。

---

## 3. P2：架构级挑战（需要决策）

### P2-1 单播放器 vs SampleQueue 预热 vs 双播放器池【本提案最大的架构议题】

**现状拆解。** 当前滑动首帧链路（模拟器 Stage 17 NORMAL，p50/p95）：

```text
settled → plan(已预制备) 10.4/11.9ms → prepare 143.1/238.5ms → 首帧 233.7/329.2ms
```

真机 CDN 往返 + 解码器初始化未验证，预计比模拟器数值更大。项目里有两个为此设计、但**默认关闭**的机制：

- Owner Promotion（`PRODUCTION_OWNER_PROMOTION_ENABLED=false`）：预加载 owner 晋升，省 cancel/acquire 往返（见 P1-3）。
- Media3 SampleQueue 预热（`cvfSampleQueuePreloadEnabled=false`）：官方 `DefaultPreloadManager` 为下一条建立 demuxer + 填 SampleQueue（≤10MiB 预算、CappedNextSampleGateway 防越界、SamplePreloadHandoffGate 防陈旧交接——全部已实现并测试），交接时同一播放器从热队列继续。

**三条路线理论对比：**

| 维度 | A. 现状+OwnerPromotion | B. A + SampleQueue 预热 | C. 双播放器轮换 |
|---|---|---|---|
| 首帧组成 | settle→bind→prepare（moov/首段）→解码 | 省 demuxer+moov+首段等待，仍需 MediaCodec init（~30–80ms 真机） | 第二播放器已在 READY，仅 surface 切换 + play，首帧 ≈ 1–2 帧 |
| 理论首帧 | 基线 | 基线 − prepare 段 | 基线 − prepare − codec init |
| 新代码量 | 0（翻开关） | 0（翻开关） | 大：架构合同 6.7/FR-FEED-05 重写、双 PlayerView 在 Pager 中的布局与 z-order 切换、双音频焦点、双生命周期同步 |
| 内存 | 基线 | 基线 + SampleQueue 缓冲 | +约 30–60MB（第二解码器/输出缓冲） |
| 风险 | 极低 | 低（交接门已测） | 高（Compose+SurfaceView 双实例是已知复杂域） |

**结论与建议：** 理论收益上 C > B > A，但**收益差量**（C 比 B 只多省 MediaCodec init，几十毫秒量级）远小于**风险差量**。当前证据不足以支撑推翻"唯一 ExoPlayer"合同。推荐路线：

1. 第一步：真机 A/B 同时打开 OwnerPromotion + SampleQueue（flag 机制已备好，`SamplePreloadAbEvaluator` 的启用判据是 p95 改善 ≥15% 且零安全失败——代码里已有评估器）。
2. 第二步：若 settle→首帧 p95 仍 >250ms，且 `transitionMetrics`（`bindToPrepareMs`/`prepareToReadyMs`/`readyToFirstFrameMs` 字段已在采样）证明剩余时间集中在 prepare 段，再立项评估双播放器。届时双播放器方案才有数据支撑，"理论上更优"才成立。
3. 我倾向于预判：SampleQueue 打开后大概率不需要双播放器。

### P2-2 缓冲窗口 50–60s → 25–40s【推翻现有 tuning 的候选】

- 现状：`MIN_BUFFER_MILLIS=50_000 / MAX_BUFFER_MILLIS=60_000`（`PLAYBACK_TUNING_CANDIDATE="12D-FINAL"`），rebuffer 恢复阈值 12s。
- 问题：feed 视频多为 15–60 秒，60s 缓冲上限意味着**大多数视频被几乎完整下载**——与"下一条只预加载少量字节"的设计哲学矛盾，尾部流量大量浪费。例：40s/720p@1.6Mbps ≈ 8MB，用户停留 15s 就滑走，约 5MB 白下。
- 新方案：maxBuffer 降到 25–40s 区间（给出 3 个候选：25s / 30s / 40s），rebuffer 恢复阈值同步调 8–10s。TDLib 已下载前缀保留在私有缓存，seek 已看部分零成本。
- 理论对比：流量节约与视频时长分布直接相关（估算 25–40% 下载量下降）；风险是弱网 rebuffer 概率上升，但现有下一条预加载体系（10MiB ceiling）+ 弱网质量选择（Stage 17 已把 0.5Mbps 场景 p95 从 7031ms 打到 1572ms）吸收了相当部分风险。
- **不能拍板**：Stage 12D 的 50–60s 是真机调优结论（rebuffer 恢复窗口需要空间），推翻它需要 Stage 17 弱网重放矩阵 + 真机流量/rebuffer 采样。**建议：用 `PLAYBACK_TUNING_CANDIDATE` 机制生成 3 个候选做真机 A/B。** 这是本提案里最"敢于推翻"但必须用数据说话的一项。

### P2-3 明确不推翻的决定（审查后确认现状正确，避免无效折腾）

| 决定 | 为什么保持 |
|---|---|
| Compose `VerticalPager` | 单 PlayerView 置底 + Pager 覆盖交互层的结构是对的；自研滚动布局无收益 |
| 手写枚举导航（不用 Navigation Compose） | 4 屏应用、无深链/转场需求；预测返回已兼容。仅需 P0-3 修复 |
| 单 ABI arm64-v8a、固定 TDLib 1.8.66、NDK 23.2 | provenance 纪律与真机唯一 ABI；模拟器测试已用"instrumentation buildType 排除 .so"绕过，不构成升级理由 |
| 不用图片加载库（无 Coil/Glide） | 应用无网络图片需求；加载海报用调色板渐变代替，是刻意的设计选择 |
| 不用 Media3 SimpleCache / 不建第二缓存 | 单字节缓存策略是 FR-CACHE-01 的核心合同，与 TDLib 私有缓存共存会双写放大 |
| 随机模式 2,000,000 页 Pager | 行业通行"无限轮播"手法，无内存代价；重排逻辑已有边界避免。仅建议补一个极端边界测试（round 边界 + items 变化的组合） |
| 扫描并发保持 2 worker | FLOOD_WAIT 共享闸门已把限流风险管住；提到 3–4 的边际收益低、限流风险高 |
| 不用 FTS | 当前检索是标签精确匹配 + 内存 NFKC 搜索，无全文需求；若未来加 caption 全文搜索再上 FTS4/5 外部内容表 |

### P2-4 数据生命周期：`videos` 表软删记录无限增长【数据层前瞻】

- 现状：`is_deleted=1` 的记录永不清理，feed 查询虽排除它们，但表体积与索引随频道历史增长（真实超长频道可达数万条）。
- 方案（前瞻）：保留最近 N 个月的软删记录（供对账），更旧的物理删除——或加清理任务在扫描完成时批量执行。当前不是紧急项（v5 索引与 100/页分页已把热路径管住），列为 P2 存档。

---

## 4. 建议落地顺序与验证计划

### 4.1 落地顺序

```text
第一批（P0 批量，无需真机）：P0-1 索引迁移 → P0-2/P0-3 → P0-4/P0-5 → P0-6/P0-7/P0-8/P0-9
  验证：全量 test（1106 基线）+ lint + assembleDebug + Room 迁移 5→6 emulator 测试

第二批（P1 候选，逐个 Stage）：
  1. P1-1 KSP（构建侧，可独立先行）
  2. P1-3 OwnerPromotion + P1-5/P1-6 节流（一个"播放器微调"Stage）
  3. P1-4 lease 复用 + chunk 512KiB（DataSource Stage）
  4. P1-2 R8（独立 Stage，真机回归最重）
  5. P1-7/P1-8（启动与安全 Stage，各自独立）

第三批（P2 决策点，真机数据驱动）：
  P2-2 缓冲窗口 A/B（3 候选）→ P2-1 SampleQueue 真机 A/B → 按数据决定是否立项双播放器
```

### 4.2 真机 A/B 方法（沿用项目既有框架）

- 每个播放相关候选走 `PLAYBACK_TUNING_CANDIDATE` / feature flag 机制（gradle 属性 + BuildConfig 强校验已就绪）。
- 采样用现有 `transitionMetrics`（`gestureToSettleMs`、`settleToPlanMs`、`bindToFirstByteMs`、`bindToReadyMs`、`readyToFirstFrameMs`、rebuffer 计数）——这套埋点就是为了 A/B 设计的，直接消费。
- 弱网复现用 Stage 17 固定 seed 重放矩阵（NORMAL/SLOW05/SLOW10/SLOW20），保证"同条件对比"。
- 每项记录：p50/p95、rebuffer 次数/时长、TDLib 调用数、下载字节数，沿用"证据分层"汇报模板。

### 4.3 预期收益汇总（理论值，待真机证实）

| 提案 | 预期收益 |
|---|---|
| P1-2 R8 | APK 40.7MB → 26–32MB；启动 dex 加载下降 |
| P1-1 KSP | 构建时间显著缩短（无运行时影响） |
| P1-3 + P2-1(SampleQueue) | settle→首帧 p95 预计减 30–50% |
| P2-2 缓冲窗口 | 下载流量 -25~40%（候选值相关） |
| P0 批次 | 每秒全量重建消除、LRU 清理降为索引扫描、进程恢复状态修复 |

---

## 5. 附录：本次审查中确认的高质量设计（建议长期保留）

1. **generation 门控体系**：`PlaybackCallbackGate`/`SessionToken`/`accountGeneration`/`cancelGeneration` 层层拒绝陈旧回调——播放器、TDLib、文件管理三处一致。
2. **owner 租约协调**（TelegramFileManager）：单文件多 owner 区间合并、优先级仲裁、进度感知等待（15s 无进展 + 90s 硬上限）、prefix probe 复用已有下载。
3. **HLS 严格解析 + 单次回退**：只放行白名单 tag、禁止外部 URI、资源 generation 绑定、失败单次回退 direct MP4。
4. **时长优先预加载预算**（10MiB 硬顶 + 512KiB chunk 重评估）与 SampleQueue 的预算封顶网关。
5. **4Hz 快照隔离**：`toPresentationSnapshot()` 剥离进度字段，高帧快照只驱动进度条子树——这个设计让整个 Feed 不会被 4Hz 更新拖垮（我最初怀疑的全屏重组问题不存在）。
6. **测试纪律**：1106/1106 主机测试、固定 seed 弱网重放、证据分层文档、候选机制 + A/B 评估器。
7. **供应链与安全**：TDLib/OpenSSL 固定版本 + SHA-256 provenance、凭证 AES-GCM + Keystore + noBackupFilesDir、release 凭证强制为空、明文清零。

---

## 6. 待确认事项

1. 是否批准第一批（P0 批量）？预计一次改 9 处 + 新增 1 个 Room 迁移。
2. P1 各候选是否按建议顺序逐个立项？
3. 真机（iQOO 12）是否已可用于 A/B 采样？若否，P1-3/P2-1/P2-2 只能先完成主机/模拟器门槛并保持默认关闭。
4. P2-1 的路线（先 SampleQueue 后双播放器）是否认可？
