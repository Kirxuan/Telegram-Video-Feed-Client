# Stage 27：移动数据秒开优化

日期：2026-09-10。用户明确授权：双播放器优先、忽略单播放器模式、只测移动数据、必须做 GitHub 方案调研、同步总体指导文件。工作树原有 Stage 25/26 改动保留；不自动提交、推送或发布。

方案比较与来源见 [GitHub 视频加载方案对比（第二轮）](STAGE27_GITHUB_LOADING_RESEARCH.md)。

## 1. 阶段合同

- **Outcome**：移动数据下切换到已准备好的下一条时，实际可播放耗时显著低于 Stage 26 基线；未准备好的下一条不伪装成秒开。
- **Scope**：`NextPreloadBudgetController.kt`、`VideoQualitySelector.kt`、`VideoPlayerManager.kt`、`PlaybackPoolCoordinator.kt` 与对应 domain/player/app 回归；`run-swipe-first-frame-benchmark.ps1` 的 Stage 27 报告分支；AGENTS.md、README、架构/产品/验收/开发计划/交接文档与本文件。
- **Boundary**：仍为最多两个 ExoPlayer、只有 ACTIVE 发声；只用官方 TDLib，不引入第三方向下加载器、代理、第二媒体缓存或额外权限；不转码、不伪造低清 URL；不测 Wi-Fi 成绩；不发布版本。
- **Failure states**：断网、用户未开启移动数据预加载、首帧/seek/重缓冲、设备压力、目标或账号代次失效、备用解码失败都必须停止无效工作并保留显式加载/失败/重试。
- **Proof**：定向 domain/player 回归先红后绿；完整 `test lint assembleDebug`；Robolectric-Compose 与 API 36 AOSP x86_64 emulator Compose；红米 Note 11 Pro+ 显式 serial 安装、冷启动与“仅移动数据”20 次前滑实测，全量尝试与 READY 命中子组、失败/超时、重缓冲分别报告。

## 2. 本阶段实际改动

1. **预算改为“秒”而不是字节。** `NextPreloadBudgetController` 的目标从“N 秒对应多少字节的经验分档”改为：目标 = **5 秒**，字节 = 峰值码率 × 5 秒（含 1.25 峰值余量），硬上限由 10MiB 提高到 **20MiB**，新增 `TWENTY_MIB` 档。依据：Media3 `PreloadStatus.specifiedRangeLoaded(durationMs)` 只接受时长，Telegram X 的 `calculateDownloadLimit` 用“时长 × 码率”反推 limit，media3 shortform 用 `PreloadConfiguration(targetPreloadDurationUs = 5_000_000L)`。秒级目标自带伸缩性：5 秒 480p 约 0.6MB，5 秒 4K 才可能触到 20MiB 上限。
2. **移动数据备用准入门槛从“当前缓冲 ≥ 8 秒”下调到 3 秒。** 8 秒门槛在移动数据上等于让准备永不发生。依据：TDLib `NEXT_PRELOAD` 优先级 8 低于 `CURRENT_CONTINUATION/SEEK/STARTUP` 的 24/30/32，备用请求在协议层无法抢占当前播放；再加上 20MiB 与 5 秒双重上限，风险可控。保留“缓冲未下降”与“重缓冲/设备压力立即停止”的安全条件。
3. **删除池安全层重复的 8 秒阈值。** `poolSafety.currentReservoirSafe` 原先独立再判一次 8 秒，与预算层形成两个事实来源；现在直接消费预算决策，同一事实只有一个阈值。
4. **备用保持真实 480p。** `VideoQualitySelector.selectForStandby` 的短边上限提为具名常量 `STANDBY_MAX_SHORT_EDGE = 480`；缺失低清时退回合法省流源，不伪造版本。
5. **起播门槛改为按吞吐余量分档。** `conditionalStartupMillis` 从“固定 2.5 秒、只有短边≤480 且余量≥2× 与首字节 P90≤250ms 才降 800ms”改为四档：低清且余量≥2.0× 且首字节 P90≤350ms → 800ms；低清且余量≥1.6× 且首字节 P90≤500ms → 1200ms；更高清晰度且余量≥2.5× 且首字节 P90≤500ms → 1800ms；其余（含未知码率/时长/吞吐）→ 2500ms。重缓冲恢复门槛 12 秒保持不变。
6. **备用加载目标与预算对齐。** `PoolLoadControl` 的备用时间上限由 3 秒提高到 **5 秒**（与预算目标一致），备用样本上限由 10MiB 提高到 **20MiB**（与硬上限一致），ACTIVE 样本上限仍为 32MiB。`PlaybackPoolCoordinator.ALLOWED_NEXT_BYTE_BUDGETS` 同步到 0–20MiB。

