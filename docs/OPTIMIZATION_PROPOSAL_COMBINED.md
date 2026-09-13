# VELORA（曜流）全面优化提案

> Codex 主方案 × Claude Code 可采纳项融合版

文档版本：1.0
日期：2026-09-04
状态：**仅为方案；尚未批准实施；未修改生产代码**

> 以上为 2026-09-04 的历史状态。2026-09-09 用户已授权 A-F 连续实施；当前工作树、实验开关、验证结果见 [Stage 25 实施结果](STAGE25_OPTIMIZATION_RESULTS.md)。下文的阶段编号和性能数字保留为原始规划，不能代替当前结果。

---

## 0. 执行摘要

VELORA 1.1.0 已经具备清晰的 Telegram、Room、Media3、缓存和安全边界，不需要为了“现代化”推翻 Kotlin、Compose、TDLib、Media3、Room 或 Hilt。下一轮真正值得推翻的是几条已经开始限制规模和可验证性的内部路径：

1. Feed 一次性加载完整视频模型及频道全部标签；
2. 数据库故障被转换成空列表，错误语义丢失；
3. 播放状态、队列、预加载和页面交接集中在超大 ViewModel/Composable；
4. TDLib 高频文件事件、缓存元数据写入和启动对账缺少背压与预算边界；
5. 播放性能已有大量合成和历史真机证据，但当前 SampleQueue 候选仍缺严格真机 A/B；
6. UI 已有品牌辨识度，但频道/标签页面的信息密度、层级和大屏适配仍可重构；
7. Release 缺少 R8、Baseline Profile、Macrobenchmark、CI 和依赖校验闭环。

本提案吸收 Claude Code 方案中可验证的倒计时隔离、导航恢复、错误分类、资源集中、R8、数据生命周期和 TDLib 数据库加密建议；修正错误的 LRU 索引、KSP、`@Immutable`、并发启动扫描、进度动画和播放器结论。

### 总体路线

```text
先建立基线
    ↓
修正错误语义和恢复一致性
    ↓
重构 Feed 查询与窗口化水合
    ↓
治理 TDLib 事件和缓存写入
    ↓
拆出可测试的播放会话状态机
    ↓
单播放器 SampleQueue 真机 A/B
    ↓
必要时再做双播放器池真机 A/B
    ↓
UI、大屏、R8/Profile、安全与发布工程
```

一次只实施一个阶段。任何阶段的 Proof 失败时停止扩展，先定位首个根因。

---

## 1. 固定边界

以下合同不因优化而改变：

- Kotlin、Jetpack Compose、Material 3；
- Kotlin Coroutines、Flow；
- AndroidX Media3 ExoPlayer；
- Room、DataStore；
- Telegram 官方 TDLib，使用个人账号，不使用 Bot API 或手写 MTProto；
- UI → ViewModel → UseCase/Repository → 实现 → TDLib/Room/Media3；
- 视频字节只存在于 TDLib 的应用私有缓存，不引入 Media3 第二份完整磁盘缓存；
- 只预判并预热唯一下一条；不建立随页面数量增长的播放器；
- 任意时刻最多一个播放器请求音频焦点并发声；
- 移动数据默认不预加载；低存储、省电、热状态、低内存和当前播放饥饿时立即让路；
- 受保护内容继续遵守 `FLAG_SECURE`，不增加保存、导出或分享；
- 权限仍只有 `INTERNET` 与 `ACCESS_NETWORK_STATE`；
- 不记录凭证、验证码、密码、正文、文件路径、owner token 或完整 TDLib 对象。

---

## 2. 当前事实基线

### 2.1 已经做对的部分

- 全应用只有一个生产 ExoPlayer 和一个活动 PlayerView；
- 普通换片复用引擎，不再 `stop + clearMediaItems` 重建管线；
- CURRENT 与唯一 NEXT 使用不同优先级和所有权令牌；
- TDLib `downloadFile(offset, limit)` 已映射 Media3 `DataSpec.position/length`；
- 当前 progressive/HLS、混合 ABR、动态下一条预算和回退均有单测边界；
- SampleQueue 预热代码已存在，但生产开关默认关闭；
- Owner Promotion 已完成同设备 A/B 并被明确否决；
- 当前 UI 已有明确的曜流品牌、沉浸式播放页和内容保护。

