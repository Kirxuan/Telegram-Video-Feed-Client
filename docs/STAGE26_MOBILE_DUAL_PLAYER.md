# Stage 26：移动数据双播放器首帧优化

日期：2026-09-10；用户明确授权双播放器优化、GitHub 方案比较、更新指导文档及红米 Note 11 Pro+ 移动数据测试。工作树原有 Stage 25 改动保留；不自动提交、推送或发布。

## 阶段合同

- Outcome：当前播放期间有界准备唯一下一条，滑动开始保留准备，最终目标一致时交接双播放器。
- Scope：`VideoPreloadPolicy.kt`、`NextPreloadBudgetController.kt`、`VideoPlayerManager.kt`、`ReusablePlayerLifecycle.kt`、`TelegramMediaDataSource.kt`、`Media3SamplePreloadController.kt`、`VideoPlaybackViewModel.kt`；相关 app/player/domain 与真实播放器测试；`run-swipe-first-frame-benchmark.ps1`、`SwipeFirstFrameBenchmark.psm1` 及其测试；AGENTS/README 与当前架构/产品/验收/开发指导文档。
- Boundary：最多两个播放器、只有 ACTIVE 发声；只用官方 TDLib、唯一私有媒体缓存、现有质量选择；不自动清账号/索引，不发布版本，不测 Wi-Fi 加载速度。
- Failure states：断网、用户关闭预加载、首帧/seek/重缓冲、内存/存储/热压力、目标/账号/质量失效、备用解码失败必须停止无效工作；保留显式加载、失败和重试。
- Proof：定向 domain/player 回归；完整 test/lint/assembleDebug；现有模拟器 Compose/播放器 Proof；红米明确 serial 安装启动、移动数据全部尝试与 READY 子组、重缓冲/PSS/帧统计。

## 当前进展

### 用户追加授权：3 秒 / 480p / 快速起播

- Outcome：唯一 NEXT 以实际缓冲 3 秒为目标；移动数据新增请求最多 10MiB，准备真实 480p 版本并原样晋升。
- Scope：NextPreloadBudgetController、VideoQualitySelector、VideoPlaybackViewModel、VideoPlayerManager 及对应 domain/app/player 回归、AGENTS/README。
- Boundary：不转码、不伪造低清 URL，不增加播放器；显式原画/720p 选择仍保留。800ms 只在低清、吞吐有余量且首字节延迟足够低时启用；弱网或未知条件保留 2500ms，重缓冲门槛不降。
- Failure：不存在低清版本时退回合法源；10MiB 内不足 3 秒不得宣称已备够；当前播放风险和设备压力仍停止备用工作。
- 区分估计与硬限：码率估计用于预算诊断；池 DataSource 以档位硬限保护所有资源的实际请求，Media3 以实际缓冲 3 秒停止。备用压缩样本阈值同步从 1MiB 提高到 10MiB，当前仍为 32MiB，均非进程内存硬上限。
- Proof：预算与选源回归、真实 Media3 起播/停止边界、完整主机及 Compose Proof、红米移动数据实际可播放/首帧/重缓冲分别报告。新策略尚未验证，以下历史结果不代表它。

上一内存修复构建的长暂停观测已完成：372 秒、9 次快照，PSS 从 252543KiB 收敛至 241420KiB；当前进程 crash=0，MainActivity 始终 resumed（redmi-memory-soak.json）。该有限样本不证明任意素材都不会 OOM。

追加策略主机检查：首轮 JVM 原生 malloc 失败（duration480-host-memory-failure.txt），属于 Windows 主机资源故障；关闭本轮模拟器后，原命令完整重跑成功（duration480-full-proof-retry.log，49s，668 tasks），没有删除检查或关闭 lint。JUnit XML 合计 1697 次执行（含构建变体重复），失败/错误/跳过均为 0。API 36 Compose 101/101；真实 Media3 C1 执行 9 个通过、1 个 C2 条件跳过，C2 10/10 通过。800ms 与重缓冲分离、3 秒/10MiB 停止边界均有真实播放器验证。

