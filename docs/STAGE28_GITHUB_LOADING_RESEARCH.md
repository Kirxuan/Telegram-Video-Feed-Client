# Stage 28：GitHub 视频加载方案调研（第三轮，移动数据 #TEST 实测驱动）

> 公开版本说明：测试频道、标签和设备序列号已脱敏。本文保留原阶段验证状态；1.2 的正式发布范围见 [发布说明](RELEASE_1.2.md)。

调研日期：2026-09-11。目的：针对用户指定的实测目标（频道"测试频道（名称已脱敏）"、标签 #TEST、仅移动数据、红米 Note 11 Pro+），解决"新视频首帧慢、大视频加载慢、预加载命中不稳定"三个问题，寻找可验证的最优机制。

本轮与 Stage 26/27 的区别：前两轮以"机制语义"（秒级预算、480p 备用、起播分档）为主；本轮直接对 TDLib 源码做逐层阅读，并结合应用侧 20 次滑动 × 多轮的设备日志做**机制级归因**，得到的是"应用层实际向 TDLib 发送了什么"的第一手证据。

只参考架构与机制并自行实现，不复制第三方代码、不替换 TDLib、不引入新依赖、不引入代理或 Bot API。

## 1. 本轮对比表

| 项目与已阅读依据                                                                                                                                                                                                              | 关键机制                                                                                                                                                                                                                                  | 与 VELORA 的关系                                                                                    | 本轮决策                                                           |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| [TDLib FileDownloader.cpp](https://github.com/tdlib/td/blob/master/td/telegram/files/FileDownloader.cpp)                                                                                                              | 单文件下载在 `do_loop()` 内按资源额度**并行派发多个 part**（不等前一个完成）；part 完成乱序写入，`ready_prefix_size` 只推进到第一个空洞之前；视频类文件挂 `DelayDispatcher`（0.05 s → 0.003 s 指数递减的发起间隔）；`update_downloaded_part(offset, limit)` 会**重设 streaming 区间并取消区间外的 in-flight part** | 证据吻合：设备日志中 `downloaded` 快速增长而 `prefix` 长时间停滞（乱序写入）；应用每次消费推进都调用 downloadFile 会让 streaming 区间不断前移 | **核心依据**。把"应用 → TDLib"的调用从"每消费 256 KiB 一次"降下来，让 part 流水线不被反复重设 |
| [TDLib ResourceManager.cpp](https://github.com/tdlib/td/blob/master/td/telegram/files/ResourceManager.cpp) + [FileDownloadManager.h](https://github.com/tdlib/td/blob/master/td/telegram/files/FileDownloadManager.h) | `max_download_resource_limit_ = 1 << 21`（**非 premium 账号全局下载资源 2 MiB**）；`satisfy_node` 按优先级从高到低分配，`need = estimated_extra`，**任一 worker 未满足即 break，低优先级什么都拿不到**                                                                         | 解释了"备用/预加载在当前播放持续索取时长期为 0 字节"；也解释了多文件竞争时的分配形状                                                   | 采用其语义：预加载的"最小探路"（256 KiB）在低优先级下**不抢占**当前流，只是排队；当前流释放后立刻受益      |
| [TDLib FileDownloadManager.cpp](https://github.com/tdlib/td/blob/master/td/telegram/files/FileDownloadManager.cpp)                                                                                                    | `download(offset, limit)` 的 limit 只决定"下载目标区间"，不改变资源上限；`update_downloaded_part(offset, limit, max_download_resource_limit_)` 把全局资源上限传入                                                                                                 | 说明"把请求 limit 从 4 MiB 加到 8 MiB"本身不会提升资源配额                                                        | 不做"加大 limit"的无效实验                                              |
| [Telegram X TdlibDataSource](https://github.com/TGX-Android/Telegram-X/blob/main/app/src/main/java/org/thunderdog/challeggment/telegram/TdlibDataSource.java)                                                         | 读取线程按媒体时长反推"预载 N 秒的字节数"；缺字节时阻塞等待，不打断已在进行的目标区间                                                                                                                                                                                         | 与本项目"按秒推导字节"一致；也支持"不要反复改写目标区间"的判断                                                               | 维持秒级预算语义；本轮不改预算公式                                              |
| [media3 shortform demo](https://github.com/androidx/media/blob/release/demos/shortform/src/main/java/androidx/media3/demo/shortform/viewpager/ViewPagerMediaAdapter.kt)                                               | `DefaultLoadControl` 起播门槛 500 ms；`setPrioritizeTimeOverSizeThresholds(true)`；托管窗口固定 20 项                                                                                                                                              | 印证"起播门槛是最大的确定性延迟"，且短视频场景允许激进门槛                                                                  | 本轮新增"等待越久、门槛越低"的渐进放宽（有下限），把最坏黑屏时间截断                            |
| [ExoPlayer issue #10597](https://github.com/google/ExoPlayer/issues/10597)（官方回答）                                                                                                                                      | `prepare()` 会**同时**缓冲数据并获取解码器；双播放器意味着双解码器实例；"同一解码器可复用时，播放列表方式更易被库优化"                                                                                                                                                                  | 双播放器池是已验收架构（ACTIVE/STANDBY 静音、20 MiB 上限），本轮不改；但确认"STANDBY 解码器持有"是真实资源成本，STANDBY 的准入与预算不能放松      | 维持池的现行准入；把"预加载"分为两层：256 KiB 探路（轻）与池备用（重）                       |
| [TikTok 预取架构（工程实践综述）](https://www.techinterview.org/post/3233474985/design-tiktok-video-feed-mobile)                                                                                                                  | 预取"下一条的前 500 KB-2 秒"；蜂窝网络限制预取量；玩家池（current/next/prev）；首卡元数据随 feed 返回立即缓冲                                                                                                                                                              | "预取的目标是**首段就绪**而不是整片缓冲"；与 VELORA 的"5 秒预算"相比，**小目标更容易在滑动前完成**                                    | 采用"分层目标"思想：先保证能覆盖 TTFB 的首块（起步 256 KiB），完整 5 秒储备由池备用在其之后推进      |
| [mpv options](https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst)                                                                                                                                     | 秒与字节双上限；起播/重缓冲阈值独立                                                                                                                                                                                                                    | 重缓冲恢复门槛（12 s）保持独立、不随本轮调整                                                                        | 维持现状                                                           |
| [FFmpeg movenc faststart](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/movenc.c)                                                                                                                          | 封装期把 moov 前置减少尾部读取                                                                                                                                                                                                                    | 客户端无法重封装远程文件；但确认"首段读取必须容忍尾部索引"                                                                  | 维持现有 startup range 机制                                          |

## 2. 设备证据（本轮归因的事实基础）

全部来自 `build/reports/opt/`（git-ignored 证据目录），红米 Note 11 Pro+、Wi-Fi 关闭、移动数据、频道"测试频道（名称已脱敏）"、#TEST 标签、随机模式、每轮 20 次前滑、每滑观察 5 s。

1. **设备移动数据的真实带宽 ≥ 4 MB/s**（`curl` 实测 Cloudflare 20 MB 下载 3.99 MB/s），而 TDLib 侧长窗口下载速度只有 ~0.42-0.85 MB/s（file update 增量），**存在 4-8 倍的差距**，说明瓶颈不在物理链路。
2. **每个冷视频的"第一个 64 KiB"需要 600-700 ms**（多视频 `range ready offset=0 waitMs=615-1393`），这段等待在滑动瞬间不可消除，**唯一的消除方式是预加载**（命中案例首帧 32-54 ms，命中率 50%-85% 随缓存变热而提高）。
3. **20 次滑动产生 578 次 `request begin`（对 TDLib 目标区间的改写调用）**：REPRIORITIZE 19、SWITCH 99、MERGE 173、cancel 67，其余为 START。逐条日志分析显示大头不是"正常消费推进"（该路径被 CONTAINED 短路），而是**同文件两个 owner 的区间冲突**：上一次滑动的 `NEXT_PRELOAD` 残余 owner 停留在旧 offset，与新的 `CURRENT_PLAYBACK` owner 的区间被合并进同一个 plan，plan 在两者之间摆动，产生 `cancel result=DISJOINT_SWITCH` 与反复 SWITCH/MERGE，**反复打断 TDLib 的 part 流水线**。
4. **两个 owner 的区间拉锯**：滑动后，旧的 `NEXT_PRELOAD` 残余 owner（offset=2162688）与新的 `CURRENT_PLAYBACK` owner（offset=2424832+）同文件共存；`!active.coversAny(unsatisfied)` 判定因 NEXT 的旧区间而持续成立，触发 SWITCH 与 cancel（日志实例：`41.494 cancel fileId=123389 result=DISJOINT_SWITCH`）。
5. **预加载的"未出首帧"硬阻塞循环**：慢视频（2K/19.5 Mbps）等待自身 2.5 s 启动储备可达 10-16 s（设备实测 `bindToReadyMs=16391`、缓冲 1483 ms 停滞 15 s）；期间 `AdaptivePreloadPolicy` 返回 OFF（`CURRENT_NOT_STABLE`），按 `CLEAR_TARGET_REASONS` 逻辑**下一条预加载被整体清空**，用户滑走后必然是冷启动，形成系统性循环。
6. **大视频的物理边界**：样例视频为 2560×1440、19.5 Mbps（`localBytes=6881280` 对应 2.8 s 缓冲），在 8-32 Mbps 波动的移动链路上**无法保证全程流畅**；能优化的是"更早出画 + 更少中断 + 下一条更快"，不能承诺任意视频秒开。

## 3. 由对比与证据推导的修改（同时推进，可独立回退）

1. **播放 owner 主导下载窗口**（`TelegramFileManager.selectSteeringOwners`）：同文件存在 `CURRENT_PLAYBACK` owner 时，残余的 `NEXT_PRELOAD` owner 不再参与 plan 合并。已下载字节仍留在 TDLib；未读部分由播放请求自身的 read-ahead 覆盖。消除拉锯与 `DISJOINT_SWITCH`。当播放 owner 全部满足（播放数据就绪）时，plan 自动回落到包括 `NEXT_PRELOAD` 在内的全部 owner，预加载恢复驱动。
2. **同值请求不再重发**（`ensureRequestLocked` 的 contained 复用分支）：priority 与 ownerKind 均未变化时不调用 `updateActiveRequestPriority`（该分支当前仅由测试构造启用，生产仍走 13A 取消重建；此改动让候选路径不再产生冗余 published 调用）。
3. **"未出首帧"从硬阻塞降级为有界储备**（`AdaptivePreloadPolicy`）：`CURRENT_NOT_STABLE` 返回 CONSERVATIVE（256 KiB）而不是 OFF。NEXT_PRELOAD 优先级低于一切当前播放优先级，在 TDLib 资源分配中**只使用当前流未使用的容量**；一旦当前流空闲，下一条立刻获得 256 KiB 探路数据，滑动时 TTFB≈0。
4. **渐进式起播门槛**（`VideoPlayerManager.reconcileStartupThreshold`）：未渲染首帧时，门槛从基准值按 500 ms/s 衰减、下限 1000 ms（grace 2 s）。把"慢视频的最坏黑屏"从 10-16 s 截断到个位数秒；重缓冲恢复门槛与暂停恢复路径不受影响（不同常量、不同条件）。

以上四项都有对应的单元测试与既有回归测试看护；"减少调用"与"owner 主导"两项相互独立，可分别回退。

## 4. 不采用与保留的候选

- **提高预加载优先级到 CURRENT 级**：会与当前播放直接竞争 2 MiB 资源，违反"当前流优先"的既有安全边界；保留 `NEXT_PRELOAD` 低优先级 + 让出机制。
- **加大 downloadFile 的 limit（4 MiB → 8/16 MiB）**：FileDownloadManager 源码证明资源上限与 limit 无关；不浪费实验轮次。
- **TDLib session_count 翻倍以放大资源池（×8）**：会改变账户会话形状与风控面，超出优化范畴。
- **整体迁移 `DefaultPreloadManager`**：与既验收割的所有权/保护租约互斥（Stage 27 已否决，维持）。
- **App 层多连接并行下载**：TDLib 单文件下载目标区间是单游标语义，多调用会互相覆盖（FileDownloader 源码），不属于可用杠杆。

## 5. 结论

本轮把"预加载命中率"和"TDLib 调用卫生"作为两个主杠杆：先用**低成本探路预取**解决 600-700 ms 的 TTFB（命中即首帧 <60 ms），再用**下载窗口单一来源 + 冗余调用清除**降低 TDLib 侧的调度震荡，最后用**渐进门槛**兜底最坏等待。所有机制均来自上述源码/官方示例可溯源的行为，不引入任何第三方组件。

所有收益描述均为机制分析与待验证目标，最终以红米真机移动数据多轮实测为准。
