# Stage 26：GitHub 视频加载方案对比

调研日期：2026-09-10。比较目的是缩短首帧关键路径，不是最大化整文件下载速度。只参考架构与机制，未复制第三方实现、替换 TDLib 或增加依赖。

| 项目与已阅读依据 | 机制与适用条件 | VELORA 决策 |
|---|---|---|
| [AndroidX Media3 shortform](https://github.com/androidx/media/tree/release/demos/shortform)、[ExoPlayer](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java) | 复用播放器、预先准备媒体源；准备样本与呈现首帧是不同阶段 | 复用现有固定两实例；提前准备唯一下一条，交接时保留来源、代次和所有权。现有依赖为 1.10.1，不为追新升级 |
| [TDLib 固定源码](https://github.com/tdlib/td/blob/022d60202e446ad1287b9fb68e687c8a0760788b/td/generate/scheme/td_api.tl)、[downloadFile 合同](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1download_file.html) | offset/limit 定位下载；新的不同区间请求会影响已有下载；localFile 的连续前缀才是可读依据 | 同一文件由现有调度器合并相邻区间与更新优先级，保留缓存洞检查；不同时为同一文件乱发多个独立下载 |
| [Telegram X TdlibDataSource](https://github.com/TGX-Android/Telegram-X/blob/main/app/src/main/java/org/thunderdog/challegram/telegram/TdlibDataSource.java) | 在播放器读取线程等待可用字节，按连续可读前缀返回，必要时查询偏移处的可读长度 | 参考“读取等待与网络前瞻分开”的设计；不复制 GPL 实现，不引入另一套文件缓存 |
| [Telegram Android FileLoadOperation](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java) | 按播放位置优先调度下载分片，使用随机访问文件与未完成区间状态 | 借鉴播放位置优先；该客户端直接使用 MTProto，不能把其下载器接入本项目替代官方 TDLib |
| [aria2 手册](https://github.com/aria2/aria2/blob/master/doc/manual-src/en/aria2c.rst) | split 多连接；inorder 优先文件前部，geom 前部优先并扩大分片间距；通常直接按偏移写文件 | 借鉴“即将需要的数据优先、连接与请求复用”。Telegram 文件不是普通 HTTP URL，增加 aria2/代理或等完整下载后合并会扩大关键路径 |
| [FFmpeg movenc](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/movenc.c)、[muxers 文档](https://github.com/FFmpeg/FFmpeg/blob/master/doc/muxers.texi) | faststart 在封装阶段将 moov 索引前置，减少播放前读取尾部的需要 | 源文件结构影响首帧；客户端不能重新封装所有远程视频来改善首次加载，必须允许 Extractor 按需读取索引区间 |
| [mpv 缓冲文档](https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst) | 网络前瞻按秒与字节双重限制；起播阈值和重缓冲阈值独立；滞回避免下载反复启停 | 采用有界准备和当前播放优先；不能仅将首播缓冲设零就宣称优化成功，必须检查重缓冲 |
| [GSYVideoPlayer](https://github.com/CarGuo/GSYVideoPlayer) | 多播放内核、边播边缓存及列表播放封装 | 不引入完整 UI/播放器框架，现有 Media3+TDLib 边界更直接；HTTP 缓存机制不适用于内部 TDLib 文件 |

## 关键推论

冷首帧至少需要定位媒体、取得必要容器信息与首个可解码样本、初始化解码器并提交到可见 Surface；蜂窝网络往返、服务端吞吐和源文件结构都不是应用单方面可消除的。因此不存在可由源码比较证明的所有网络/视频全局最优解。

双播放器可把下一条的媒体解析和解码准备移到用户观看当前条的时间内。准备命中时，切换主要剩下目标校验、Surface 交接和显示提交；未命中时仍需真实网络读取。采用固定两实例、有界下载、取消与资源降级，优先消除当前实现中的确定性浪费，再以真机观测比较 C1/C2。

## 已定位的项目问题

1. 移动数据开启选项未传入备用预算，预算层无条件拒绝计费网络，播放器层又要求非计费 Wi-Fi。
2. `pauseForPageTransition` 在每个手势开始时丢弃并释放备用播放器，已完成的准备无法晋升。
3. 正常下一目标变化也会释放池槽位，增加 ExoPlayer 构造/释放开销。
4. 过去首帧超过 650ms 会永久阻挡当下仍有充足缓冲的备用准入，容易造成“越慢越不预备”的反馈。
5. 基准脚本将播放控制自动隐藏、选择器收起和 UIAutomator 的短暂不可观测当成离开播放页；修正识别并保留失败尝试，不把脚本错误计为首帧改善。
6. AUTO 的吞吐估计进入播放身份，实际清晰度未改变也会撤销下一条；将网速采样与播放身份分开，仅实际表示变化才重新准备。
7. 首个 DataSpec 必须等满 256KiB 才交给解析器；首段最低可读窗口改为 64KiB，持续下载的前瞻仍独立保留，后续滚动窗口不缩小。
8. 备用源数据失败会被当成硬件失败，禁用整个会话的双播放器；数据/解析错误改为仅放弃当前目标，保留下一目标准备能力。
9. 手机日志是循环缓冲；长轮测试必须累积已观测事件，防止覆盖早期首帧与错误样本。失败轮不计作性能通过。
10. 已显示首个静止画面不代表 Media3 已 READY。移除无条件补写 READY 的旧计时逻辑，新增实际可播放事件与单次 rebuffer 事件；Stage 26 正常切换验收同时等待首帧和实际 READY。
11. 备用播放器原来仅缓冲 500ms 就允许 READY，晋升后发生过 175–460ms 即停顿。统一采用前台起播门槛，备用时间上限由 1s 调整到 3s（内存/网络上限不变）。[Media3 实现](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java) 还允许“不再加载且解码器有数据”直接 READY，所以晋升处额外检查可播放缓冲，原实例继续前台加载，够用后才发声；已完整缓冲的短片直接放行。该机制避免把预算耗尽导致的 READY 当成充分预热。
12. 暂停后 seek 清除首帧标记，但复用画面时 Media3 不保证再次发出首帧回调，界面可能永久加载且失去恢复按钮。保留同一绑定的已有画面，在 seek 回到 READY 后解除 seek 风险，真实播放器及红米操作均复现并验证修复。
13. 额外长暂停中发现旧构建堆耗尽。时间优先策略达到一定样本字节数后仍继续加载的风险已用真实 DefaultLoadControl/Allocator 回归复现；当前样本增加 32MiB 停止加载阈值，达到阈值允许有数据的前台播放继续，防止等待更多数据与停止加载相互死锁。阈值约束加载决策，不等于进程内存上限，单个在途样本可能越过它；不能据此宣称所有 OOM 已被证明消除。

现有 TDLib 同区间请求仅改优先级的 `reuseContainedActiveRequest` 也被重新检查。仓库 [Stage 13B 记录](STAGE13B_PRELOAD_OWNER_PROMOTION.md) 已有真实负收益数据，因此本轮没有为了“更多并发”直接重启该关闭候选；使用既有区间合并/读取前瞻并修复明确的准备失效。未来若重新评估，应单独隔离变量，不能将 aria2 多连接逻辑直接套在 TDLib 单文件下载游标上。

实现、命令与移动数据结果以 [Stage 26 记录](STAGE26_MOBILE_DUAL_PLAYER.md) 为准；本文件中的收益描述均为机制分析，不是实测承诺。
