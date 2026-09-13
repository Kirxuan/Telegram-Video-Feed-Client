# Stage 27：GitHub 视频加载方案对比（第二轮，移动数据秒开）

调研日期：2026-09-10。目的：为“移动数据下近乎秒开、否则逼近最优”寻找可验证的最优机制。只参考架构与机制并自行实现，不复制第三方代码、不替换 TDLib、不引入新依赖、不引入代理或 Bot API。

本轮问题定义与 Stage 26 不同：Stage 26 关心“能否准备成功”，本轮关心“**首帧与真正可连续播放的耗时**”，并把预算从字节改为秒。以下每一项都给出“机制—适用条件—VELORA 决策”。

## 1. 对比表

| 项目与已阅读依据 | 关键机制 | 适用条件 | VELORA 决策 |
|---|---|---|---|
| [Media3 DefaultPreloadManager](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:media3/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.java)、[TargetPreloadStatusControl](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:media3/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/TargetPreloadStatusControl.java)、[PreloadStatus](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:media3/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.java) | 预加载目标用**时长**表达：`PreloadStatus.specifiedRangeLoaded(durationMs)` / `specifiedRangeCached(durationMs)`；`PreloadMediaSource` 保留 SampleQueue，`getMediaSource()` 把**同一个 MediaSource 实例**交给播放器，避免二次解析与解码器重建；预加载与播放共享 `LoadControl`、`BandwidthMeter`、`RenderersFactory`；滑动窗口用 `addMediaItems`/`removeMediaItems` 控制内存 | 动态 Feed；需要按“秒”而不是按“字节”分配预算；同一 MediaSource 需要在线程同一 Looper 上交接 | **采用其语义，不采用其实现**。仓库 `Media3SamplePreloadController` 已存在该 API 的接入（`specifiedRangeLoaded(targetSeconds*1000)`），但启用它会与双播放器池互斥并改变已验收的所有权/预算边界。本轮只吸收“**预算是秒**、**窗口有界**、**预加载与播放共享预算观**”三条语义，落到现有池的预算控制器 |
| [Media3 PreloadConfiguration](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:media3/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java) | 播放列表场景直接 `preloadConfiguration = PreloadConfiguration(targetPreloadDurationUs = 5_000_000L)`，即官方示例默认 **5 秒**；仅在当前播放没有加载请求时才启动预加载，天然不与当前播放抢带宽 | 顺序可预测的播放列表；官方示例给的是 5 秒 | 采用“**5 秒是官方默认预加载时长**”这一锚点，作为本轮秒级预算的目标值；并保留“当前播放加载优先”的既有门控 |
| [media3 shortform demo](https://github.com/androidx/media/blob/release/demos/shortform/src/main/java/androidx/media3/demo/shortform/viewpager/ViewPagerMediaAdapter.kt) | `DefaultLoadControl` 参数固定为 **min 5000ms / max 20000ms / bufferForPlayback 500ms**，并 `setPrioritizeTimeOverSizeThresholds(true)`；`TargetPreloadStatusControl` 对 ±1 与 ±2..±3 均返回 `specifiedRangeLoaded(1000L)`，更远返回 `specifiedRangeCached(5000L)`；托管窗口固定 20 项，靠近边缘 2 项时整体滑动 4 项 | 短内容垂直 Feed；起播门槛取 500ms 是官方示例值 | 采用“**起播门槛可以远低于 2.5 秒**”的结论，但 500ms 属于无弱网保护的激进值；本项目改为**按吞吐余量分档**（见 §3），在有 2 倍余量和低首字节延迟时降到 800ms |
| [Telegram X TdlibDataSource](https://github.com/TGX-Android/Telegram-X/blob/main/app/src/main/java/org/thunderdog/challegram/telegram/TdlibDataSource.java) | 读取线程在缺字节时 `CountDownLatch.await()`；`calculateDownloadLimit()` 在 `OPTIMIZE_CHUNKS` 下**按媒体总时长与码率反推需要预加载的字节数（例如预加载 10 秒）**；读取位置映射为 TDLib `addCloudReference(priority, offset, limit)`；可读性只看连续前缀，必要时查询 `GetFileDownloadedPrefixSize` | 官方 TDLib 是唯一下载通道；按播放位置优先 | 采用“**按秒推导字节**”的做法（与本项目用户诉求一致，且来自真实 Telegram 客户端）；确认本项目已有的“连续前缀可读 + 区间合并 + 优先级”方向正确，不引入第二套下载游标 |
| [Telegram Android FileLoadOperation](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java) | 分片下载，按播放位置优先调度分片，并用未完成区间状态表避免重复下载 | 直接使用 MTProto 的自有下载器 | 只借鉴“播放位置优先”；不接入其下载器替代官方 TDLib |
| [eatshots_video_player（原生层优化说明）](https://pub.dev/packages/eatshots_video_player) | 明确把 `DefaultLoadControl` 起播门槛从 2.5 秒降到 **500ms**；维持**最多 3 个控制器（上一/当前/下一）**并复用解码器、只替换数据源；用串行更新队列 + 快速滑动跳过中间源来消除解码器竞态；预取量按网络档位给定（WiFi 1.5MB / 4G 384KB / 3G 128KB） | 短视频垂直流 | 采用“**起播门槛是主要延迟来源**”和“**复用解码器、串行化切换**”两条结论（本项目已有 owner/generation 串行化）；其**按字节**分档不予采用，本轮按秒；其 3 播放器上限不采用，本项目合同固定两实例 |
| [mpv options](https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst) | 网络前瞻同时受**秒**与**字节**双上限约束；**起播阈值与重缓冲阈值互相独立**；用滞回避免反复启停下载 | 通用播放器 | 采用“秒主、字节是硬上限”和“起播/重缓冲阈值分离”；本项目重缓冲门槛保持不降 |
| [aria2 manual](https://github.com/aria2/aria2/blob/master/doc/manual-src/en/aria2c.rst) | `split` 多连接；`inorder`/`geom` 让文件前部优先 | 普通 HTTP 文件 | 不采用。Telegram 文件不是 HTTP URL，对同一 fileId 增加连接/独立下载游标会与 TDLib 单游标冲突 |
| [FFmpeg movenc / muxers](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/movenc.c) | `faststart` 在封装期把 moov 前置，减少起播前读取文件尾部 | 封装阶段 | 不采用（客户端无法重新封装远程文件）；但确认“必须允许 Extractor 按需读取索引区间”，本项目已有稀疏尾部读取 |
| [GSYVideoPlayer](https://github.com/CarGuo/GSYVideoPlayer) | 多内核、边播边缓存 | HTTP 场景 | 不引入其框架；其 HTTP 缓存不适用于 TDLib 内部文件 |
| [justin-taylor/video_player_feed](https://github.com/justin-taylor/video_player_feed) | 用 ExoPlayer `CacheDataSource` + 协程预取下一段到磁盘缓存 | HTTP + 磁盘 Cache | 不引入第二套完整文件缓存（与现有 TDLib 缓存重复） |
| [ExoPlayer DefaultLoadControl 文档](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:media3/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java) | `bufferForPlaybackMs` 决定 `playWhenReady` 后多久真正出声；`bufferForPlaybackAfterRebufferMs` 决定重缓冲后的恢复门槛 | 通用 | 采用“起播门槛是**可调**的第一延迟来源”；重缓冲后门槛不动 |

## 2. 关键推论

1. **起播门槛是移动数据下最大的确定性延迟。** 官方 shortform 用 500ms、eatshots 明确写到“从 2.5s 降到 500ms”，本项目此前多次实测 `bind→实际可播放 P50 3431ms / P95 19134ms`，与 2500ms 静态门槛高度一致。把门槛改成“**有依据地降到 800ms**”是收益最直接、风险最可控的一步。
2. **预算是“秒”，字节只是上限。** Media3 的 `PreloadStatus` 只接受时长；Telegram X 用“时长 + 码率”反推 limit；mpv 用秒与字节双限。三者一致。用户提出的“备够 5 秒、大视频自然多分配、上限 20MiB”与官方 API 语义一致，本轮采纳。
3. **5 秒有官方锚点。** `PreloadConfiguration` 示例值就是 5 秒，高于 shortform 的 1 秒，因为本项目是较长视频且网络为移动数据，需要更深的储备。
4. **低清备用是命中率的决定性变量。** 备 3 秒原画约 10MB、备 480p 约 0.6MB，在同样的字节预算下低清能准备的时间长得多的内容，且 480p 解码更快。这与 eatshots“复用解码器”的结论同向：**越便宜的目标越容易在滑动前准备完**。
5. **不存在可由源码比较证明的全局最优。** 蜂窝往返、服务端吞吐、源文件结构（moov 位置）都不是应用单方面可消除的。可行目标是消除确定性浪费，而不是承诺任意视频秒开。

## 3. 由对比推导出的可执行模型

记当前视频瞬时码率为 `p`（bit/s），观测到的较快/较慢吞吐为 `d_fast`/`d_slow`，净余量 `R = min(d_fast, d_slow) / p`。若 `R > 1`，缓冲随时间增长，起播所需缓冲只用于覆盖**吞吐波动**而非覆盖全部播放时间，因此：

| 条件 | 起播门槛 | 理由 |
|---|---:|---|
| 未知码率/时长/大小，或无可信吞吐，或 TTFB P90 > 500ms | 2500ms | 与既有保守基线一致，弱网不冒险 |
| 已知且 `R ≥ 2.5` 且 TTFB P90 ≤ 350ms | **800ms** | 缓冲每秒净增 ≥1.5 秒，800ms 可覆盖一次明显的瞬时抖动 |
| `R ≥ 1.8` | 1200ms | 余量充足但更低 |
| `R ≥ 1.3` | 1800ms | 余量有限，保留缓冲 |
| 其余 | 2500ms | 不冒险 |

这条规则把“有 800ms 就先播”从直觉变成**可判定条件**，并且当条件不满足时自动退回 2500ms，不会在弱网制造重缓冲。重缓冲恢复门槛保持不降。

同时，备用预算的准入从“当前缓冲 ≥ 8 秒”下调。依据：TDLib 侧 `NEXT_PRELOAD` 的优先级为 8，而 `CURRENT_CONTINUATION/SEEK/STARTUP` 为 24/30/32，**备用请求在协议层就不可能抢占当前播放的数据**；加上备用请求本身受 20MiB 与 5 秒双重上限约束，因此把准入门槛改为“当前缓冲 ≥ 3 秒且缓冲未下降”，可以在不牺牲当前播放的前提下显著提高准备命中率。原 8 秒门槛被确认为移动数据下“READY 命中长期为 0”的主要原因之一。

## 4. 本轮不采用但保留的候选

- **整体迁移到 `DefaultPreloadManager`**：理论上更优（单解码器、同一 MediaSource 交接、共享带宽估计）。不采用的原因：与已验收的双播放器所有权令牌、字节预算、缓存单写入器、保护租约边界互斥，属于架构替换而非优化，风险与工作量都远超本轮范围。若未来要做，应作为独立阶段并保留现有池作为回退。
- **启用 `SAMPLE_QUEUE_PRELOAD_ENABLED`**：与上一条同因，且构建配置已强制其与播放器池互斥。
- **按网络档位的字节预取（eatshots 式）**：与本轮“按秒”结论冲突，且会重新引入“低码率视频被少备”的问题。

## 5. 结论

本轮采用：**秒级预算（目标 5 秒）+ 20MiB 硬上限 + 480p 低清备用 + 分档 800ms 快速起播 + 备用准入下调到当前缓冲 3 秒**。四项都有上方官方或真实 Telegram 客户端的机制依据，且都可以用真实播放器回归与红米移动数据实测证伪。所有收益描述均为机制分析与待验证目标，不是已完成的实测承诺。
