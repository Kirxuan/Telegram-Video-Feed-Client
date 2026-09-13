# Stage 29：播完自动滑入下一条

> 公开版本说明：测试频道、标签和设备序列号已脱敏。本文保留原阶段验证状态；1.2 的正式发布范围见 [发布说明](RELEASE_1.2.md)。

2026-09-11 用户明确授权新增连续播放、GitHub 多方案比较、红米 Note 11 Pro+ 验证。基于包含 Stage 28 优化的既有工作树，正式发行仍为 Stage 24 / 1.1.0；本次产物是本地候选，未发布。

## 阶段合同

- Outcome：当前视频真实播放结束后，自动向上翻一页并播放下一条，复用手动滑动的准备、稳定绑定和备用晋升。
- Scope：`player/.../VideoPlaybackController.kt`、`VideoPlayerManager.kt`、`app/.../VideoPlaybackScreen.kt`、共享 Compose `VideoPlaybackScreenTest.kt`、`player/src/androidTest/.../VideoPlayerManagerIntegrationTest.kt`、本文及 README 文档入口。
- Boundary：不改 TDLib、账号、索引、权限、依赖、缓存预算、起播阈值和双播放器架构；不发布、不提交、不清应用数据。
- Failure states：不支持流式、加载失败维持现有提示/重试，不能被自动跳过而隐藏；后台、暂停、详情展开及手势进行时等待；最新排序最后一条停止，随机排序沿用现有下一轮。后续视频未预备好时仍显示真实加载状态。
- Proof：编译、共享 Compose Robolectric 测试、全量 `test lint assembleDebug --max-workers=1`、API 36 AOSP x86_64 Compose UI、账户无关播放器集成测试、红米安装启动及移动数据实际完成/滑动验证。

## GitHub 多方案对比

以下是本次实际检索并阅读的上游源代码。判断为结合本项目约束的工程推论，不宣称全局理论最优。表中"逐字核验"表示本仓库直接获取并阅读了对应源码/文档原文。

交接后复验（本轮再次直读上游原文，结论与交接状态一致）：`Player.java` 的 `STATE_ENDED` 语义为"The player has finished playing all media"，`MEDIA_ITEM_TRANSITION_REASON_AUTO` 仅在播放列表存在下一条时产生，`REPEAT_MODE_ALL` 才在末条回到首条；`demos/shortform` 的 `ViewPagerMediaAdapter` 中 `DefaultPreloadManagerListener.onCompleted` **只打日志、不翻页**（`Log.w(TAG, "onCompleted: " + mediaItem.mediaId)`），其托管窗口为固定 20 项、预载档次按 `abs(rankingData - currentPlayingIndex)` 分档。这三点共同确认：上游示例不提供"播完自动翻页"，本功能的翻页所有权必须落在 Feed/Pager 侧。