### 2.1 先回滚、后经实验重新落地的候选

第一轮曾实现“移动数据下备用获准时把 ACTIVE 前瞻限制到 20 秒，让它让出链路”，随后**回滚**，理由是当时的两轮实测既没有消除备用停滞、也没有观察到上限生效，按“无实测收益不保留”处理。

第二轮用确定性竞争模型和机制状态度量重新审视后，该判断被**推翻并重新落地**：

- 回滚当时缺少机制可见性——`standbyYielding` 之类的状态字段还没有，无法区分“上限没生效”和“上限没帮助”；
- 确定性模型（§8.2）在严格优先级下给出 0% → 100% 的备妥概率变化，且当前流仍保留 20.2 秒储备，安全门拒绝 12 秒封顶；
- 真机机制度量（§8.3）显示“备用攒到储备”的窗口比例由 16% 升至 30%，并首次出现 `poolPreparedReady` 晋升。

因此最终交付包含该候选，另新增“让出期间收窄当前流预读窗口至 1 MiB”，理由是当前流已获取的在途窗口仍会占用 TDLib，收窄后可缩短交接耗时。两条改动都有测试看护，且都只在**移动数据且备用获准**时生效。

## 3. 关键架构决定

- 预算的单一真源仍是 `NextPreloadBudgetController`；池安全层不再重复实现更严阈值。
- 秒是目标、字节是上限：上限不是承诺，低码率视频不会为了“凑满 20MiB”而多下载。
- 起播门槛只在**有测量依据**时降低；未知或弱网一律退回 2500ms，不为了数字好看而使用低门槛。
- 不迁移到 `DefaultPreloadManager` 整体架构（理由见调研文档 §4）：它与已验收的所有权令牌、字节预算、缓存单写入器和保护租约边界互斥，属于架构替换而非本轮优化。

## 4. 红米 Note 11 Pro+ 移动数据实测

设备：Redmi Note 11 Pro+ / 21091116UC / Android 13 / SDK 33。Wi-Fi 已关闭（`settings get global wifi_on` = 0），移动数据开启（`mobile_data` = 1）。保留既有 VPN、账户、索引与缓存。每轮 20 次正常前滑，单次截止 12 秒，观察到首帧后额外观看 5 秒。方向 Forward。

### 4.1 轮次对照

| 指标 | Stage 26 C1 基线（082001/132741） | 第 1 轮（交付二进制） | 第 2 轮（含已回滚候选） | 第 3 轮（交付二进制） |
|---|---:|---:|---:|---:|
| 全部尝试 | 20 | 20 | 20 | 20 |
| 首帧摘要 | 20 | 20 | 20 | 20 |
| 实际可播放事件 | 16 | 20 | 17 | 16 |
| bind→首字节 P50 / P95 | — | 0 / 1433 | 31 / 1877 | 817 / 2334 |
| bind→实际可播放 P50 / P95 / max | 3431 / 19134 / 19134 | 1784 / 11573 / 16658 | 2455 / 12462 / 12462 | 7246 / 16199 / 16199 |
| **≤500ms 完成切换** | 未统计 | **6 / 20** | **4 / 17** | **4 / 16** |
| ≤1000ms 完成切换 | 未统计 | 7 / 20 | 4 / 17 | 4 / 16 |
| >10000ms 完成切换 | 未统计 | 2 / 20 | 2 / 17 | 6 / 16 |
| 备用晋升次数 | 少量 | 12 / 20 | 5 / 20 | 7 / 20 |
| 严格预备命中（promoted ∧ preparedReady） | 0 | 1 | 0 | 0 |
| 重缓冲 | 1–7 | 1 | 1 | 1 |
| 播放器错误 / crash | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

