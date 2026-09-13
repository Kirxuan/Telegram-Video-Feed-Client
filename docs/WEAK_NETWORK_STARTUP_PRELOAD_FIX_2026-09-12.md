# 跨层有界预取修复与红米真机验证（2026-09-12）

> 公开版本说明：测试频道、标签和设备序列号已脱敏。本文保留原阶段验证状态；1.2 的正式发布范围见 [发布说明](RELEASE_1.2.md)。

本文件记录 docs/WEAK_NETWORK_EXTREME_OPTIMIZATION_RESEARCH.md 第 2.1 节"256KiB 策略可能被调用链截断"的**复现、最小修复与验证**。

阶段编号：接手时 AGENTS 仍记录 Stage 28、工作树已有 Stage 29 文档，编号归属尚未由仓库所有者确认，因此本文件不自行宣布阶段号，只描述内容。正式发行基线仍为 Stage 24 / 1.1.0，本轮产物是私有本地 Debug 测试候选，不是发行版本。

## 工作合同

- **Outcome**：当前播放项尚未渲染首帧（STARTUP）时，唯一下一条可获得**一次 ≤256 KiB 的字节前缀预取**（不创建解码器、不启动备用播放器），且该字节阶段能与后续重 STANDBY 阶段共享同一目标身份与累计预算。
- **Scope**：`core/domain/.../NextPreloadBudgetController.kt`、`player/.../VideoPreloadManager.kt`、`app/.../feature/video/VideoPlaybackViewModel.kt` 及三个模块的对应测试。
- **Boundary**：不改池备用准入（仍要求首帧 + 当前缓冲 ≥3 秒）、5 秒目标、20 MiB 新请求上限、大原画确认、源选择、播放器引擎、HLS 会话、缓存与权限；不引入多条预取；不改变顺序。
- **Failure states**：离线、省电、热、存储压力、内存压力、移动数据预加载未开启、SEEK、REBUFFER 均为 BLOCKED，不发任何下一目标字节。
- **Proof**：模块定向测试 + 完整 `test lint assembleDebug` + `:app:compileInstrumentationKotlin` + Robolectric-Compose 组 + 红米真机移动数据 A/B。

## 1. 复现：三层 guard 确实拦截了 256 KiB

README/AGENTS 记录的 Stage 28 意图是：**未出首帧时 `AdaptivePreloadPolicy` 返回 `CONSERVATIVE`（256 KiB）而不是硬阻塞**（docs/STAGE28_MOBILE_FAST_START.md §3.3）。静态核查与测试证明该意图在生产配置下**无法落地**：

生产开关：`cvfDynamicNextPreloadEnabled=true`（默认）、`PRODUCTION_OWNER_PROMOTION_ENABLED=false`、`cvfPlaybackPoolCandidate=C1`、`cvfTelegramHlsEnabled=true`。

| # | 位置 | 行为 |
|---|---|---|
| A | `VideoPlaybackViewModel.activateNextPreloadIfEligible` | `if (!playerSnapshot.hasRenderedFirstFrame) return` —— 未出首帧时不注册下一目标 |
| B | `NextPreloadBudgetController.tier` | `playbackState != PLAYING -> BLOCKED` —— STARTUP/SEEK/REBUFFER 一律 0 预算 |
| C | `VideoPreloadManager.onCurrentPlaybackStarting` | `ownerPromotionEnabled=false` 分支无条件 `cancelSpeculativeLocked(); target = null` —— 当前项每个 Loading snapshot 都清掉目标 |

同时确认：动态路径完全忽略 `AdaptivePreloadDecision.maxPreloadBytes`（`restartLocked` 在 `dynamicNextPreloadEnabled=true` 时直接转 `restartDynamicLocked`），因此 Stage 28 的 256 KiB 决策在生产中是**空操作**。

**证伪步骤**（保留证据）：把上述三处行为临时还原（A 恢复早退、B 增加 `&& false`、C 增加 `&& false`），使用修复后编写的测试运行：