| 方案与直接来源 | 核验方式 | 特点 | 本次取舍 |
|---|---|---|---|
| [Media3 Player 原生播放列表](https://github.com/androidx/media/blob/release/libraries/common/src/main/java/androidx/media3/common/Player.java) | 逐字核验 | 播放列表内单项播完自动过渡下一条（`MEDIA_ITEM_TRANSITION_REASON_AUTO`），只有整表播完或列表为空才进入 `STATE_ENDED`；`REPEAT_MODE_ALL` 首尾循环。单条列表因此仍以 `STATE_ENDED` 表示"该条播完" | 可行的重构方向，但当前 Feed 负责复合键、随机轮次和窗口水合，播放器负责 TDLib 资源与所有权。迁移需要重做队列及所有权交接以换取播放器内转场，而本功能要求"翻页动画可见且每页保持独立身份"，无证据显示额外收益，因此不采用 |
| [Media3 shortform 示例](https://github.com/androidx/media/blob/release/demos/shortform/src/main/java/androidx/media3/demo/shortform/viewpager/ViewPagerMediaAdapter.kt) | 逐字核验 | ViewPager2 + PlayerPool + `DefaultPreloadManager`；自定义预载档次（当前项±1 预载 1000ms、±3 预载 1000ms、其余 cached 5000ms），维护 20 项窗口、双侧增删。**该示例本身没有"播完自动翻页"**：翻页由用户驱动，播放完成只记日志 | 采用其"邻近项预载档次"与"有限窗口"思想（本项目已有等价的唯一下一条预载与 5 秒预算）；不采用每页 PlayerView/离屏 surface 的池方案，本应用保持单 ACTIVE PlayerView + C1 备用晋升 |
| [Compose PagerState](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/pager/PagerState.kt) + [Media3 Player 结束状态](https://github.com/androidx/media/blob/release/libraries/common/src/main/java/androidx/media3/common/Player.java) | 逐字核验 + 设备探针 | 真实 ENDED 与可取消 `animateScrollToPage` 组合；动画经正常 target/settled 状态通道驱动既有准备/绑定管线 | **采用**：单一队列/绑定路径，300ms 翻页动画，用户拖动可打断，不新建播放器。另经 Robolectric 探针证实：取消中的 `animateScrollToPage` 会停在任意半格位置且不会自动回弹（见下"中断安全"） |
| [SmallDY 仿抖音短视频（社区实践）](https://github.com/RogerIn1900/SmallDY) | 逐字核验（README/特性说明） | `Player.STATE_ENDED` → `pagerState.animateScrollToPage()` 平滑切换；有 `onVideoEnded` 回调、播放位置记忆、预加载下一个播放器实例；仅当前页播放 | 与本方案同族，证明"真 ENDED 驱动 Pager 动画"是社区主流做法；其播放器按页持有且无 TDLib 区间请求约束，本项目复用双播放器池与备用晋升，不照搬其资源模型 |
| [compose-reels 库](https://github.com/manjees/compose-reels) | README 核验 | 池化 ExoPlayer（默认 7 个、preloadCount=2）、双向预载、磁盘缓存 150MB；完成事件仅暴露 analytics（`onVideoCompleted`），未文档化自动翻页 | 参考其预载/池配置面；不采用：池规模远大于本项目有界双播放器，且不解决 TDLib 占用与随机轮次问题，引入新依赖也不符合仓库稳定依赖边界 |
| 按 position/duration 定时翻页（与上述 Player 状态契约对照） | 逐字核验（Player.java 状态语义） | 看似简单，但 duration 可未知或与索引时长不同，暂停/倍速/缓冲使墙钟不等于播放进度 | 不采用；实际 ENDED 才成立，READY/isPlaying=false/进度到达索引时长都不能冒充完成 |

## 实现与边界

- `VideoPlayerSnapshot.hasEnded` 仅由通过 binding token 校验的 ACTIVE Media3 状态回调置位；新绑定/加载/失败/释放、READY 和 seek 清除。STANDBY 的回调不能触发自动切换。
- 原有 ViewModel 状态流自然传递新增字段，无单独计时器、命令队列或额外媒体解析。
- Pager 对当前完成键只发起一次动画。生命周期必须 RESUMED，当前稳定页必须与完成复合键一致，并且没有暂停、详情或手指/滑动占用。
- UI 重组和进度变化不会重复翻页；离开页面取消协程；手动滑动优先。全屏允许自动前进，但仍禁止原有全屏手动 Pager 拖动。
- 结束后 seek 回片中会恢复进度采样，避免播放器继续播放而进度条停住。
- 单条随机轮次的下一次进入会复用同一绑定；Media3 在 ENDED 下只调用 `play()` 无法重播，因此先回到开头，缓冲复用机制不变。

### 中断安全（本轮新增）

Robolectric 探针（拖动时钟到动画中途后取消协程，保存于 `build/reports/continuous/probe-cancel.log`）证实：**被取消的 `animateScrollToPage` 会停在任意半格位置，既不完成也不回弹**（探针 1：offset 0.421 定格；探针 2：settled=1 但 offset 仍为 -0.043）。因此"发起动画前就把完成事件标记为已消费"的写法在生命周期中断（例如视频临近结束时切后台）会把 Feed 永久留在半格滚动状态。

当前实现改为：

- 目标页在完成事件首次满足条件时**锚定**（`targetPage = settledPage + 1`），中断后的重试总是完成同一目标，不会越过下一条；
- 完成标记只在动画真正返回后置位；协程被取消时按 `lifecycle` 状态区分——仍在 RESUMED（用户手势或队列变化接管）则消费标记、尊重手动控制；跌出 RESUMED（生命周期中断）则保留未消费状态，回到前台后由同一个 `repeatOnLifecycle` 重启块续播过渡；
- 重试路径重新校验目标页边界后才动画，已越过吸附中点的情况只会把残余偏移吸附回目标页。

对应回归测试（`VideoPlaybackScreenTest`）：

- `lifecycleInterruptionMidAdvanceFinishesTheSameTransitionOnResume`：吸附中点前中断，恢复后必须完成为第 1 页且 `currentPageOffsetFraction == 0`；
- `lifecycleInterruptionAfterSnapMidpointSettlesTheAdvanceWithoutSkipping`：越过吸附中点后中断，恢复后必须吸附回第 1 页（不得跳到第 2 页）且偏移归零。

两项测试均通过"消费前置"突变验证：在把消费标记提前到动画之前的对照组中，两项测试分别以定格 offset 0.077 / -0.043 失败；恢复修复实现后全部通过（证据 `build/reports/continuous/mutation-check*.log`、`verify-interruption-fixed.log`）。

## 验证记录

原始证据保存在 git-ignored 的 `build/reports/continuous/`。测试手机为红米 Note 11 Pro+（`REDACTED_REDMI_SERIAL`，21091116UC / Android 13），**Wi-Fi 关闭、移动数据（5G NR, ccmni1）**，保留既有账号与索引，未清应用数据。频道"测试频道（名称已脱敏）"、随机排序。

### 构建与自动化

- 全量 `test lint assembleDebug`：**BUILD SUCCESSFUL**（lint `abortOnError=true` 无告警；`:app:assembleDebug` 产出 APK）。
- `:app:testInstrumentationUnitTest --tests "…VideoPlaybackScreenTest"`：**78/78 通过、0 失败、0 错误**（含 13 项连续播放用例）。
- 本次新增两项点补丁（见下"本轮硬化"）后，上述两项均复跑通过。

### 真机行为观测（本轮关键证据）

自动切换**在真实设备上成立**：`cvf2_s` 观测窗口内记录到连续 2 次"播完 → 自动切下一条"，无手动操作。

| 序 | 播完消息 ID | 播完位置 | 下一条消息 ID | 切换后 `bindToReadyMs` | 切换后 `readyToFirstFrameMs` | `gestureToSettleMs` |
|---|---|---|---|---|---|---|
| 1 | 58435043328 | 83.6 s（= 结束） | 54079258624 | **142 ms** | 31 ms | 347 ms |
| 2 | 54079258624 | 16.9 s（= 结束） | 56480497664 | **133 ms** | 1 ms | 388 ms |
| 3 | 56480497664 | 自动重启后被手动打断 | 12327059456 | 2359 ms（冷起） | — | 630 ms |

关键点：

- 每条一旦播到末尾即产生 `ended chatId=… messageId=…`，并在 **0.6–0.9 s 内**完成下一条的绑定与起播；日志中 `poolPromoted=true`、`playerInstances=1`，证明复用既有 STANDBY 晋升路径而非新建播放器。
- 第 1 条播完时 `standbyGate[budgetBytes=0 … safe=false]`（备用未就绪），第 2 条播完时 `budgetBytes=1218602 safe=true`——说明切换走的是同一条常规准备/晋升管线，命中与否由既有的 3 秒准入门槛决定，没有被自动切换逻辑绕过或放宽。
- 手动滑动等价性同时成立：3 次前滑命中 3 个不同 `messageId`（12327059456 / 86158344192 / 15259926528），`direction=FORWARD`，与自动切换共用同一路径。
- 观测到的 `state=ENDED`、`isPlaying=false`、`summary … state=ENDED` 与自动切换一一对应，`hasEnded` 只在真实播完后置位，未出现"进度到达时长即误判完成"。

### 本轮硬化（相对交接状态的两处点补丁）

1. `retireCurrentBindingForReplacement()` 显式 `hasEnded=false`：被替换的绑定不把完成事件带进下一条；晋升路径下引擎跨绑定存活，必须在换绑时清干净。
2. `resume()` 失败关闭：`hasEnded` 且 `seekTo(0)` 未能清除完成标记时（不可 seek 的已结束条目）直接返回，不谎报"已恢复播放"。

两项均为防御性加固，不改变正常路径行为；不影响上面实测的 133–142 ms 热切换。

### 连续稳定性 soak（无操作连续自动切换）

清空 logcat 后静置约 5.5 分钟不做任何触摸，再记录到 **3 次连续自动切换**，累计本轮共观测 **5 次连续自动切换**，`logcat -b crash` **0 行**（无崩溃、无 ANR），进程存活于前台，`TOTAL PSS 262 MB`（Java Heap 42.7 MB / Native Heap 67.9 MB）、`Views: 54`、`Activities: 1`。

| 播完消息 ID | 播放时长 | 下一条消息 ID | 端到端（`gestureToTerminalMs`） | `bindToReadyMs` |
|---|---|---|---|---|
| 9924771840 | 90.6 s | 31664898048 | 1479 ms | 7770 ms |
| 31664898048 | 66.7 s | 85108719616 | **520 ms** | **195 ms** |
| 85108719616 | 67.0 s | 24444403712 | 783 ms | —（`bindToFirstByteMs=42`） |

- 三次自动切换的消息 ID 互不相同，链式前进未回绕、未重复、未跳过。
- 唯一一次 7770 ms 是**链路受限**而非切换逻辑：该会话 7 分钟内累计 `rebufferCount=21`、`totalRebufferMs=329283`、单条 `rebuffer60Count=3`；其 `gestureToSettleMs=311`（翻页与绑定依旧快速），瓶颈在数据到达。链路恢复后立刻回到 520 ms / 195 ms 水平。
- 内存与视图数在连续切换后保持有界（`playerInstances=1`），未见随切换次数单调增长的迹象。

### 未完成/边界

- 未做 API 36 emulator 与 `connectedDebugAndroidTest` 复跑（本轮以真机为准）。
- 未对随机轮次"越过 upcoming 边界后进入下一轮"做长时间设备观测，该路径由 `randomCompletionUsesUpcomingRoundAtBoundary` 等 Robolectric 用例看护。
- 本轮首条为手动点击进入，起播时网络处于冷启动（rebuffer 18.9 s、`bindToReadyMs=4571`）；这是当时链路状态，不是连续播放逻辑的度量。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，46,837,774 B，SHA-256 `BF11363FAC3655AC5314E460E083C19EA2F9F4AAB100817544C8CDB2754A145C`，构建于 2026-09-11 14:20（含上述两处硬化）。本候选**未提交、未发布**，正式发行基线仍为 Stage 24 / 1.1.0。