本次候选 `velora-stage26-duration480.apk` SHA-256：`22E4C95E8C9EB64546A2E497BBD6D4C625761CE4CDF713250A84ADE3C691CC73`。红米覆盖安装成功，应用冷启动 1484ms（非视频加载成绩）。移动数据采样另行记录，不用主机/模拟器通过代替流量成绩。

- GitHub 8 个项目的对比和采用理由见 [调研记录](STAGE26_GITHUB_LOADING_RESEARCH.md)。
- 主机基线 `:app:assembleDebug` 通过，基线 APK SHA-256：`2335B0A83A2C1A2FDD4A20A4434767454BB89F87207150A2E1EEDADE732F553A`。
- 红米型号 21091116UC / Android 13，已成功 `adb install -r` 基线，保留登录及应用数据；设备 Wi-Fi 已处于关闭状态。
- 新增跨策略/预算/池的移动授权回归先失败（mobile-red.log），修复后 `:core:domain:test :player:testDebugUnitTest` 通过（mobile-green.log）。
- 基线脚本初次遇到播放控制自动隐藏与瞬时 UI 树问题；失败报告与尝试账本保留，不能作为性能通过。
- AUTO 吞吐估计进入播放身份导致反复撤销：相同清晰度下从 500kbps 下降的回归先失败（throughput-red-2.log），修复后全部 ViewModel 回归通过（throughput-green-build.log）。初始较小变化未越过估计器滞回阈值，测试未复现；未把该轮绿色误当作根因已解决。
- 首段 64KiB 已到但整段 256KiB 未到的可读性回归先失败，修复后 player 单测与构建通过（first-header-red.log、first-header-green-build-2.log）。后续滚动窗口仍为 256KiB，前台前瞻最多 4MiB；备用阶段实际交给 TDLib 的新请求由同一 2MiB 台账约束，晋升后恢复前台前瞻。
- 备用源错误只放弃当前目标；真正的解码/硬件错误仍按会话降级。质量代次改变不返还同一 VideoKey 的下一条请求预算。
- 首个吞吐估计发布时不再把 device networkGeneration 与 contextRevision 两种计数混用，避免将采样当成网络切换。
- 模拟器真实 Media3 C1 池、源错误恢复、所有权与释放组合通过（player-c1-source-fallback.log；7 个测试，含一个 C2 条件用例未执行，不能当作 7 个独立全部执行的 C1 用例）。
- 第一次完整并行 lint 出现 Kotlin FIR 内部异常；单工作线程复核定位到新增常量引用缺少 Media3 opt-in，补齐标注后 `:player:lint` 通过（lint-player-fixed.log）。未禁用 lint、删除测试或增加不稳定依赖。
- 第一次完整最终主机回归通过（full-proof-readiness-final.log，含 test/lint/assembleDebug 与 Compose 编译/Robolectric）；API 36 模拟器 Compose 101/101 通过；真实 Media3 C1 执行 6 个、另有 1 个 C2 条件跳过，C2 执行 7/7 通过。新增起播缓冲修复后的重新验收见后续记录，不能用前一构建替代。

## 中间移动数据观测（不作为最终验收）

| 构建/证据时间戳 | 协议与全部尝试 | 首帧结果 | bind→首帧 P50 / P95 | 限制 |
|---|---|---|---|---|
| 原工作树基线 001949 | 12 次正常前滑；12s 截止 | 12 个最终首帧，其中 1 个超时；FAIL | 2708 / 14176ms | READY 命中 0；无 rebuffer/crash；PSS 308241→341229KiB |
| 首轮移动授权/手势修复 C1 012519 | 12 次正常前滑；12s 截止 | 12/12；PASS | 1263 / 2905ms | READY 命中 0（准备中晋升 1）；无 rebuffer/crash；PSS 223489→254205KiB |
| 保留准备 C1 013916 | 20 次，每次另看 3s；12s 截止 | 台账 19 次观察到终态、1 次未观察到；末尾日志只剩 15 首帧；FAIL | 不用于验收 | 设备循环日志覆盖早期事件。现已累积每次读取的去重事件，并用重叠/覆盖回归验证收集器 |
| C1 首段优化 015718 | 20 次，每次另看 5s；12s 截止 | 20/20 首帧、无超时/错误/crash；连续性 FAIL | 1033 / 2034ms，最大 3567ms | READY 命中 0。旧脚本报 5 条含重缓冲的日志，去除 state/sample/summary 重复后实际 2 次；审计输出为 c1-firstframe-audited-summary.json。PSS 186888→283347KiB，gfxinfo jank 7.82% |