```
NextPreloadBudgetControllerTest > lightweightStartupPrefetchGetsOneFlatPrefixWhileEveryHardBlockStaysBlocked FAILED  (NextPreloadBudgetControllerTest.kt:207)
VideoPreloadManagerTest > startupSafetyWithoutAFirstFrameStillIssuesOneBoundedPrefixForTheNextTarget FAILED          (VideoPreloadManagerTest.kt:609)
VideoPreloadManagerTest > theItemThatBecomesCurrentStillReleasesItsOwnSpeculativeOwner FAILED                        (VideoPreloadManagerTest.kt:678)
VideoPlaybackViewModelTest > boundedNextBytePrefixIsRegisteredWhileTheCurrentItemStillHasNoFirstFrame FAILED         (VideoPlaybackViewModelTest.kt:951)
VideoPlaybackViewModelTest > heavyPoolStandbyStillWaitsForTheFirstFrameBeforeTakingTheNextTarget FAILED             (VideoPlaybackViewModelTest.kt:968)
```

三层各自独立地把有界预取挡在门外，疑点成立（不是仅凭静态阅读）。

## 2. 最小修复

### 2.1 预算层：显式区分"轻量字节预取"与"重备用准备"

`NextPreloadBudgetController`：

- 新增档位 `CONSERVATIVE_STARTUP(256 KiB)`。它是**平坦字节上限**而不是"秒×码率"，因为这一阶段唯一的任务只是覆盖滑动瞬间的 TTFB，平坦上限不会被未知或乐观的码率估值放大。档位序号刻意排在一档 `TWO_MIB` **之下**，保持所有 `>= TWO_MIB` 的池判断语义不变。
- `NextPreloadBudgetInput` 新增 `lightweightStartupPrefetch: Boolean = false`。只有**不创建解码器**的 `VideoPreloadManager` 字节预取器置为 `true`；池备用沿用默认 `false`。
- `tier()` 顺序调整：**设备压力与计量未开启先判**（硬阻塞永远优先），再允许 `STARTUP + lightweightStartupPrefetch -> CONSERVATIVE_STARTUP`，SEEK/REBUFFER 与其余情形保持 BLOCKED。对任何既有输入，档位结果（BLOCKED→BLOCKED）都不变，只有报出的原因可能与原先不同。
- `blockedReason()` 同步改为接收 `NextPreloadBudgetInput`，保持同一顺序。

由于 `coerceIn(MIN_PROGRESSIVE_BYTES=256 KiB, min(ceiling, 20 MiB)=256 KiB)` 与循环里 `min(remaining, RANGE_CHUNK_BYTES, fileRemaining)` 的双重约束，该阶段**整个目标最多 256 KiB**：一次分块后 `remainingNewNetworkBudgetBytes` 归零，循环退出，不会重复申请。

累计账本语义：`dynamicRequestedBytes` 在目标身份不变时**不重置**，因此当当前项随后进入 PLAYING、档位放大到 20 MiB 时，已花掉的 256 KiB 计入同一预算，不会重复下载（"不取消退款、不重复申请"）。

### 2.2 执行器层：Loading snapshot 不再清除"另一个"下一目标

`VideoPreloadManager.onCurrentPlaybackStarting`（`ownerPromotionEnabled=false` 分支）：只有当待预取目标**就是**正在成为当前项的那个视频时才释放推测性 owner（此时 CURRENT_PLAYBACK owner 会接管同一文件，符合 Stage 28 单主导窗口）；目标 key 不同时**保留**，因为那仍是唯一的下一条。修复前，当前项每个 Loading snapshot 都会把它清掉。

### 2.3 ViewModel 层：未出首帧只走轻量字节阶段

`activateNextPreloadIfEligible`：把 `needsOriginalConfirmation` 判定提到最前（大原画待确认仍然完全阻止预取），未出首帧时只调用 `preloadController.setNextVideo(preparedVideo)` 并返回；**不调用 `prepareStandby`**，因此不会创建第二个解码器或备用播放器，当前项的启动储备仍占用链路。