### 2.2 已有播放器性能证据

Stage 12D/12E 的真实设备结果：

| 指标 | P50 | P90 | 说明 |
|---|---:|---:|---|
| bind → 首帧 | 88–89 ms | 226–229 ms | 播放器绑定、准备、解码和首帧 |
| target known → settle | 约 433 ms | 约 435 ms | Pager 动画与交互链路 |
| gesture → 首帧 | 约 719 ms | 约 865 ms | 包含输入、动画和播放器尾段 |

这组数据非常重要：第二播放器即使把下一条完全预解码，也主要只能消除现有约 0.09～0.23 秒的播放器尾段，不能消除手势、Pager 动画、队列决策和目标命中成本。

### 2.3 已否决的历史候选

Owner Promotion 不能重新进入生产候选：

| 同设备 A/B | FIRST_FRAME | bind → 首帧 P90 | max |
|---|---:|---:|---:|
| 关闭 | 12/12 | 1,599 ms | 1,783 ms |
| 开启 | 11/12 | 3,033 ms | 4,013 ms |

开启后 P90 恶化约 89.7%，最大值恶化约 125.1%。后测缓存条件理论上更有利，结果仍失败，因此不得与 SampleQueue 或双播放器组合后重新包装为新方案。

---

## 3. Claude Code 方案融合决定

| Claude Code 建议 | 融合决定 | 调整后的做法 |
|---|---|---|
| 频道倒计时减少全页重组 | 采用 | 将时间变化变成局部状态，只重组倒计时叶节点 |
| 恢复 `playbackFilter` | 采用并扩大 | 原子保存目标页面、频道、标签模式、标签集合和播放顺序 |
| TDLib 超时/错误分类 | 采用 | 统一映射为显式领域失败，不将失败伪装为空状态 |
| 文案、颜色、无障碍集中 | 采用 | 与 UI 信息架构阶段一起完成 |
| R8 与资源压缩 | 采用 | 独立阶段实测，不提前承诺 APK 大小 |
| 软删除数据清理 | 采用 | 设定保留期、事务和可恢复边界 |
| TDLib 数据库加密 | 采用 | 新安装优先；旧账号必须设计失败关闭和迁移策略 |
| `(cached_bytes, last_accessed_at)` 索引 | 修正 | 用 `EXPLAIN QUERY PLAN` 比较 partial/order-first 索引，不按猜测建索引 |
| rebuffer 后重置 EWMA | 仅实验 | 先证明当前估值方向错误，再做单变量网络 A/B |
| `@Synchronized` 防竞态 | 拒绝直接采用 | 先明确线程所有权并写竞态测试；不以锁掩盖设计问题 |
| 合并 DataStore | 拒绝 | 没有足够收益，不承担迁移风险 |
| 全量 `@Immutable` | 拒绝 | 先看 release Compose compiler report，只修实测热点 |
| Kapt 全迁移 KSP | 当前拒绝 | Hilt/Dagger KSP 仍不满足仓库稳定依赖合同 |
| Owner Promotion | 拒绝 | 已有明确负向真机 A/B |
| 8 并发 `getFile` 启动对账 | 拒绝 | TDLib 客户端为串行边界，应取消全量启动扫描并预算化对账 |
| 500 ms ticker + Animatable | 修正 | 暂停/结束/不可见时停止 ticker；动画与节能分别测量 |
| 512 KiB DataSource/lease 复用 | 仅实验 | 先采集分配、GC、句柄和首帧分段证据 |

---

## 4. 目标架构

### 4.1 Feed 从“完整列表”改成“键快照 + 水合窗口”

当前路径会先查询全部匹配视频，再查询所选频道中的全部视频标签，随后构造完整 `IndexedVideo` 列表。Room 任意相关更新都可能触发整表模型重建，播放 ViewModel 再执行多轮 O(n) 队列协调。

目标路径：