不同轮次并非同一批媒体、缓存与网络窗口，不能据上述数字计算可归因的提升比例。首帧是显示回调；不等于整片已下载，且不替代连续播放/重缓冲观察。所有早期脚本未能验证页面的失败记录仍保留。

## 计时审计

015718 轮明确观察到首个静止画面先于可连续播放的 STATE_READY。此前 first-frame 回调无条件补写 READY，使历史 `bindToReadyMs` 不能当作真实可播放时间；首帧耗时本身仍是实际显示回调。现改为仅当 ExoPlayer 确实 READY 才补写已发生的 READY，另记录一次 `playable` 事件，基准独立统计其 N/P50/P95/max，未到 READY 不作为 0ms。重缓冲新增单次开始事件，并兼容审计旧累计日志，禁止将重复日志当作多次停顿。

该变化不降低缓存起播阈值，也不通过提前隐藏加载状态来制造成绩。新计时版本已真机复核；以下结果均保留连续性失败。

## 严格可播放计时下的移动数据对照

设备为 Redmi Note 11 Pro+ / Android 13；Wi-Fi 关闭、移动数据开启，保留既有 VPN、账户、索引和缓存。各轮为随机媒体、不同网络时间窗口，不能计算可归因的 C1/C2 提升比例。每轮 20 次正常前滑，单次截止 12s，每次观察到首帧及实际 READY 后额外观看 5s。

| 指标 | C1（082001） | C2（083000） |
|---|---:|---:|
| 全部尝试 | 20 | 20 |
| 截止内完成 / 超时 | 13 / 7 | 20 / 0 |
| 最终首帧数 | 19 | 20 |
| bind→首帧 P50 / P95 / max | 2036 / 12355 / 12355ms | 995 / 2044 / 2207ms |
| 实际 READY 数（含截止后） | 16 | 20 |
| bind→实际 READY P50 / P95 / max | 5770 / 19710 / 19710ms | 1486 / 7141 / 9199ms |
| READY 准备命中 | 1 | 3，命中子组首帧 P50 100ms / max 105ms |
| 重缓冲 / crash | 6 / 0 | 7 / 0 |
| PSS 前 / 后 | 194812 / 277193KiB | 194392 / 315114KiB |
| gfxinfo jank | 30.34% | 23.25% |
| 连续性验收 | FAIL | FAIL |

C2 确认能在红米完成离屏解码及可见 Surface 交接，但不代表连续性合格；不能用 3 次命中代替 20 次全量结果。UID 流量增量尚未验证。红米拒绝安装单独的库 instrumentation APK，返回 INSTALL_FAILED_USER_RESTRICTED；没有绕过设备安装限制，设备硬件夹具 instrumentation 尚未验证。实际应用 APK 安装和真实流量测试已执行。

## 起播缓冲回归

C2 轮中出现过晋升后播放 175–460ms 即缓冲耗尽。原备用 LoadControl 在 500ms 就放行 READY，且仅允许 1s 样本；这与前台 2500ms 起播门槛冲突。新增真实 LoadControl 测试先失败（promotion-reservoir-red.log），现统一起播阈值、备用样本时间上限 3s，内存仍 1MiB、移动数据请求仍 2MiB。