## 3. 测试

新增/更新：

- `core/domain/src/test/.../NextPreloadBudgetControllerTest.kt`
  - `lightweightStartupPrefetchGetsOneFlatPrefixWhileEveryHardBlockStaysBlocked`：STARTUP 轻量档 256 KiB、预算是总量而非每次额度、未知码率不上调；计量未开启 / 移动未开启 / 省电 / 热 / 存储 / 内存 / SEEK / REBUFFER 全部为 0。
  - `heavyStandbyKeepsTheThreeSecondAdmissionAndNeverUsesTheStartupTier`：重路径 STARTUP 仍 BLOCKED，2.9 秒缓冲仍 BLOCKED。
- `player/src/test/.../VideoPreloadManagerTest.kt`
  - `startupSafetyWithoutAFirstFrameStillIssuesOneBoundedPrefixForTheNextTarget`：STARTUP + 移动预加载开启 → **恰好一次 256 KiB** NEXT_PRELOAD 请求，档位 `CONSERVATIVE_STARTUP`。
  - `loadingSnapshotsForTheCurrentItemDoNotCancelADifferentNextTarget`：连续 4 次 Loading snapshot 不清除异 key 目标、不关租约。
  - `theItemThatBecomesCurrentStillReleasesItsOwnSpeculativeOwner`：目标晋升为当前项时仍释放。
  - `startupBytePrefixStillHonoursEveryHardBlock`：计量未开启 / REBUFFER / SEEK / 内存压力 → 零请求。
- `app/src/test/.../VideoPlaybackViewModelTest.kt`
  - `boundedNextBytePrefixIsRegisteredWhileTheCurrentItemStillHasNoFirstFrame`（替代原 `nextNetworkPreloadWaitsUntilCurrentItemHasRenderedAFrame`）。
  - `heavyPoolStandbyStillWaitsForTheFirstFrameBeforeTakingTheNextTarget`：支持备用的控制器上，首帧前只注册字节目标、不调用 `prepareStandby`，首帧后才准备。
  - `onlyFinalStablePageBindsAndTransitionPausesOldAudio` 的 `targets` 断言改为取最后一个（该用例本意是"最终绑定页的下一目标"，不是"整轮只有一个目标"）。
  - 测试替身新增可选 `standbySupported`（默认 false，保持既有用例路径不变）。

**契约变更说明**：原用例 `nextNetworkPreloadWaitsUntilCurrentItemHasRenderedAFrame` 断言"首帧前不注册任何下一目标"，这与 Stage 28 §3.3 的书面意图相反，是本次修复要拆开的那一条。现在拆成"轻量字节阶段不等首帧"与"重备用阶段仍等首帧"两条。

## 4. 主机 Proof（全部通过，但**不等于**完整验收）

> 标题修正：本节只证明**主机侧**检查通过。AGENTS 定义的 `Proof(Compose)` 还需要 emulator-Compose-UI，该环节本轮未闭合（见第 6 节），因此本阶段**未**达到完整验收条件。

```
.\gradlew.bat :core:domain:test :telegram:testDebugUnitTest :player:testDebugUnitTest --no-daemon --console=plain --max-workers=1
.\gradlew.bat test lint assembleDebug --no-daemon --console=plain --max-workers=1
.\gradlew.bat :app:compileInstrumentationKotlin --no-daemon --console=plain --max-workers=1
```