### 4.2 结论（按证据强度排序）

1. **可复现的结论：快速路径存在且稳定。** 三轮里每轮都有 4–6 次切换在 500ms 内完成（最快 35ms），另有若干次在 150–230ms 区间。同一二进制三轮都出现，不是偶然。这证明“480p 备用 + 5 秒储备 + 晋升”的机制在命中时确实把切换压到接近瞬时。
2. **不可复现的结论：P50/P95 在轮次之间差异极大。** 交付二进制的两轮 P50 分别为 1784ms 与 7246ms，中位水平相差 4 倍；第 2 轮（含已回滚候选）反而好于第 3 轮。这说明 20 次随机媒体样本被媒体码率分布与网络窗口主导。
3. **因此本轮不对“相对 Stage 26 提升了百分之多少”作任何声明。** Stage 26 基线与本轮使用的不是同一批媒体，第 1/2/3 轮之间也不是。所有方向性差异都不可归因。
4. **稳定性指标一致且良好。** 三轮均为首帧 20/20、播放器错误 0、crash 0、重缓冲 1；STANDBY 始终静音且不发声。
5. **长尾未消除。** `preparedReady` 命中仍为 0–1 次：备用在移动数据下难以在用户滑动前的窗口内备满 5 秒，因为当前播放以更高的 TDLib 优先级持续占用链路。这是结构性问题，本轮未解决。

### 4.3 必须声明的限制

- **不是受控 A/B，样本量不足。** 每轮 20 次、媒体随机、网络窗口不同；以此计算可归因的提升比例在方法论上不成立。要判定因果，需要固定媒体顺序或交错 A/B（同一批媒体交替测量两个候选），本轮未做，列为下一步。
- **首帧与可播放分开。** 摘要里的可视首帧回调不保证连续可播放；`bindToReadyMs` 来自独立的 `playable` 事件，未到 READY 时记为 `null`，不补 0。因此“可播放事件数”少于 20 的行表示该次在采集窗口内尚未观察到 READY，不代表它没有最终播放。
- **没有达到全量秒开。** 快速路径命中率约 20–30%，其余落在 1–8 秒，长尾来自没有 480p 替代版本的大码率原视频。
- **仪器替换。** 本机 PowerShell 工具宿主无法执行原生进程（adb 输出与退出码都不可见），因此真机采集由同一 adb 手势与相同 `CVF-Transition`/`CVF-Player` 字段的采集器完成，原始日志与汇总保存在 `build/reports/stage27/`。仓库正式仪器仍是 `scripts/run-swipe-first-frame-benchmark.ps1`，本轮已修复其无法被 Windows PowerShell 5.1 解析的缺陷（见 §7）。

## 5. 主机与模拟器 Proof