Media3 在停止加载时也可能直接进入 READY。因此晋升另外校验剩余缓冲：达到前台门槛或整段剩余时间线已缓冲才开始播放；保持同一个播放器、媒体源和下载区间，在等待时以当前播放身份继续加载。暂停/手势停用计时器，恢复后继续检查，换目标与释放清除等待，不留下旧目标自动播放。准备命中指标也必须通过该缓冲校验。

修复后的主机完整 Proof 已通过（full-proof-reservoir-final.log，4m44s）：1689 次测试执行，含构建变体重复执行，失败/错误/跳过均为 0。Compose API 36 AOSP x86_64 为 101/101；真实 Media3 C1 为 7 个执行通过、1 个 C2 条件跳过，C2 为 8/8 执行通过。起播测试第一次绿色复测暴露了测试夹具使用空 Timeline 的问题，改为真实 SinglePeriodTimeline 后通过；该中间失败保留于 promotion-reservoir-green-emulator.log，没有作为通过证据。

## 快速滑动压力观测

132217 轮使用起播缓冲修复前的 C2 APK：50 次前滑、每 5 次一个检查点，10 个批次均观察到终态，9 个首帧、4 个 UNCHANGED、69 个被后续目标替代的终态；这不是 50 个独立视频的成功播放。采集窗口中播放器 FAILED/重缓冲/crash 均为 0，PSS 274220→288027KiB。方向和字段完整性检查失败，报告为 FAIL；不能将批次终态协议冒充逐条可播放验收。更早一次快速脚本被会话中断且没有尝试账本，未计为已测 50 次。

## 本轮直接修改的实现与验证文件

- `core/domain/.../media/VideoPreloadPolicy.kt`、`NextPreloadBudgetController.kt` 及 `NextPreloadBudgetControllerTest.kt`。
- `player/.../VideoPlayerManager.kt`、`ReusablePlayerLifecycle.kt`、`TelegramMediaDataSource.kt`、`Media3SamplePreloadController.kt`。
- `player/src/test/.../MobilePoolAdmissionTest.kt`、`PoolStartupReservoirTest.kt`、`ReusablePlayerLifecycleTest.kt`、`SamplePreloadStage18Test.kt`、`TelegramMediaDataSourceTest.kt`。
- `player/src/androidTest/.../VideoPlayerManagerIntegrationTest.kt`、`PlaybackPoolEngineTest.kt`。
- `app/.../feature/video/VideoPlaybackViewModel.kt` 及 `VideoPlaybackViewModelTest.kt`。
- `scripts/run-swipe-first-frame-benchmark.ps1`、`scripts/SwipeFirstFrameBenchmark.psm1`、`scripts/tests/SwipeFirstFrameBenchmark.Tests.ps1`。
- `AGENTS.md`、`README.md`、`docs/ARCHITECTURE.md`、`PRODUCT_SPEC.md`、`DEVELOPMENT_PLAN.md`、`ACCEPTANCE_TESTS.md`、`VELORA_OPTIMIZATION_HANDOFF.md`、本文件及 GitHub 调研文件。

工作树另有进入本轮前的 Stage 25 修改；本清单不把整个 git diff 归为本轮成果。没有自动提交、推送或发布。

## 可复核命令与构建

```powershell
$env:JAVA_HOME = 'E:\Android Studio\jbr'
.\gradlew.bat test lint assembleDebug :app:compileInstrumentationKotlin :app:testInstrumentationUnitTest :app:assembleInstrumentation :app:assembleInstrumentationAndroidTest :player:assembleDebugAndroidTest --max-workers=1 --no-daemon --console=plain
.\scripts\run-emulator-compose-tests.ps1 -Serial emulator-5580 -SkipBuild
& 'E:\AndroidStudio2.0\platform-tools\adb.exe' -s emulator-5580 install -r player/build/outputs/apk/androidTest/debug/player-debug-androidTest.apk
& 'E:\AndroidStudio2.0\platform-tools\adb.exe' -s emulator-5580 shell am instrument -w -e class 'com.qixuan.channelvideoflow.player.PlaybackPoolEngineTest,com.qixuan.channelvideoflow.player.VideoPlayerManagerIntegrationTest' com.qixuan.channelvideoflow.player.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:assembleDebug :player:assembleDebugAndroidTest -PcvfPlaybackPoolCandidate=C2 --max-workers=1 --no-daemon --console=plain
# 单独重复上述真实播放器 instrumentation 验证 C2，随后恢复默认 C1 构建。
.\gradlew.bat :app:assembleDebug --max-workers=1 --no-daemon --console=plain
.\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <已核实红米> -SwipeCount 20 -WatchMillis 5000 -ReportStage stage26 -SkipBuild
.\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <已核实红米> -SwipeCount 50 -Mode Fast -FastCheckpointEvery 5 -ReportStage stage26 -SkipBuild
```

