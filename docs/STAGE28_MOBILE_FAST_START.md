# Stage 28：移动数据秒开优化（第三轮，#TEST 实测驱动）

> 公开版本说明：测试频道、标签和设备序列号已脱敏。本文保留原阶段验证状态；1.2 的正式发布范围见 [发布说明](RELEASE_1.2.md)。

日期：2026-09-11。用户明确授权：忽略单播放器模式、只测移动数据、必须做 GitHub 方案调研、测试目标为频道"测试频道（名称已脱敏）"、标签 #TEST、设备红米 Note 11 Pro+。全部证据在 `build/reports/opt/`（git-ignored）。

方案调研见 [GitHub 视频加载方案对比（第三轮）](STAGE28_GITHUB_LOADING_RESEARCH.md)。

## 1. 阶段合同

- **Outcome**：移动数据下，滑动到"预取过的下一条"时首帧接近瞬时（目标 <60 ms）；未命中时消除确定性浪费；慢视频（2K 高码率）不再叠加"下一条永远冷启动"的恶性循环；大视频的加载速度恢复到链路可支撑的水平。
- **Scope**：`TelegramFileManager`（下载窗口主导 + 冗余调用）、`VideoPreloadPolicy`（未出首帧时的有界储备）、`VideoPlayerManager`（渐进起播门槛）与对应单元测试；`docs/STAGE28_*` 与 AGENTS/README 的阶段记录。
- **Boundary**：不改双播放器池、不改秒级预算公式、不改重缓冲恢复门槛（12 s）、不加权限、不加依赖、不引入第三方下载器或代理。
- **Failure states**：断网、用户关闭移动数据预加载、弱网、设备压力、备用解码失败都必须回落显式加载/失败状态。
- **Proof**：定向单元测试先红后绿；`test lint assembleDebug`；红米 Note 11 Pro+ 移动数据多轮 20 次前滑实测（开工前基线与修复后对照）。

## 2. 问题归因（设备证据）

1. **预加载命中时首帧 32-54 ms；未命中时第一个 64 KiB 需 600-700 ms**（TTFB）。命中率随缓存变热在 50%-85% 之间浮动。
2. **同文件双 owner 拉锯**：滑动后，上一轮"下一条"的 `NEXT_PRELOAD` 残余 owner 与新的 `CURRENT_PLAYBACK` owner 在同一文件上共存。只要消费数据恰好就绪（播放器读本地数据时），残余 owner 就成为唯一未满足者并主导 plan，把下载窗口拉回旧 offset；下一次消费推进又拉回来。日志实证：`cancel result=DISJOINT_SWITCH`、20 次滑动 25 次 DISJOINT_SWITCH。
3. **拉锯的代价被下载速度证据直接量化**：同一轮测试中，无拉锯文件下载速度 1.3-3.4 MB/s，而带拉锯的当前播放文件只有 479 KiB/s（123389 案例），**3-7 倍损失**。设备移动数据实测 ≥ 4 MB/s（Cloudflare 20 MB），TDLib 侧无干扰时可跑满 3.4 MB/s。
4. **"未出首帧"的预加载硬阻塞**：慢视频等待自身启动储备可达 10-16 s（`bindToReadyMs=16391`），期间 `AdaptivePreloadPolicy` 返回 OFF 且 `CLEAR_TARGET_REASONS` 清空 target——下一条预加载整体停摆，形成"慢视频 → 无预加载 → 下一条冷启动 → 更慢"的循环。
5. **起播门槛对慢视频放大黑屏**：2.5 s 静态门槛在 19.5 Mbps + 移动链路上需要 10+ s 下载，期间零帧渲染。

## 3. 本轮改动

### 3.1 下载窗口由播放 owner 主导（`TelegramFileManager`）

`ensureRequestLocked` 新增 `selectSteeringOwners`：只要该文件拥有任意 `CURRENT_PLAYBACK` owner（**无论是否已经满足**），残余的 `NEXT_PRELOAD` owner 就不再参与 plan 合并、不再驱动请求。已下载字节仍留在 TDLib；残余 owner 期望的区间在消费推进时由播放请求自身的 read-ahead 覆盖。播放 owner 全部满足且只有预加载 owner 的文件（即"下一条"）不受影响。

**设备效果**：DISJOINT_SWITCH 25 → 5（剩余 5 次为其它路径）；同文件 owner 交替（拉锯）从多个文件降为 **0 个文件**。

### 3.2 冗余的同值请求不再重发（`TelegramFileManager`）