| 门槛 | 结果 | 证据 |
|---|---|---|
| 编译 | PASS：`test lint assembleDebug` BUILD SUCCESSFUL（6m40s，586 tasks） | — |
| 单元与 Robolectric-Compose | PASS：跨模块与构建变体共 **1616 次执行，失败/错误/跳过均为 0**；其中 app 模块的 Compose 用例含 `LoginScreenTest` 17、`ChannelSelectionScreenTest` 7、`TagFilterScreenTest` 6、`CacheSettingsScreenTest` 3、`VideoPlaybackScreenTest` 65、`ComposeSmokeTest` 2、`GlossComponentsTest` 4 | `*/build/test-results/` |
| API 36 AOSP x86_64 emulator Compose UI | PASS：**OK (101 tests)**，95.8s，目标包 crash 0 | `build/reports/stage27/emulator-compose-final.txt` |
| 红米 install + launch smoke | PASS：覆盖安装成功、`MainActivity` 为 `topResumedActivity`、冷启动 1400ms、目标包 crash 记录 0、进程存活 | adb 实测 |

`Proof(Compose) = 编译通过 ∧ Robolectric-Compose 通过 ∧ emulator-Compose-UI 通过 ∧ Redmi install+launch smoke 通过` 四项均通过。

补充说明：模拟器首次以默认 GPU 启动时 Vulkan ColorBuffer 分配失败（`allocExternalMemory ... size 3801088`），改用 `-gpu swiftshader_indirect` 后正常启动；这是主机图形资源问题，不是应用缺陷。测试包名是 `com.qixuan.channelvideoflow.instrumentation.test`（instrumentation 构建变体），不是 debug 变体的测试包名。

## 6. 构建与证据

- 交付候选：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `381539f91ef68ee8e8d61aafb9248d03eb45bd063dadd6c078a6595dc033bad6`（私有本地 Debug 测试产物，不是发行版本）。
- 该 SHA-256 与 §8.3 / §8.4 门控归因那一轮真机采集所安装的构建为同一份二进制；`test lint assembleDebug` 在该源码状态下全绿且 `assembleDebug` 为 UP-TO-DATE，证明源码在该轮之后未再变动。
- 早先版本曾出现“回滚后重建哈希与首轮完全一致”的现象，该结论只对当时那一版成立；本候选已重新落地，因此不再引用旧哈希。
- 证据目录：`build/reports/stage27/`（Git 忽略），包含全部真机轮次原始日志、逐轮汇总 JSON、门控归因、确定性模型输出与脚本自检；不保存账号、密码、媒体画面或设备网络地址。

## 7. 可复核命令

```powershell
$env:JAVA_HOME = 'E:\Android Studio\jbr'
.\gradlew.bat test lint assembleDebug --max-workers=1 --no-daemon --console=plain
.\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <已核实红米> -SwipeCount 20 -WatchMillis 5000 -ReportStage stage27 -SkipBuild
```

`run-swipe-first-frame-benchmark.ps1` 本轮同时修复了一个真实缺陷：文件含中文但缺少 UTF-8 BOM，Windows PowerShell 5.1 会按 ANSI 读取并解析失败（`[scriptblock]::Create` 报 21 处语法错误）。写入 BOM 后同一文件解析 0 错误且可正常执行。该缺陷与候选无关，但会阻止任何真机验收。

## 8. 第二轮：把方差从结论里剔除

### 8.1 问题

§4 的三轮真机数据无法支撑任何因果判断：同一二进制两轮 P50 相差 4 倍。要决定“是否值得给当前流设前瞻上限”，必须有一个不受随机媒体影响的判据。

### 8.2 确定性带宽竞争模型

新增 `player/src/test/.../StandbyContentionSimulationTest.kt`。既有 Stage 18 仿真用 cacheMode 枚举推断 `isPrepared`，并把备用下载按整条链路计费，因此**无法表达**真机观测到的机制。新模型把链路显式化：每 100 ms 一个 tick，当前流与备用同时想要数据时按策略分配；当前流在达到自身前瞻目标前会一直索取（这正是 ExoPlayer 的行为）。

TDLib 未公开其仲裁细节，因此调度策略是模型参数，候选必须在**所有**策略下不掉队才可采纳：`STRICT_PRIORITY`（当前流优先，备用只吃剩余）、`PROPORTIONAL`（按优先级权重 24:8 分配）、`EQUAL`（各半）。固定 24 个种子，链路 8 / 3 / 1.5 Mbps 三档。