```text
Room Filter Query
    ↓
VideoKeySnapshot（chatId + messageId + 最小排序字段）
    ↓
PlaybackFeedSession（顺序/随机轮次、当前位置、generation）
    ↓
HydrationWindow（上一条、当前、下一条，必要时 ±2）
    ↓
完整 IndexedVideo + tags + 播放候选
```

关键决定：

- 最新模式和随机模式都可以持有轻量键序列；
- 随机轮次继续保证一轮内不重复和边界不立即重复；
- 只有当前窗口加载 caption、标签、候选文件等重模型；
- Room 更新时先比较键快照差异，不无条件重建所有 UI 模型；
- 不直接套用 Paging 3。随机轮次需要全局键集合，Paging 3 更适合单向顺序浏览；若后续数据规模证明需要，可将 Paging 作为最新模式的独立候选。

预期收益必须通过基准确认，不能在方案阶段写成固定百分比。

### 4.2 显式失败，不再以空列表掩盖异常

将 Feed、标签和扫描状态改为封闭结果：

```text
Loading
Empty
Content
RecoverableFailure
SignedOut
```

- `CancellationException` 原样抛出；
- 数据库、TDLib、网络、权限失效和文件失效分开映射；
- 保留上次成功内容时显示“内容 + 非阻断错误”，不瞬间清空页面；
- 真正查询成功且零条记录时才显示 Empty。

### 4.3 播放会话从 ViewModel 中拆出

`VideoPlaybackViewModel.kt` 当前约 1,776 行，`VideoPlaybackScreen.kt` 约 2,700 行。目标不是按行数机械拆文件，而是形成可独立测试的状态所有者：

```text
VideoPlaybackViewModel
    ├─ PlaybackFeedSession
    │    ├─ key snapshot
    │    ├─ random round
    │    ├─ current/target/settled generation
    │    └─ removal/reconciliation
    ├─ PlaybackPlanResolver
    ├─ PreloadDecisionPort
    └─ VideoPlaybackController
```

Compose 只接收稳定展示状态和 typed intents；进度叶节点、Pager 状态、控制层和详情层分开重组。

### 4.4 TDLib 高频事件与缓存写入治理

当前 `UpdateFile` 通过有界 `MutableSharedFlow` 后使用 `emit` 进入串行 dispatcher。文件进度高峰可能延迟授权、消息或控制事件；缓存 touch 又会为每次更新启动独立 Room upsert，旧写入可能晚于新写入完成。

目标：

- 授权/账号/控制事件使用不可丢失的高优先级通道；
- `UpdateFile` 按 `fileId` 合并，只保证消费者最终看到最新单调进度；
- 缓存 touch 使用单写入 actor，按 `fileId` 合并，时间取 max，字节按可信快照更新；
- 批量或节流写 Room，不让每个读取动作产生独立数据库任务；
- 启动不再遍历全部索引视频逐个 `getFile`；官方 storage statistics 为容量事实来源，LRU 缺口在空闲/设置页按页预算化修复；
- LRU 删除查询用 `EXPLAIN QUERY PLAN` 证明索引，而不是凭列顺序猜测。

---

## 5. 双播放器池专项结论

### 5.1 能不能用？

**能，但当前只能作为严格受限的实验候选，不能直接替换生产单播放器。**

仓库合同禁止的是“页面数量决定播放器数量”和“多路同时发声”，不是硬性禁止容量为 2 的固定池。一个 ACTIVE 加一个 STANDBY 的池在架构上可行，前提是：

- 硬上限永远为 2；
- 只有 ACTIVE 获取音频焦点和发声；
- STANDBY 只能对应唯一下一条；
- STANDBY 所有读取仍受 NEXT owner、优先级和动态字节预算限制；
- 低资源或任何异常立即释放 STANDBY，回退现有单播放器；
- 退出 Feed、后台、退出账号和清理缓存时两个播放器都完整释放。

### 5.2 为什么它理论上首帧最优？

单播放器 SampleQueue 可以提前完成：

- manifest/source；
- track selection；
- extractor；
- 压缩 sample 进入内存队列。

双播放器如果持有第二个有效 Surface，还能进一步提前完成：

- 第二个 MediaCodec 初始化；
- 视频 sample 解码；
- 第一帧输出到备用 Surface；
- 用户切页时只做角色和可见 Surface 交接。