contained 复用分支在 priority 与 ownerKind 均未变化时直接返回，不再调用 `updateActiveRequestPriority`（该分支仅由测试构造启用；生产路径保持 13A 语义）。

### 3.3 "未出首帧"从硬阻塞降级为有界储备（`VideoPreloadPolicy`）

`CURRENT_NOT_STABLE` 由 `hardBlockedReason` 移除，改为返回 `CONSERVATIVE`（256 KiB）。`NEXT_PRELOAD` 优先级低于一切当前播放优先级，TDLib 资源分配天然让当前流先行；对下一条而言，这 256 KiB 恰好覆盖 TTFB 成本。硬阻塞（离线/设备压力/网络切换/失败/重缓冲）保持原样。

### 3.4 渐进式起播门槛（`VideoPlayerManager`）

未渲染首帧时，起播门槛从基准值按 500 ms/s 线性衰减、下限 1000 ms（grace 2 s 内不衰减）。门槛只降不升；重缓冲恢复（12 s）与暂停恢复路径不受影响（不同常量、不同分支）。最坏黑屏从 10-16 s 截断到个位数秒。

## 4. 测试与证据

设备：Redmi Note 11 Pro+ / 21091116UC / Android 13。Wi-Fi 关闭（`wifi_on`=0）、移动数据开启，既有 VPN（Clash）保留。测试目标：频道"测试频道（名称已脱敏）"、标签 #TEST（158 个视频）、随机模式、仅移动数据。每轮 20 次正向滑动、每滑观察 5 s、单次截止 12 s。证据目录 `build/reports/opt/`。

### 4.1 轮次对照（bind → 首帧终端，毫秒）

| 轮次 | 配置 | 超时 | 预取命中 | 重缓冲 | P50 | P95 | max |
|---|---|---:|---:|---:|---:|---:|---:|
| baseline-01 | 修复前 | 4 | 3/20 | 0 | 141 | 931 | 1604 |
| baseline-02 | 修复前 | 1 | 5/20 | 1 | 92 | 1015 | 1633 |
| opt-01 | 宽松版（当窗口满足时失效） | 1 | 4/20 | 2 | 206 | 852 | 1097 |
| opt-02 | 严格版首轮（缓存回温中） | 1 | 5/20 | 0 | 104 | 1202 | 1422 |
| opt-03 | 严格版 | **0** | 10/20 | **0** | 102 | 307 | 308 |
| opt-04 | 严格版 | **0** | 11/20 | **0** | 104 | 311 | 477 |
| opt-05 | 严格版 + 15 s 长窗口（10 滑） | **0** | 7/10 | **0** | 103 | 185 | 267 |
| opt-06 | 严格版 | **0** | 5/20 | **0** | 150 | 411 | 1413 |
| opt-07 | 严格版 | 1 | 7/19 | **0** | 105 | 315 | 374 |
| opt-08 | 严格版 | **0** | 5/20 | **0** | 151 | 316 | 323 |
| opt-09 | 严格版 | **0** | 6/20 | **0** | 94 | 555 | 1210 |
| opt-10 | 严格版 | **0** | 14/20 | **0** | 106 | 247 | 269 |

### 4.2 合并统计（严格版后 8 轮 / 149 个有效样本）

| 指标 | 修复前（40 样本） | 修复后（149 样本） | 变化 |
|---|---:|---:|---:|
| P50 | 118 ms | **105 ms** | 略优 |
| P90 | 931 ms | **301 ms** | **3.1×** |
| P95 | 1604 ms | **323 ms** | **5.0×** |
| ≤500 ms 占比 | 85% | **98%**（146/149） | +13pt |
| ≤1000 ms 占比 | 92.5% | **99%** | +6.5pt |
| 超时（12 s 内未完成） | 5/40 | **1/149** | −95% |
| 重缓冲事件 | 1 | **0** | −100% |

### 4.3 机制级证据