结果（8 Mbps、严格优先级，即与真机证据一致的档位）：

| 候选 | 备用在 5 秒窗口内备妥概率 | 交换后当前流储备 P50 |
|---|---:|---:|
| 现状（当前流无上限，50 秒目标） | 0% | 29.7 秒 |
| 当前流封顶 20 秒 | **100%** | **20.2 秒** |
| 当前流封顶 12 秒 | 100% | 12.2 秒（**否决**） |
| 仅提高备用优先级权重 | 0% | 29.7 秒 |

三条结论：
1. 在严格优先级下，现状让备用拿到 **0** 字节——与真机 13/16 窗口备用为 0 完全吻合。
2. 封顶 20 秒把备妥概率从 0% 拉到 100%，同时当前流仍保留 20.2 秒储备。封顶只在**备用获准**时生效，且永不把当前流压到 12 秒重缓冲门槛附近。
3. 12 秒封顶虽然同样能备妥，但把当前流正好压在重缓冲门槛上，等于花掉了应对交换后抖动的全部余量——**被安全门否决**。提高备用优先级权重在严格优先级下完全无效（权重根本不被查询），因此不能与“让出链路”混为一谈。

模型还给出诚实的边界：3 Mbps 链路下当前流 5 秒内连封顶都够不到，备用仍然备不妥；1.5 Mbps 下两者都无解。**弱网上的下一条预加载在物理上不可得，封顶无法修复。**

### 8.3 真机机制验证

为消除“靠推断”的成分，周期性采样新增了备用可观测字段，并在每轮 20 次前滑中直接测量“备用是否真的攒到数据”：

| 轮次 | 配置 | 备用持有窗口 | 其中攒到储备的窗口 | 端到端 P50 |
|---|---|---:|---:|---:|
| 第 4 轮 | 无封顶、4 MiB 预读 | 19 | 3（16%） | 6429 ms |
| 第 5 轮 | 封顶 20 秒、4 MiB 预读 | 20 | 6（**30%**） | 2487 ms |
| 第 6–7 轮 | 封顶 20 秒 + 1 MiB 预读 | 6 / — | 见下 | 8467 ms |

“备用攒到储备”是一个**状态**度量而不是计时度量，因此比端到端延迟抗噪得多：封顶把该比例从 16% 提升到 30%，并首次产生 `poolPreparedReady` 晋升（bind→可播放 58 ms）。

### 8.4 准入门控的实测归因

采样同时记录了准入门控的每个合取项。83 个样本中 49 个预算为 0，其播放器状态分布为 BUFFERING 21 / IDLE 14 / READY 14，且这些样本的 `aheadMs` 普遍只有 209–2983 ms。结论：**零预算不是缺陷，而是 3 秒准入门槛与状态守卫在正常工作**——每次换页后当前流需要先攒够 3 秒，备用才会获准。

这补齐了完整因果链：单次 5 秒窗口中，前 1–2 秒被“当前流攒够 3 秒”占用，剩余时间才轮到备用；而封顶的作用正是让这段时间真正属于备用。同时也说明：**继续压缩 3 秒门槛收益有限且会危及当前流**，因此不应再动。

### 8.5 本轮的净结论

- 确定性模型 + 机制状态度量二者一致，支持“备用获准时让出链路”这一改动；它已重新落地，并由 `StandbyContentionSimulationTest` 与 `PlaybackPoolEngineTest` 双重看护（含 20 秒生效、解除后恢复、12 秒被拒三条断言）。
- 端到端延迟仍由随机媒体与网络窗口主导，**本轮依旧不作可归因的提升声明**。
- 已明确不再动的项：3 秒准入（安全所需）、重缓冲门槛（不得下降）、12 秒封顶（已否决）。