- 三步均 `BUILD SUCCESSFUL`（第二步 14m45s；`config candidate=12D-FINAL`）。
- 全变体测试汇总：**203 个测试类 / 1793 个测试 / 0 失败 / 0 错误**（debug、release、benchmark、instrumentation 四种变体）。
- Robolectric-Compose（`testInstrumentationUnitTest`）：`LoginScreenTest` 17、`ChannelSelectionScreenTest` 8、`CacheSettingsScreenTest` 4、`TagFilterScreenTest` 6、`VideoPlaybackScreenTest` 81、`ComposeSmokeTest` 2，全绿。
- 静态安全：`AndroidManifest.xml` 未修改；权限仍只有 INTERNET 与 ACCESS_NETWORK_STATE；`allowBackup=false` 与 dataExtraction/fullBackup 排除规则未动；改动文件未出现凭证。
- 构建指纹（可复现，两次独立构建哈希一致）：
  - 修复候选：`app-debug.apk`，46,913,928 字节，SHA-256 `4b619c41dd6917ee16b79c2f8aa2e9f9018e9bdb804b4108a05f49e01b90ff21`
  - 对照（三层 guard 还原）：46,913,928 字节，SHA-256 `4f3147bdfc4ecccfcede5373056d17d38bb33b07c096132e0679b6ce7f63761e`

## 5. 红米 Note 11 Pro+ 真机移动数据验证

设备 `REDACTED_REDMI_SERIAL`（model `21091116UC`，Android 13 / API 33）；Wi-Fi 关闭（`wifi_on=0`）、移动数据开启（`mobile_data=1`）；应用 `mobile_preload_enabled=1`、清晰度偏好 `DATA_SAVER`、缓存上限 20 GiB；覆盖安装，**未清账号、索引与缓存**。
目标：频道"测试频道（名称已脱敏）" + 标签 `#TEST`（158 个视频）。

**环境混杂因素（必须披露）**：设备上存在一个 Clash VPN 隧道（`tun0`，默认网络为 VPN，含 HTTP 代理），所有应用流量经其转发。研究文档已指出代理路径与 Cloudflare/Telegram 对比不可比，本次未关闭用户 VPN，因此吞吐归因受限。

轨迹：同一节奏 `input swipe 540 1900 540 700 110`，每轮 20 次滑动，停留 3 秒；对照顺序为**先修复版、再基线版、再修复版配对轮**（因此基线轮的缓存比第一次修复轮更热，对基线有利）。

| 指标（20 次滑动/轮） | 基线 `4f3147bd` | 修复 `4b619c41`（配对轮） |
|---|---|---|
| `CVF-Preload` 日志行数 | **0** | **12** |
| `allowedBudgetTier=CONSERVATIVE_STARTUP` | **0** | **6**（占 30% 滑动） |
| `stop()` 时持有活动租约（`activeLease != null`） | **0** | **6** |
| 手势→终态 P50 / P95 (ms) | 701.5 / 910 | 711.5 / 872 |
| settle→终态 P50 / P95 (ms) | 114.5 / 335 | 142 / 334 |
| bind→首字节 P50 / P95 (ms) | 0 / 31 | 8 / 29 |
| 池备用 READY 命中 | 8/20 | 8/20 |
| 首帧终态 / 被释放 | 16 / 4 | 17 / 3 |
| 重缓冲 | 0 | 0 |
| TOTAL PSS | 283.2 MB | 288.7 MB |

机制证据（本阶段的主要结论）：

1. 修复版日志出现 `calculatedTargetBytes=262144 allowedBudgetTier=CONSERVATIVE_STARTUP currentBufferedSeconds=0.0` —— 正是"未出首帧"窗口内被授予 256 KiB，基线版**在同一轨迹中一次都没有**。
2. 修复版 6 次在 `stop()` 时仍持有活动租约（`stop()` 仅当 `target != null && activeLease != null` 才打印 YIELD），说明该窗口内**确实发出了 NEXT_PRELOAD 区间请求**；基线版 0 次。
3. 端到端计时在两轮之间**无统计差异**（差异量级远小于研究文档指出的"随机媒体轮次 P50 差 4 倍"），两轮都是热缓存、首帧在 100-300 ms 内完成；本阶段**不主张端到端收益**。
4. 副作用：两轮均零重缓冲；PSS 差 +5.5 MB（+1.9%），无解码器或缓冲实例增加，判为无可测回归但不算等价证明。

## 6. 局限与尚未验证