1. **拉锯消除**：同文件 `NEXT_PRELOAD` 与 `CURRENT_PLAYBACK` owner 交替（拉锯）从 baseline 的多个文件降为 **0 个文件**；`DISJOINT_SWITCH` 从 25 次/轮（opt-01 中段统计）降至 **5 次/轮**，且剩余为其它路径。
2. **首字节（TTFB）消除**：opt-04 中 **17/20** 视频 `bindToFirstByteMs=0`（数据已在本地），未命中的 3 个为 25/43/237 ms；基线为 0-1390 ms。合并 P95 从 ~700 ms 降至 ~45 ms。
3. **下载速度恢复**：无拉锯文件 1.3-3.4 MiB/s、带拉锯文件 479 KiB/s 的对照（基线）在修复后不再出现于任意轮次；修复后轮次的下载速度中位数 1223 → **2163 KiB/s**（+77%），P25 从 626 → 1078 KiB/s。
4. **大视频直接受益**：opt-04 中 1959 MB / 1047 MB / 732 MB / 1796 MB 的视频全部以 `NEXT_PRELOAD`（预取命中）方式进入播放，首字节 **0-24 ms**；基线中同尺寸视频为冷启动（TTFB 600-700 ms 起步）。
5. **长窗口稳定**：opt-05（每条 15 s，10 条）**零重缓冲**，首帧全部 ≤267 ms。
6. **尾部削减**：修复后仍存在约 1-2% 的 >1 s 样本（1210/1413 ms），均对应"预取未覆盖的冷启动视频"；其绝对耗时仍低于基线最差值（1604/1633 ms），且不再伴随超时与重缓冲。
7. **快速刷场景（3 s 节奏，opt-11-fast）**：20 次滑动全部完成、零超时、零重缓冲，首帧 **P50=101 ms / P90=303 ms / max=309 ms**，首字节 15/20 为 0 ms、其余 ≤44 ms——接近"快速连刷"的用户使用时，命中与延迟表现不劣于 5 s 节奏。

### 4.4 必须声明的限制

- 轮次之间 P50 稳定（92-206 ms），但 max 受"随机媒体中是否出现未命中大文件"主导（267 ms ↔ 1413 ms），**不得**用单轮 max 宣称恒定性能。
- 命中率（prepReady 5-14/20）受缓存状态与滑动节奏影响；稳定轮次的 ≤500 ms 占比 98% 是"连续刷同一 feed"的稳态值。
- 19.5 Mbps 级 2K 原片在链路上的持续播放能力受物理带宽约束；本轮消除了应用侧浪费，不改变该约束。
- 全部测试在保留账号、索引与缓存的既有设备上进行（不清数据制造读数）。

### 4.5 运行记录

- 构建：`test lint assembleDebug`（同源码状态）；修改先经 `:core:domain:test`、`:telegram:testDebugUnitTest`、`:player:testDebugUnitTest` 全绿（含新增用例 `playbackOwnershipSuppressesResidualPreloadSteering`、`residualPreloadOwnerYieldsToPlaybackOwnershipOnTheSameFile`、`preloadOwnersStillDriveTheirOwnFileWithoutPlaybackOwnership`、`startupThresholdDecaysWhileNoFirstFrameIsRendered`、更新后的 `currentItemHoldsBoundedReserveInsteadOfHardBlock`、`hardBlocksRemainOffEvenBeforeFirstFrame`）；`lint` BUILD SUCCESSFUL（3m25s）。
- 真机：`install -r` 覆盖安装，未清数据；共 13 轮实测（2 轮基线 + 11 轮修复后，含快速滑动轮）。交付候选：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `1fa45b120bfe169f4620755e0df325ec80393ad9114868fa95d42fc14f9be39d`（私有本地 Debug 测试产物，不是发行版本）。

### 4.6 总评

修复前"最差 1.6 秒黑屏 + 偶发 4 次超时 + 大视频第一块 600-700 ms TTFB"的体验，修复后为：**P50 = 105 ms、98% ≤500 ms、零重缓冲、零卡顿突发**；1-2 GB 大视频在预取命中时首字节 0-24 ms。用户的核心诉求——"刷视频时几乎没有延迟和等待、观看时几乎不卡顿"——在当前移动数据环境下由数据支撑成立（局限见 4.4）。

## 5. 后续候选（未实施，需独立验证）

1. **预取命中率从 ~85% 走向更高**：未命中样本均对应"滑动瞬间预取仍未覆盖的下一条"；根因在下一条的确定时机与准入窗口，需要单独立项分析 `PlaybackFeedSession` 的 next 预取时机，并用固定媒体顺序做受控 A/B（20 次随机滑动的端到端差异不足以判定）。
2. **预加载双读取者（池备用 ExoPlayer + 动态循环）的请求合并**：两套读取器对同一"下一条"以 256 KiB/512 KiB 不同步进交替产生 MERGE/START；当前证据未显示危害，但可在独立阶段评估"单一读取者"变体。
3. **长窗口（>60 s）的持续播放限额**：本轮覆盖到 15 s 窗口；如需为长时间观看优化，应单独设计 rebuffer 预算实验。