固定 C1 与 C2 均为双播放器；C2 额外使用受消费的离屏输出，受保护内容不使用它。默认构建保持 C1，C2 是单独验证候选。全量单测的默认候选合同仍校验 C1，未为了跑显式 C2 构建而删除该守卫。

起播修复后 APK（私有本地 Debug 测试产物，不是发行版本）：

- `build/reports/stage26/velora-stage26-c1-reservoir.apk`：SHA-256 `4651A5F36B5EB2249E7EEB0811B8B3F434885413786CEAEF5326493423AB1439`。
- `build/reports/stage26/velora-stage26-c2-reservoir.apk`：SHA-256 `8FBA2121EF5D2C9281F4077DEF47A686FEC6F44D7934B8D351B356572D7B1B6A`。
- 红米 C1 修复版覆盖安装成功，冷启动 MainActivity 1576ms；这是应用启动耗时，不是视频起播成绩。

## 起播修复后的移动数据复验

132741 轮：C1、20 次正常前滑、每次另看 5s；首帧 20/20，bind→首帧 P50 1122ms / P95 3609ms / max 3729ms。实际可播放截止内 9/20，11 次超时；最终记录到实际 READY 12 次（含晚到），其 P50 3431ms / P95=max 19134ms，另 8 次没有 READY，不能记作 0ms。严格 READY 准备命中 0；重缓冲 1、播放器错误 0、crash 0；PSS 227392→267211KiB，gfxinfo jank 20.02%。**FAIL**，没有达到全量秒开或近乎完美连续播放。

该轮包含 3840×2160、约 198MB 的原视频，日志为 alternative=false，只能证明没有选到替代版本，不能据此断言服务器绝对没有其他编码版本。当前手机网络和随机媒体与早间轮不同，不能将重缓冲次数减少直接归因或计算百分比提升。

C2 起播修复版第一次复测停在标签页，尚无性能尝试。由此发现采集器的两个边界错误：空日志在 PowerShell 管道中变成 null；初始页面等待的提示文字进入函数返回流，使失败布尔值被当作真。两项均新增实际函数回归、先红后绿；空日志现在按空数组处理，提示改用独立消息流，测试 PASS 仅在全部断言完成后输出。相关证据为 benchmark-empty-log-{red,green}.log、benchmark-route-wait-{red,green}.log、benchmark-script-final.log。已有逐次播放页校验与完整尝试账本的性能轮仍保留；未开始采样的失败不伪装成视频加载结果。

133920 轮：C2、同为 20 次正常前滑与每次另看 5s，实际媒体和网络窗口不同。截止内完成 12/20、8 次超时；最终首帧 19 个，bind→首帧 P50 1364ms / P95=max 19542ms；最终实际 READY 13 个（含晚到），P50 3595ms / P95=max 14123ms；严格 READY 命中 0。重缓冲 4、播放器错误 0、crash 0；PSS 197955→259310KiB，gfxinfo jank 26.5%。**FAIL**，这轮也不能作为 C2 已达标的证据。

对照后选择：工作树及手机恢复 C1 双播放器；C2 实现和独立 APK 保留，但没有足够的同条件优势及连续性证据将其改为默认。当时 C1 覆盖安装成功，明确 force-stop 后冷启动 MainActivity 1540ms，未清除应用数据。后续使用检查又发现以下问题，因此上述 APK 不是最后交付构建；移动数据全量连续性目标仍未达成，不进入下一阶段、不发布版本。