- **emulator-Compose-UI 未验证**：`scripts/run-emulator-compose-tests.ps1` 所需的 `CVF_AOSP_API36_X86_64` AVD 在本次会话无法启动。模拟器（`emulator.exe`）反复在 `external/netsim+/rust/common/src/system/mod.rs:19` 以 `Os { code: 5, PermissionDenied }` panic：已尝试 `-feature -Netsim,-Wifi`、清理 `%TEMP%\netsim.ini` 与 `netsimd`、以及非沙箱模式，均同样崩溃；同一 AVD 在 2026-09-11 曾成功运行（`%TEMP%\netsimd\netsim_stdout.log`），因此这被判定为**宿主环境限制**而非代码缺陷。Compose Proof 的另外三项（编译、Robolectric-Compose、红米 install+launch）已通过。
- **目标场景未被真机复现**：本次两轮都命中热缓存（`bind→首字节` P50 为 0-8 ms），当前项首帧很快，未出现 Stage 28 记录的"慢视频等 10-16 s 自身储备"窗口。因此 256 KiB 前缀的**收益**（而非存在性）未获验证，需要一次冷前缀或高码率原画启动的受控轮次。
- **HLS 目标的轻量阶段不覆盖 TTFB**：见第 8.3 节，轻量阶段对含 HLS 变体的目标只发一次渐进前缀、不取清单；HLS 的清单与片段准备仍属重阶段。HLS 覆盖率本身未统计。
- **代理/VPN 混杂**：未关闭，见第 5 节。
- 样本量：每轮 20 次滑动，未达到研究建议的每层 ≥30、重点组 ≥100；无 20-30 分钟混合观察。

## 7. 修改文件

- `core/domain/src/main/java/com/qixuan/channelvideoflow/domain/media/NextPreloadBudgetController.kt`
- `core/domain/src/main/java/com/qixuan/channelvideoflow/domain/media/VideoPreloadPolicy.kt`
- `core/domain/src/test/java/com/qixuan/channelvideoflow/domain/media/NextPreloadBudgetControllerTest.kt`
- `player/src/main/java/com/qixuan/channelvideoflow/player/VideoPreloadManager.kt`
- `player/src/main/java/com/qixuan/channelvideoflow/player/VideoPlayerManager.kt`
- `player/src/main/java/com/qixuan/channelvideoflow/player/Media3SamplePreloadController.kt`
- `player/src/test/java/com/qixuan/channelvideoflow/player/VideoPreloadManagerTest.kt`
- `player/src/test/java/com/qixuan/channelvideoflow/player/SampleRequestBudgetTest.kt`
- `player/src/androidTest/java/com/qixuan/channelvideoflow/player/VideoPlayerManagerIntegrationTest.kt`
- `app/src/main/java/com/qixuan/channelvideoflow/feature/video/VideoPlaybackViewModel.kt`
- `app/src/test/java/com/qixuan/channelvideoflow/feature/video/VideoPlaybackViewModelTest.kt`

## 8. 复查（codex）三项 P2 的核实与处理

`build/reports/startup-preload-review/REVIEW.md` 提出三项 P2。三项均经本轮**独立核实成立**，已修复。

### 8.1 动态 STARTUP 路径不尊重 `AdaptivePreloadState.OFF`（已修复）

核实：`restartLocked` 在 `dynamicNextPreloadEnabled=true` 时直接转 `restartDynamicLocked` 并返回，**跳过**了非动态分支里的 `decision.state == OFF` 判定；而 `NextPreloadSafetySnapshot` 没有 offline 字段，`isMetered` 在用户已授权移动预加载时会通过计量门。因此离线策略下仍会申请 262144 字节。复查方的 `StartupPrefetchReviewProbeTest` 复现属实（`OFFLINE issued bytes=262144`）。

修复：`NextPreloadBudgetInput` 新增 `hasAdaptiveHardBlock`，由持有策略决策的一方（`VideoPreloadManager.calculateBudgetLocked`，取自 `decision.state == AdaptivePreloadState.OFF`）填写；`tier()` 把它放在**第一条**分支，压过包括启动前缀在内的所有放行条件；新增 `NextPreloadStopReason.ADAPTIVE_HARD_BLOCK` 以保持诊断可读。该门同样封堵了此前就存在的"PLAYING + 离线 + 已授权"漏洞。