因此，在“用户确实滑到被正确预测的下一条、备用首帧已经完成、设备支持双硬解、Surface 交接稳定”的条件下，双播放器的理论首帧下限确实优于只预加载 SampleQueue。

但这是条件最优，不是全局最优。预测错误、快速反滑、随机目标变化、弱网未达到预算、双解码器被系统回收、备用 Surface 未真正合成时，收益会消失或转为负收益。

Android 官方提供 `DefaultPreloadManager`，用于列表/轮播提前准备 MediaSource 和 SampleQueue；官方也明确提醒预加载过多会浪费功耗和网络。MediaCodec 还存在 `ERROR_INSUFFICIENT_RESOURCE` 与 `ERROR_RECLAIMED`，说明第二解码器并非所有设备都可稳定持有。

参考：

- [Media3 DefaultPreloadManager](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager)
- [DefaultPreloadManager 概念与交接](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts)
- [Media3 Surface 类型](https://developer.android.com/media/media3/ui/surface)
- [MediaCodec 资源错误](https://developer.android.com/reference/android/media/MediaCodec.CodecException)

### 5.3 三种方案比较

| 方案 | 提前下载 | SampleQueue | 预解码 | 备用 Surface | 资源成本 | 当前决定 |
|---|---:|---:|---:|---:|---:|---|
| A 当前单播放器 + 动态字节预热 | 是 | 否 | 否 | 否 | 最低 | 生产基线 |
| B 单播放器 + DefaultPreloadManager | 是 | 是 | 否 | 否 | 中低 | 已实现、待真机 A/B |
| C 固定双播放器池 | 是 | 是 | 是 | 是/实验变体 | 最高 | 只有 B 之后才测试 |
| 每页播放器 | 是 | 是 | 是 | 每页一个 | 不可控 | 永久拒绝 |

### 5.4 为什么先测 B，再测 C？

当前残余 `bind→首帧` 的 P50 只有约 88ms，P90 约 229ms。方案 B 已能提前完成 source、track、extractor 和 sample 读取，很可能拿走双播放器的大部分收益，却不需要第二解码器和第二 Surface。

正确顺序：

1. A 与 B 使用同设备、同账号、同队列、同网络窗口和同缓存前置条件 A/B；
2. B 未达到 15% P95 改善时，分析分段到底卡在网络、extractor、decoder 还是 Surface；
3. 只有 decoder/Surface 残余仍显著，才进入 C；
4. C 与胜出的单播放器方案比较，而不是只和最旧基线比较。

禁止同时打开 SampleQueue 实验和双播放器新逻辑后宣称双播放器有效；候选之间必须互斥，保证单变量因果。

### 5.5 双播放器候选设计

```text
BoundedPlayerPool(maxSize = 2)

ACTIVE
  - visible surface
  - CURRENT owner/priority
  - audio focus = true
  - volume = user setting

STANDBY
  - exact next VideoKey + fileId + generation
  - NEXT owner/priority
  - audio focus = false
  - volume = 0
  - strict 0/2/5/10 MiB dynamic budget
  - prepared/decoded state is disposable
```

角色晋升顺序：

1. 校验 key、fileId、质量、账号、网络和队列 generation；
2. 旧 ACTIVE 先暂停并撤销音频焦点；
3. STANDBY 的 request session 从 NEXT 提升为 CURRENT，并解除下一条 payload cap；
4. 交换可见 Surface/层级；
5. 新 ACTIVE 按用户静音状态获取焦点并播放；
6. 旧 ACTIVE 清理媒体后变为空闲 STANDBY；
7. 角色变化期间任一校验失败，放弃晋升并走现有单播放器 bind。

必须由 `PlaybackPoolCoordinator` 唯一持有两个播放器，Compose 页面不能创建播放器。不能简单复制两个现有 `VideoPlayerManager`，否则会复制 50～60 秒 LoadControl、CURRENT 4 MiB 前读、音频焦点和指标状态，破坏 NEXT 预算。

### 5.6 Surface 实验要拆成两个变体

- **C1：第二播放器 + 无可见备用 Surface**
  只证明双 decoder/prepare 是否可行；晋升时仍切换到主 Surface。资源较低，但不能提前证明“真实首帧已经合成”。

- **C2：第二播放器 + 持久备用 Surface**
  可以真正预渲染首帧，理论延迟最低；但 SurfaceView 层级、黑帧、旧帧泄漏、Compose 生命周期、功耗与 `FLAG_SECURE` 风险最高。

C1 不能自动升级成 C2；两者必须单独测量。不得为了淡入淡出直接把两个常驻 SurfaceView 改为 TextureView，因为官方建议普通视频优先 SurfaceView，其通常有更低功耗和更准确帧时序。

### 5.7 双播放器强制回退条件

出现任一条件，当次 Feed 会话永久降级到方案 A/B，不循环重建第二播放器：

- `ERROR_INSUFFICIENT_RESOURCE` 或 `ERROR_RECLAIMED`；
- 第二 decoder 初始化失败；
- 黑帧、错视频、上一条残帧或双音频；
- STANDBY 请求越过动态 NEXT 预算；
- 当前 reservoir 低于安全线或发生 rebuffer；
- 网络切换、计量网络默认禁用、低内存、低存储、省电或 MODERATE+ 热状态；
- 快速滑动导致预测目标失效；
- 页面后台、退出、logout、缓存清理或 generation 改变。

### 5.8 双播放器验收门槛

与胜出的单播放器方案相比，同时满足以下条件才允许讨论默认启用：

- 至少 30 次正常正向、30 次反向、30 次快速连滑；
- FIRST_FRAME 100%；
- gesture → visible first frame P95 改善至少 15%，且绝对改善至少 75ms；
- bind/promotion → visible first frame P95 有明确改善；
- black/wrong/old-frame/audio-overlap/crash 均为 0；
- 30/60 秒 rebuffer 不劣于单播放器；
- skipped next 浪费字节 P95 不增加超过 25%；
- 当前播放绝不因 STANDBY 抢带宽；
- PSS 增量不超过 64 MiB 且不超过单播放器峰值的 25%；
- 无 codec resource/reclaimed 错误；
- gfxinfo/FrameTiming jank 不增加超过 2 个百分点；
- 30 分钟热态不触发更高 thermal 档位；
- 低端或双 decoder 不可用设备能稳定自动回退。

最终决定：**双播放器池进入“有条件实验储备”，不是当前推荐生产方案。理论首帧最优成立，但优先验证成本更低的单播放器 SampleQueue。**

---

## 6. 分阶段实施计划

## Stage 25A：真实基线与可观测性闭环

### Outcome

得到可重复的启动、查询、重组、滑动、首帧、缓存写入、TDLib 事件、内存和浪费字节基线，所有后续优化有统一比较标准。

### Scope

- 增加 release-like Macrobenchmark/基准模块；
- 扩展现有脱敏 benchmark，记录 SQL、Feed 水合、visible first frame、PSS、jank 和预加载命中；
- 启用临时 release Compose compiler reports；
- 记录 APK 分项体积，而非只记录总大小。

### Boundary

- 不改变产品行为、播放器参数、查询结果、缓存策略或 UI；
- 不清真实账号与凭证；需要冷缓存时仅在用户明确授权后执行。

### Failure states

- 样本不足、页面目标不确定、网络条件变化、目标包 crash 或数据脱敏失败时 benchmark 非零退出。

### Proof

- 主机单测和 benchmark parser；
- API 36 emulator 只验证流程；
- 性能数字只取真机 release-like 构建；
- 输出 baseline JSON/Markdown 与 Perfetto trace。

官方依据：[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。

## Stage 25B：错误语义与进程恢复

### Outcome

数据库或 TDLib 故障不再显示为空内容；进程重建后页面和筛选条件始终一致。

### Scope

- Repository 返回显式 Feed/Tag/Scan failure；
- 原子保存 `AuthorizedDestination + VideoFilter + order`；
- 恢复数据失效或账号改变时安全回到频道页；
- 增加进程恢复与故障注入测试。

### Boundary

- 不重构 Feed 查询，不引入 Navigation Compose，不改播放策略。

### Failure states

- 状态版本不兼容、筛选频道已失效或账号已退出：清除恢复快照并回到安全入口；
- Room 异常：保留上次内容并显示可重试失败。

### Proof

- Repository Fake/DAO 故障测试；
- `ActivityScenario.recreate()`；
- API 36 emulator Compose 路径；
- 全量 test/lint/assembleDebug。

## Stage 25C：Feed 键快照与窗口化水合

### Outcome

视频数量增长时，筛选和 Room 增量更新不再构造所有完整视频和所有频道标签；当前、前后项仍稳定播放。

### Scope

- 新增轻量 `VideoFeedKeyRow`/领域快照；
- DAO 增加过滤键查询和复合键窗口水合；
- `PlaybackFeedSession` 管理最新/随机序列；
- 只水合当前窗口并对删除、编辑和筛选变化做增量校正。

### Boundary

- 不改变 OR/AND 语义、随机一轮不重复规则、TDLib 扫描和播放器数量；
- 不直接引入 Paging 3。

### Failure states

- 当前项被删除：选择确定性邻项；
- 水合失败：保留键位置并显示可重试失败；
- 窗口与 generation 不匹配：丢弃迟到结果。

### Proof

- 0/1/10/1,000/100,000 键的确定性基准；
- DAO query plan 与索引测试；
- 随机轮次、删除和编辑回归；
- 峰值内存与 Room 更新耗时不得劣化。

## Stage 25D：缓存写入和 TDLib 事件背压

### Outcome

高频下载进度不会阻塞控制事件；缓存访问时间不会因协程乱序回退；启动不会对全部视频逐条 `getFile`。

### Scope

- fileId conflation/最新快照通道；
- 控制事件与文件进度分离；
- 单缓存元数据 writer，合并并批量 upsert；
- 启动对账分页、预算化、可取消；
- LRU 查询计划和 partial/order-first 索引候选 A/B。

### Boundary

- 不建立第二媒体缓存，不删除正在使用的文件，不改变缓存上限选项。

### Failure states

- writer 失败：内存状态继续单调，进入有限重试并报告脱敏失败；
- logout/代际变化：清空待写队列，旧事件不可进入新账号；
- 对账超时：停止本轮，不阻塞授权和播放。

### Proof

- 高频 10,000 次 updateFile 压力测试；
- 授权控制事件延迟上界测试；
- 时间戳单调性和 logout 隔离测试；
- `EXPLAIN QUERY PLAN` 及 10万 LRU 行基准。

## Stage 25E：播放会话状态机拆分

### Outcome

队列、目标、settle、绑定、预加载和错误恢复可以脱离 Compose、TDLib 和真实播放器进行确定性测试。

### Scope

- 抽出 `PlaybackFeedSession`、`PlaybackTargetCoordinator` 和 typed intents；
- ViewModel 只组合 Repository、会话和播放控制器；
- Screen 拆分 Pager、overlay、controls、metadata、progress 叶节点；
- 不改变生产播放器行为和当前指标。

### Boundary

- 不引入双播放器、不改 buffer、range、ABR、Pager 动画或 UI 视觉。

### Failure states

- stale generation、反滑、快滑、目标删除和错误恢复必须保持现有语义；
- 行为对照不一致时阶段失败，先回退拆分。

### Proof

- golden state-transition tests；
- 现有播放 ViewModel/Compose 测试全部复用；
- 同设备 A/B 指标不得回归超过 5%。

## Stage 25F：单播放器 SampleQueue 真机 A/B

### Outcome

决定现有 `DefaultPreloadManager` 候选是否能以较低成本显著降低首帧长尾。

### Scope

- 只切换 `cvfSampleQueuePreloadEnabled=false/true`；
- 校验动态 NEXT endpoint、512 KiB chunk、HLS/MP4、owner 交接和 sample 命中；
- 补 visible-first-frame 指标。

### Boundary

- Owner Promotion 永久关闭；
- 不改变播放器数量、Pager、质量、网络、缓存前置条件或动态预算。

### Failure states

- FIRST_FRAME 非 100%、安全失败非零、P95 改善不足 15% 或浪费明显上升：保持默认关闭。

### Proof

- 同设备、同队列、相同网络窗口各至少 30 次；
- 正向、反向、快滑、暂停、seek、后台和弱网；
- 完整 Path B 与真机 install/launch smoke。

## Stage 25G：固定双播放器池候选

### Outcome

在单播放器最佳候选之后，证明或否定预解码和备用 Surface 是否仍有值得承担的首帧收益。

### Scope

- 新增 feature flag 和硬上限 2 的 `PlaybackPoolCoordinator`；
- 依次测试 C1 无可见备用 Surface、C2 持久备用 Surface；
- 复用唯一 NEXT 和动态字节预算；
- 增加 codec、PSS、thermal、Surface、音频焦点和可见首帧指标。

### Boundary

- 不创建每页播放器，不并行预测多条；
- 不与新的 SampleQueue/Owner/Buffer/Pager 改动混测；
- 未通过验收前生产默认关闭。

### Failure states

- 按 5.7 节立即降级；任何预算越界或双音频视为安全失败。

### Proof

- 按 5.8 节真机矩阵；
- 至少覆盖一台目标高端机和一台资源更紧设备；
- 30 分钟热态；
- 失败设备自动回退验证。

## Stage 25H：UI 信息架构、响应式与无障碍

### Outcome

频道/标签页面更紧凑、更易扫读；播放 UI 在小屏、横屏和平板上保持清晰并保护视频主体。

### Scope

- 频道页：退出登录降为低频危险操作；浏览视频成为明确主动作；顶部三张大卡压缩为 App Bar/菜单与紧凑状态区；
- 扫描总览默认紧凑，详细统计按需展开；
- 频道行降低高度，选择、置顶、扫描状态形成稳定层级；
- 标签页：长标签截断/展开、计数右对齐、选择态减少大面积高亮；
- 播放页：控制层自动隐藏、scrim 随内容可读性调整、触控区域 ≥48dp、进度和说明分层；
- 320dp、常规手机、横屏、平板/折叠屏使用 max-width 与 pane 规则；
- 文案、颜色、contentDescription、对比度和动态字体集中治理；
- 倒计时和播放 ticker 只更新叶节点。

### Boundary

- 不改变 Telegram/Room/播放器策略；
- 不在视频 Surface 上实时 blur；
- 不用视觉动画掩盖真实加载。

### Failure states

- 字体 200%、超长中文/英文标签、空/错误/加载、手势导航栏、横屏安全区均必须可用。

### Proof

- screenshot golden：light/dark、320dp、手机、横屏、平板；
- TalkBack 语义与触控尺寸检查；
- Compose 重组计数；
- API 36 emulator UI；
- 真机人工视觉与交互验收。

## Stage 25I：R8、启动与 Baseline Profile

### Outcome

缩小非 native 代码/资源并改善首次启动和关键导航，不破坏 TDLib JNI、Room、Hilt 或反射路径。

### Scope

- release 打开 R8 full mode 与资源压缩；
- 从缺失规则和运行证据推导最小 keep rules；
- 生成启动、登录后频道、标签和进入 Feed 的 Baseline/Startup Profile；
- 对比 APK Analyzer、冷启动和 frame timing。

### Boundary

- 不承诺 26–32 MiB；`libtdjni.so` 已形成较高物理下限；
- 不使用 `-keep org.drinkless.tdlib.** { *; }` 之类无证据全量规则作为最终方案。

### Failure states

- JNI/反射/序列化/Room/Hilt 任一 release-only 故障时回退当前阶段并最小化规则。

### Proof

- minified release 全套主机测试；
- 正式签名、权限、备份、ABI、凭证反扫；
- 安装/冷启动/登录恢复/频道/Feed smoke；
- APK 分项体积与 Macrobenchmark 前后对比。

官方依据：[Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)。

## Stage 25J：TDLib 数据库加密

### Outcome

TDLib 本地数据库使用随机密钥保护；密钥损坏或不可读时失败关闭，不静默使用空密钥。

### Scope

- Android Keystore 生成不可导出包装密钥；
- 随机 TDLib database key 经 AES-GCM 包装后保存到 `noBackupFilesDir`；
- 新安装优先启用；
- 旧安装设计两阶段迁移或明确重新登录路径；
- `setDatabaseEncryptionKey` 状态机、失败分类和恢复测试。

### Boundary

- 不在聊天、日志、Room、DataStore 或仓库出现密钥；
- 不把数据库复制到公共/可备份目录；
- 不与播放器或性能重构同阶段实施。

### Failure states

- Keystore 无效、密文损坏、key 不匹配：关闭 TDLib 会话并要求重新认证；
- 不尝试猜测或回退空 key。

### Proof

- throwaway 数据库迁移矩阵；
- 密钥损坏/删除/重启/升级测试；
- backup 与敏感文件扫描；
- 真实账号迁移只有用户明确授权时执行。

## Stage 25K：发布工程与数据生命周期

### Outcome

每次变更都能自动证明架构、测试、安全和发行边界；失效软删除数据可按策略清理。

### Scope

- CI：test、lint、assemble、依赖/凭证/权限/备份/ABI 扫描；
- Gradle dependency verification 与来源记录；
- 校正 wrapper 下载校验策略；
- deleted video/tag cross-ref 的保留期与事务清理；
- 文档与 Release proof 自动生成但不包含敏感内容。

### Boundary

- 不自动 push、发布、签名或删除用户本地数据；
- 清理只作用于明确失效且超过保留期的数据。

### Failure states

- CI 不具备签名凭证时只做 unsigned/static proof；
- migration 或清理计数异常时事务回滚。

### Proof

- clean checkout CI；
- migration/retention DAO tests；
- 依赖哈希和仓库凭证反扫；
- 正式发布仍需人工签名与真机 smoke。

---

## 7. 优先级与预期价值

| 顺序 | 阶段 | 价值 | 风险 | 是否需要真机性能窗口 |
|---:|---|---|---|---|
| 1 | 25A 基线 | 极高 | 低 | 是 |
| 2 | 25B 错误/恢复 | 高 | 中低 | 主要为功能验证 |
| 3 | 25C Feed 窗口化 | 极高 | 中高 | 建议 |
| 4 | 25D 事件/缓存治理 | 高 | 中 | 建议 |
| 5 | 25E 播放状态机拆分 | 高 | 中 | 是，证明无回归 |
| 6 | 25F SampleQueue A/B | 高潜力 | 中 | 必须 |
| 7 | 25G 双播放器池 | 条件高潜力 | 高 | 必须 |
| 8 | 25H UI/响应式 | 高 | 中 | 视觉真机验收 |
| 9 | 25I R8/Profile | 高 | 中 | 必须 |
| 10 | 25J 数据库加密 | 高安全价值 | 高 | 迁移需真机 |
| 11 | 25K 发布工程 | 长期高价值 | 中 | 否/最终 smoke 是 |

不把理论百分比写成承诺。每阶段只有在同口径前后数据完成后，才能写实际收益。

---

## 8. 明确不做

- 不迁移 Flutter/React Native；
- 不替换官方 TDLib；
- 不建立自建媒体代理服务器；
- 不使用 Media3 SimpleCache 复制完整媒体；
- 不创建每页播放器；
- 不重新开启 Owner Promotion；
- 不同时改变播放器池、Buffer、Pager、range 和 ABR 后做不可归因测试；
- 不用全量 `@Immutable`、随手加锁或迁移 DataStore 伪装成性能优化；
- 不使用 alpha/beta/RC 依赖绕过稳定合同；
- 不增加权限、遥测、广告、分析或外部日志服务；
- 不以模拟网络结果代替真实 Telegram/CDN/decoder/Surface 结论。

---

## 9. 推荐的第一个实施阶段

若仓库所有者批准进入下一阶段，建议只启动 **Stage 25A：真实基线与可观测性闭环**。

理由：

- 它不会改变用户行为；
- 能判断 Feed、Room、TDLib、Compose、decoder、Surface 中谁才是当前主要瓶颈；
- 能避免再次出现“理论更快、真机反而更慢”的 Owner Promotion 结果；
- 它是判断单播放器 SampleQueue 与双播放器池谁更值得的必要前置证据。

Stage 25A 完成并汇报后，必须等待仓库所有者明确批准，才进入 Stage 25B。