## 使用检查发现的 seek 与内存问题

- 暂停后拖动进度：实际播放器回到 READY，但界面仍加载且“继续播放”消失。`seekTo` 清除了已有首帧标记，而 Media3 复用已解码画面时不保证再发首帧回调。修复为同一绑定保留已有画面，seek 回到 READY 后解除 seek 风险。真实 Media3 回归先失败（paused-seek-red-emulator.log），修复后通过；红米暂停→seek→恢复、后台停止→返回→恢复已通过（redmi-delivery-flow.log）。该首次使用修复构建 SHA 为 `98815C84767C8AD3DF547D1F674B3198508B5D4C0F5040E6855DFA2C007EEACE`，随后又加入内存限制。
- 2026-09-10 13:50:05，较早 C1 构建的额外长暂停会话发生 **1 次 OutOfMemoryError**，堆增长上限为 256MiB，保存于 old-build-paused-oom.log。这次崩溃在 20 次切换采样窗口之外；之前各轮“crash=0”不能用来声称整段开发期间零崩溃。
- 使用真实 DefaultLoadControl/Allocator，已复现达到 32MiB 后仍因时间缓冲不足而继续加载的风险（sample-memory-red-emulator.log）。现为 ACTIVE 增加 32MiB 样本停止加载阈值，STANDBY 仍为 1MiB；两个引擎各自使用原 Media3 Allocator，不增加文件缓存。达到当前样本阈值且已有可播放数据时允许资源降级起播，避免内存阈值与起播等待互相死锁。
- 32MiB 是加载决策阈值，单个在途样本可能越过它；容器解析、解码器和其他应用对象不属于这个数字。没有保存可能包含账户资料的堆转储，不能声称已证明旧 OOM 的唯一对象来源或消除了任意素材的所有 OOM。

## 最新构建与回归

| Proof | 最新结果 |
|---|---|
| 全量 test、lint、assembleDebug、Compose 编译及 Robolectric | PASS，full-proof-memory-bounded.log，4m04s；1692 次执行（含变体重复），失败/错误/跳过均为 0 |
| API 36 AOSP x86_64 Compose | PASS，101/101，compose-memory-instrumentation.log |
| C1 真实 Media3 | 8 个执行通过、1 个 C2 条件跳过，sample-memory-green-emulator.log |
| C2 真实 Media3 | 9/9 执行通过，c2-memory-emulator.log；包含 paused seek、内存阈值及全部既有交接/取消/资源回收检查 |
| PowerShell 采集器 | PASS，benchmark-script-final.log；包括空日志、页面校验、日志覆盖及 READY/重缓冲计数 |
| Redmi 覆盖安装及冷启动 | 最新 C1 安装成功，MainActivity 1408ms；当前轮账户/索引保留 |

最新 C1 为 `build/reports/stage26/velora-stage26-memory-bounded.apk`，SHA-256 `CE339C1F92FB089C2427E79C7FE2BF16ED0DCD67620F9B7A1A16CD447E08DC04`。最新 C2 对照构建为 `velora-stage26-c2-memory-bounded.apk`，SHA-256 `F8DC4051DF0F8FB3089BC12A9753610522266E4ADD1FFC20EA7E340A5D5DC0D7`，保持非默认，不作已发布版本。

最新 C1 的额外观测：有可用 480p 替代版本时，约 4.5MB/60s 视频完成播放，60s 重缓冲为 0；另一个 2560×1440、约 964MB、未选中替代版本的视频，在长暂停前已观察到 3 次重缓冲。该两条为定向使用观察，不能替代随机全量性能分布；详见 bounded-manual-observations.log。长暂停内存采样和最后安装状态见下文。

证据目录：`build/reports/stage26/`（Git 忽略）；不保存账号、密码、媒体画面或设备网络地址。