另外补了一处真实缺陷：目标因 `CLEAR_TARGET_REASONS`（如 NETWORK_CHANGED）被清空时，`budgetDecision` 保留旧值，设备诊断会把已撤销的阶段报成"仍被放行"；现在会同步刷新为 BLOCKED。

验证：复查方自己的探针 `reviewOfflinePolicyMustBlockStartupPrefix` 由失败转为**通过**（用其原始 init script 命令运行）。新增用例：`lightweightStartupPrefetchGetsOneFlatPrefixWhileEveryHardBlockStaysBlocked`（领域层硬阻塞优先）、`offlinePolicyBlocksTheStartupPrefixEvenWithMobilePreloadOptedIn`、`policyTurningHardBlockedMidFlightRevokesAnAlreadyIssuedPrefix`（含中途撤销、释放租约、以及在硬阻塞期间的重新注册仍被拒）。

### 8.2 轻量阶段与重备用的累计请求预算没有交接（已修复）

核实：`prepareStandby` 在目标变更时 `standbyBudget = SampleRequestBudget()`，其 `reservedBytes` 从 0 起算；`updatePoolPreparation` 也只把它作为 `requestedUncachedBytes`。因此同一目标在被轻量阶段申请过 256 KiB 后，池仍可获得接近整个档位上限的新额度，"取消不退款"在账面上不成立。

修复：`SampleRequestBudget` 新增 `seed(alreadyRequestedBytes)`；`VideoPreloadController` 新增默认方法 `requestedBytesFor(video): Long = 0L`（默认实现使既有替身无需改动），由 `VideoPreloadManager` 覆盖为"仅当身份完全一致时返回 `dynamicRequestedBytes`"；`VideoPlayerManager.prepareStandby` 在为目标新建预算时先 `seed(videoPreloadController.requestedBytesFor(video))`。于是同一目标在两级之间共享一个寿命上限，取消过的在途请求也计入，不再退款。

验证：`SampleRequestBudgetTest.seededHandoverShrinksTheRemainingCeilingInsteadOfResettingIt`（种子后余额被压缩、超限仍抛错、超种子的负余额被夹紧而不是抛错）、`VideoPreloadManagerTest.requestedBytesAreHandedOverPerExactTarget`（身份不同不继承账本）、以及 `VideoPlayerManagerIntegrationTest` 中新增的真机断言（池继承 256 KiB；换目标后归零）。

### 8.3 HLS 清单不计入 256 KiB 启动预算（已修复）

核实：`MAX_MANIFEST_BYTES = 256 * 1024`，`NextHlsPreloadManifestLoader.load` 会按该上限发起一次 NEXT_PRELOAD 请求，且**不**计入 `dynamicRequestedBytes`；其后载荷还各自拥有至多 256 KiB。清单失败时还会回退到 MP4 前缀，让清单请求的额度变成不可退。

修复（选择"有界前缀探路"而非"完整片段准备"）：轻量阶段**不再请求 HLS 清单**。`restartDynamicLocked` 中在 `CONSERVATIVE_STARTUP` 档位下把 `hlsPlan` 直接置空，只对 `video.playbackFileId` 发一次 ≤256 KiB 的前缀分块。理由与边界一并写入代码注释：清单+片段是为完整片段准备服务的，属于重备用阶段的时长预算；而 Telegram 的 HLS 片段本身就是同一媒体文件的字节区间，前缀分块对 HLS 晋升同样有效，因此不会为了清单而叠加第二次不可计的请求。这样轻量阶段在**所有**分支上都以一次 ≤256 KiB 为界：成功、失败、HLS、直接文件。

验证：`VideoPreloadManagerTest.lightweightStartupStageNeverRequestsAnHlsManifest`（含 HLS 变体的目标在启动窗口只产生一次 `playbackFileId` 上的 ≤256 KiB 请求，且清单 fileId 上零请求）。

### 8.4 复查方其他意见的接受情况

- 接受：文档不得把主机检查称为"完整 Proof"，已修正标题并标明 emulator-Compose-UI 未闭合。
- 接受：轻量/重阶段的账本分离此前只被写作"局限"，现已按合同项修复并测试，不再作为形式差异处理。
- 保留意见：复查方"性能指标差距小只能说明证据不足"的表述正确，本轮仍不主张端到端收益。

### 8.5 修复后的验证

主机 Proof（同第 4 节三条命令）三步 `BUILD SUCCESSFUL`，0 失败。全变体测试汇总由复查前的 **203 类 / 1793 测试**变为 **203 类 / 1809 测试 / 0 失败 / 0 错误**（+16 项，即本轮新增用例）。Robolectric-Compose 六组仍全绿（`VideoPlaybackScreenTest` 81）。

- 领域层：`NextPreloadBudgetControllerTest` 14 项全绿（新增 `adaptiveHardBlockOutranksTheLightweightStartupAllowance`）。
- 执行器层：`VideoPreloadManagerTest` 29 项全绿（新增 4 项）；`SampleRequestBudgetTest` 3 项全绿（新增 seed 用例）。
- 复查方探针：`StartupPrefetchReviewProbeTest.reviewOfflinePolicyMustBlockStartupPrefix` 由失败转为通过（用其原始 `probe.init.gradle` 运行）。
- 真机 instrumentation（红米 `REDACTED_REDMI_SERIAL`）：`player` 套件 **`OK (11 tests)`**，0 断言失败，其中 `VideoPlayerManagerIntegrationTest` 含本轮新增的池预算交接断言（池继承 256 KiB、换目标后归零）。
- 真机启动 smoke：新 APK `install -r` 成功（未清数据），`MainActivity` `ResumedActivity`，crash buffer 为空。

**设备环境问题（非代码缺陷）**：`player` 真机测试前两次尝试失败于 `PlaybackProofActivity` 启动超时。根因由日志确认为 MIUI 的应用管控，而非测试断言或本轮改动：

```
D ActivityStarterImpl: MIUILOG- Permission Denied Activity KeyguardLocked:
  Intent { ... PlaybackProofActivity } pkg : ...player.test
appops: RUN_ANY_IN_BACKGROUND: ignore
```

该 Activity 使用 `FLAG_ACTIVITY_NEW_TASK` 启动，被"禁止后台弹出界面"策略拒绝。当次解除锁屏并对测试包临时 `appops set ... RUN_ANY_IN_BACKGROUND allow` 后，同一套件立即 `OK (11 tests)`。测试结束后已把动画缩放恢复为 1.0、该 appop 恢复为 `ignore`；未改动任何产品包或用户数据。

构建指纹（本轮修复后）：`app-debug.apk`，46,913,928 字节，SHA-256 `21e6b54d41317d04dc7b63903d1d0718d3a715ea4eeef680d68baf9004e9c90a`（此为私有本地 Debug 候选，不是发行版本）。

### 8.6 仍未闭合的项

- **emulator-Compose-UI**：仍受宿主 netsim 崩溃阻塞（第 6 节），`Proof(Compose)` 未闭合。未在红米上运行 app 的 Compose-UI instrumentation 来替代——那需要 `com.qixuan.channelvideoflow.instrumentation` 这个额外应用包，且 AGENTS 把该环节定义在模拟器上。
- **`NextPreloadSafetySnapshot` 仍无 offline 字段**：硬阻塞由调用方以 `hasAdaptiveHardBlock` 上报。这是有意的分工（只有持有策略者知道原因），但将来若有第三个调用方接入动态路径，必须同样上报，否则会重现同一漏洞。
- **冷前缀/慢启动的真机收益对照**：仍未做（第 5、6 节）。

未提交、未 push、未发布。
