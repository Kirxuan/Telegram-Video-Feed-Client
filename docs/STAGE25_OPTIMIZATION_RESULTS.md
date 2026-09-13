# Stage 25 优化实施结果与交接

日期：2026-09-09。正式版本仍为 **Stage 24 / VELORA 1.1.0**；本文记录 `main` 上未提交的 Stage 25 工作树候选，不是发布说明。用户本次明确授权连续完成 A–F，覆盖历史逐阶段审批；禁止操作实体机、提交、推送和发布。

## 一、本阶段目标

优化从切换意图到正确显示帧的客户端路径，同时限制重缓冲、seek、内存、Codec、流量和后台开销。交付真实固定双播放器候选及可归因的单播放器对照，保持原画偏好、官方 TDLib、500MB 默认私有媒体缓存、账号隔离与内容保护。

本次证据包含主机逻辑、构建、静态检查、API 36 AOSP x86_64 模拟器 UI/Room 和合成素材 Media3/EGL 测试；Redmi 仅执行移动数据播放与生命周期验收。**没有真实双 Codec 性能成绩，不宣称达到 P95 或能耗门槛。**

## 二、实际完成内容

### 阶段合同与交付

| 阶段 | Outcome | Scope（主要实现） | Boundary | Failure states | Proof |
|---|---|---|---|---|---|
| A | 指标可解释，数据库空/失败可区分，最低 SQLite 语法兼容 | Repository、MediaCacheEntryDao、Feed 状态、首帧统计与 benchmark 脚本 | 不把观察间隔/请求长度当 SQL 时间/网络流量 | 错误保留最近内容；最多三次订阅，取消传播，手动恢复 | DAO/Repository/VM/指标脚本测试 |
| B | 安全准备唯一下一目标，排队和预算有界 | NextPreloadBudgetController、VideoPreloadManager、FileUpdateConflator、MediaCacheMetadataWriter | 不扩大预加载窗口，不建第二份磁盘缓存 | 短片/尾段/完整剩余时间线与真正下载不足分开；计费网络/设备压力阻断 | domain、player、telegram 单测 |
| C | 目标代次、准备所有权与最终绑定一致 | PlaybackTargetCoordinator、PlaybackPreparationContext、PlaybackPoolCoordinator、ViewModel 接入 | 不机械拆分全部 Screen，不引入平行架构 | 旧账号/质量/队列/网络代次拒绝；取消/恢复保留预算 | Coordinator/VM/生命周期单测 |
| D | 两个实际 ExoPlayer 的 C1/C2 可运行，可靠回退 | ReusablePlayerLifecycle、VideoPlayerManager、StandbyVideoSurface、PlaybackPoolEngineTest | 默认单播放器；不允许第三实例、不叠加 SampleQueue | 备用准备/Codec 错误退单播放器；EGL 失败退 C1；后台释放 | 实际 Media3 模拟器 2 项 + 池单测 |
| E | 保持表示层和 seek 正确，减少 Feed 水合与排序开销 | HLS/MP4、ABR、DataSource/session、PlaybackFeedSession、精确窗口 DAO | 不默认 HLS 更快，不降低原画，不盲目改启动缓冲 | HLS 单次回退；旧 seek 取消；删除/随机边界重排 | HLS/seek/100k 键/随机轮次与 Room 测试 |
| F | 可构建候选、安全检查、响应式 UI 与交接 | Screen/导航、benchmark、R8/加密独立脚本、依赖验证、CI、native 脚本、文档 | 不签正式包、不发布；不把入口 Profile 当完整播放 Profile | 加密失败关闭；旧库阻断；加载/空/失败独立 | lint、构建、脚本、Compose 尺寸矩阵、ELF/APK 检查 |

### 17 项现状核实

| 项 | 本次核实与处理 |
|---|---|
| 1 | 已有 Telegram HLS 安全 parser、direct MP4 回退、混合 ABR 和动态下一条预加载，继续复用。C1/C2 与基线采用相同表示层选择，不强制 MP4。 |
| 2 | SampleQueue 默认关闭；独立 SampleQueue APK 可构建，真实 Media3 DefaultPreloadManager/PreloadMediaSource 路径已接入。 |
| 3 | 接手候选主要为池状态合同；本次已接入两个实际 ExoPlayer、切换与输出交接，不再只有 Fake。 |
| 4 | Owner Promotion 保持关闭。历史 1599ms 是特定实验 bind→首帧 P90，未改称当前 P95。 |
| 5 | Stage 17/18 确定性模拟仍只标为模拟；本次重新运行的模拟测试也不代表真机收益。 |
| 6 | 完整剩余时间线允许自然缓冲下降；短片与尾段可安全准备下一条。真正负缓冲斜率、当前卡顿/seek/暂停/设备压力仍阻断。 |
| 7 | 未缓存请求在发送前记账；取消不退款。动态预加载与样本/池账本都跨同一目标尝试保留。请求、缓存覆盖、取消字节分别报告，不等于真实网络字节。缓存写入计数只在成功事务后累计，进度事件不再输出伪造的零写入数。 |
| 8 | 同一目标/上下文已有准备不因预算档位变化反复重建；安全阻断可取消，恢复后有界重试。备用 read-ahead 限定为已记账长度，防止 TDLib 额外预取漏记。 |
| 9 | Feed 为轻量全量键 + 窗口完整元数据水合。已排序结果只线性校验；仍保留 O(N) 键和当前/下一随机轮次，不声称 O(1) 或任意规模无成本。 |
| 10 | 窗口查询按 chatId 分组消息 ID，以准确复合键查询，避免频道集合 × 消息集合交叉多取。 |
| 11 | RepositoryObservation 显式数据库错误已经接入 UI；兼容 List 接口仍保留给旧调用，主 Feed/标签等采用显式接口。持续每秒重试改为最多三次，后续由用户重试。 |
| 12 | 缓存元数据最多 256 个待写键，按 fileId 合并，100ms 批窗口，一批 Room 事务、三次重试。删除集合有界；退出清理屏障失败后阻止新账号写入。元数据近似 LRU 不冒充媒体容量真值。 |
| 13 | 初次订阅结果延迟命名为 firstEmissionMs（包括调度，不是纯 SQL），后续间隔单独 observationGapMs，水合单独 hydrateMs。未测量项输出 null，不把 0ms 注入百分位。 |
| 14 | 移除 SQLite 3.24 UPSERT；事务内使用旧系统支持的 INSERT/UPDATE。API 36 Room 通过；API 26/28 真正系统 SQLite 运行尚未验证。 |
| 15 | Baseline Profile 生成入口仍只到无需账号的凭证页；本次未生成/验收授权后播放 Profile，没有宣称完整覆盖。 |
| 16 | ELF PT_LOAD 对齐与 offset/vaddr 同余、APK 未压缩条目 16KiB 对齐分别验证，并用 zipalign 交叉检查。16KiB ARM64 运行时尚未验证。 |
| 17 | 沿用已有频道继续浏览、标签搜索/多选、加载/空/失败、设计令牌与无障碍改善；现有横屏修复将视频按视口 fit，并将全屏按钮限制在底部信息上方。本次重新安装后定向及完整横屏组均通过；旧失败日志早于该修复及 APK 更新时间。共享用例新增完整 Pager 边界、48dp 实际像素高度和触摸进入/退出全屏断言。 |

### 交付状态分类

- **已实现并验证**：数据库兼容写入、显式错误/有限重试、Feed 窗口与复合键、预加载准入与请求账本、合并写入、会话/池所有权合同、指标汇总、主机构建与允许的模拟器 Proof（具体结果见第六节）。
- **已实现但真实设备尚未完整验证**：C2、SampleQueue 对照、双 Codec 显示/音频交接、HLS/MP4/seek 与资源回退；Redmi 移动数据单播放器安全路径已执行，Wi-Fi、iQOO 12、功耗/温度/PSS 增量仍尚未验证；R8 和 TDLib 新库加密候选保持独立。
- **经证据审查不采用**：历史负向 Owner Promotion；只用 PlaceholderSurface 就声称预解码完成；用更低画质、更热缓存或仅成功样本宣称全量收益。未做 C1/C2 真机优劣判断。
- **外部条件阻塞或明确未完成**：实体机各项性能和安全运行验收；授权后 Baseline Profile 尚未生成；旧 TDLib 库加密迁移未实现，Room 未加密。Robolectric Android 16 的历史环境阻塞已在本次正常重跑中解除，见第六节；其余未完成项不记为已完成能力。

## 三、修改文件

累计修改覆盖 `app`、`core/domain`、`core/database`、`player`、`telegram`、`scripts`、`benchmark`、Gradle、CI 与 docs。接手前候选在原工作树继续，没有从旧 HEAD 建平行版本。完整累计清单见 [修改文件表](STAGE25_CHANGED_FILES.md)，其中包含用户原有未提交候选，不将其全部归为本轮新写。

主要职责：

- `PlaybackFeedSession`：复合键窗口、随机轮次、边界不重复、删除重排。
- `PlaybackTargetCoordinator`：pager 目标和稳定/不稳定状态；ViewModel 负责将会话上下文传给播放器接口。
- `ReusablePlayerLifecycle` / `PlaybackPoolCoordinator`：真实引擎所有权与硬上限/身份校验。
- `VideoPlayerManager` / `StandbyVideoSurface`：预算准入、C1/C2、显示/音频交接、失败回退。
- `CappedNextSampleGateway` / `SampleRequestBudget`：发送前预算、缓存覆盖、取消不退款、禁止越界 read-ahead。
- `FileUpdateConflator` / `MediaCacheMetadataWriter`：进度合并、独立控制事件、有界批量写入与退出屏障。
- `SecureTdLibDatabaseKeyProvider`：独立 TDLib 新库加密候选，不加密 Room。
- 构建/验收脚本：四种播放候选、R8、加密、APK 体积、安全与 native 对齐报告。

## 四、关键架构决定

1. 生产默认启用有界 C1 双播放器。SampleQueue、C1、C2 构建互斥；Owner Promotion 保持关闭。移动数据、重缓冲和设备资源压力会释放 STANDBY；C2 Surface 失败降为 C1。
2. C1 无持久备用 Surface，只能证明媒体/轨道等准备，不能声称已经硬件预解码。C2 以 HandlerThread + Media3 EGLSurfaceTexture 消费真实离屏 Surface 帧，暂停状态下准备首帧；不隐藏持续播放。仅可保存内容使用 C2，保护内容使用 C1，EGL 失败也退 C1。
3. 旧 ACTIVE 先暂停、撤销焦点、清空；验证完整目标/账号/质量/网络/队列与轮次后交接 STANDBY。唯一 ACTIVE 发声，最多两个实际引擎。后台/退出/释放关闭备用引擎、请求与 EGL 资源。
4. ACTIVE 首帧通过 AnalyticsListener 校验输出为当前可见 PlayerView 的 SurfaceView，拒绝离屏和旧代次回调。窗口安全覆盖当前页面及未退役绑定，在保护画面绑定前启用。此回调是显示 Surface 渲染代理，物理屏幕最终呈现尚需设备复核。
5. Byte preload、SampleQueue、双池各有独立对照，池接受待准入目标后不再同时启动另一套字节准备。所有字节仍经过同一 TDLib Gateway/所有权缓存，无第二份完整媒体缓存。
6. 不采用仅 PlaceholderSurface 作为 C2 完成证据，已用实际消费 EGL 帧的 Surface 替换；不采用强制 MP4、降低原画或全局降低缓冲阈值来制造收益。
7. Feed 暂保留轻量全量键和两轮随机数组，以维持一轮不重复、边界不重复、删除重排与反向语义；未引入缺乏证明的数据库随机游标大改造。
8. R8/资源压缩和 TDLib 加密均独立候选、默认关闭。新空 TDLib 库可生成 32 字节随机密钥，由 Keystore AES-GCM 封装；旧非空库无密钥返回 MigrationRequired，损坏密钥返回 Unavailable，失败关闭。旧库两阶段迁移未实现，Room 尚未加密。
9. 全屏操作的回归测试比较真实像素高度与按当前 density 换算的 48dp，避免绝对 dp 坐标相减产生 `47.99997dp` 误判。模拟器取证实际高度为 126px，等于 420dpi 下的 48dp；没有通过减小触摸目标或删除可见性断言使测试通过。

## 五、执行的命令

本机 JDK 21（Java/Kotlin 17 字节码）、Gradle 8.13、SDK 36.1/target36。执行时使用工作区 Gradle cache；不输出 local.properties 内容。

```powershell
$env:JAVA_HOME='E:/Android Studio/jbr'
$env:GRADLE_USER_HOME='E:/Telegram Android Developer/.gradle-user-home'
.\gradlew.bat :core:domain:test :player:testDebugUnitTest :telegram:testDebugUnitTest lint assembleDebug :app:assembleRelease --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :app:assembleInstrumentation :app:assembleInstrumentationAndroidTest :player:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.qixuan.channelvideoflow.feature.video.VideoPlaybackViewModelTest' --tests 'com.qixuan.channelvideoflow.feature.video.PlaybackTargetCoordinatorTest' --offline --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat test lint assembleDebug :app:assembleRelease :app:assembleInstrumentation :app:assembleInstrumentationAndroidTest :player:assembleDebugAndroidTest --continue --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat test lint assembleDebug :app:assembleRelease :app:assembleInstrumentation :app:assembleInstrumentationAndroidTest --continue --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process' # 本次收尾最终复跑
.\scripts\run-emulator-compose-tests.ps1 -Serial emulator-5580 -AvdName CVF_STAGE25_API36_X86_64 -SkipBuild
.\scripts\run-stage25-emulator-layouts.ps1 -Serial emulator-5580
Get-ChildItem scripts/tests/*.Tests.ps1 | ForEach-Object { & $_.FullName }
.\scripts\build-stage25-playback-experiment.ps1 -Candidate Baseline # 同样构建 SampleQueue、PoolC1、PoolC2
.\scripts\build-stage25-r8-candidate.ps1
.\scripts\build-stage25-tdlib-database-encryption-candidate.ps1
.\scripts\verify-stage25-release-boundaries.ps1
& 'E:/Python/python.exe' scripts/verify-native-pages.py app/build/outputs/apk/release/app-release-unsigned.apk --output build/reports/stage25-experiments/native-pages.json
```

Room 和播放器 instrumentation 仅用 `adb -s emulator-5580` 对对应测试包运行 AndroidJUnitRunner；模拟器没有运行 TDLib ARM64 native smoke。全量 test 包含固定 Robolectric-Compose 类，历史未进入断言的依赖错误与本次通过记录分别保留。所有命令具体结果以以下证据表为准。

## 六、测试结果

| Proof | 本轮结果 | 证据 |
|---|---|---|
| core model / domain | 6 / 72 项通过 | closeout-host-counts.json |
| player | debug/benchmark/release 各 128 项通过（同一组不同变体，不累计成独立用例） | closeout-host-counts.json、XML |
| telegram | debug/benchmark/release 各 165 项通过 | closeout-host-counts.json、XML |
| ViewModel + TargetCoordinator | 精确筛选 90 项通过 | final-app-proof.log、targeted-counts.json |
| 全量 test | 最终组合门禁返回 0：app benchmark/debug/release 各 146 项、instrumentation unit 250 项，failures/errors/skipped 均为 0；历史 27 条依赖初始化失败仍保留为历史记录 | closeout-final-proof.log、closeout-host-counts.json；历史 final-full-host.log、all-host-test-counts.json |
| lint、debug/release、测试 APK 编译 | 最新测试修改后的最终组合门禁通过，退出码 0 | closeout-final-proof.log |
| 常规 Compose | 最终 101/101 通过 | compose-final.log |
| 小屏大字 / 横屏 / 平板 | 最终各 40/40 通过；960×1600 / 480dpi / font 2.0、1920×1080 / 420dpi / font 1.0、1600×2560 / 240dpi / font 1.0 | layouts/*.log |
| Room / migrations | 接手前 18 项通过；本次未改数据库实现，沿用该模拟器记录 | room-emulator.log |
| 实际 C1/C2 引擎与 EGL | 接手前 2 项通过；本次未改播放器实现，沿用 C2 暂停离屏首帧、四次交接、最多两实例、最终释放至零记录 | pool-engine-emulator.log |
| 五组 PowerShell 契约 | 本次全部通过，含失败/超时、READY 命中分组、null 指标不计零值 | closeout-script-tests.log |
| Release 权限/备份/默认开关/凭证反向扫描 | 通过，APK 内本机凭证命中 0 | stage25k/release-boundaries/ |
| 四种播放候选 | Baseline、SampleQueue、PoolC1、PoolC2 本次均构建通过，互斥开关正确、Owner Promotion 关闭；大小和哈希另表 | closeout-*-build.log、STAGE25_ARTIFACTS.md |
| R8 / 资源压缩候选 | 本次构建通过：40,849,255 → 30,471,752 字节，减少 10,377,503 字节 / 25.40%；仅体积收益，运行时未验收 | closeout-r8-build.log、stage25i/ |
| TDLib 数据库加密候选 | 本次构建通过，35 项 Client/密钥提供者定向测试通过；未安装候选，旧库迁移和真实 Keystore/TDLib 打开尚未验证，Room 未加密 | closeout-encryption-build.log、closeout-encryption-test-counts.json |
| native 16KiB 静态 | 三个 .so 的 ELF PT_LOAD 与 APK 条目对齐均通过，zipalign -c -P 16 4 通过 | native-pages.json |

报告目录：`build/reports/stage25-experiments/`。确定性策略模拟输出另存为 `policy-simulation.txt`，只能证明模型/策略断言，不是 C1/C2 真机 A/B 或真实网络成绩。APK 清单、大小和 SHA-256 见 [构建产物](STAGE25_ARTIFACTS.md)。

本次收尾在重新安装当前 APK 后验证了交接前已有的横屏定位修复：单例及横屏全组均通过。加强的触摸尺寸测试一度因 `543.619dp - 495.61905dp = 47.99997dp` 浮点误差失败；取证实际为 126px，改为与按 density 计算的 48dp 像素值比较后，三组尺寸和常规 101 项全部通过。测试没有删减可见性或交互断言。新旧 debug 签名不匹配时只重新建立两个模拟器 `.instrumentation` 测试包，没有安装或操作正式应用；尺寸脚本 finally 已恢复 1080×1920、420dpi、font 1.0。

历史主机全量测试的外部阻塞为 Robolectric Android 16 `android-all-instrumented:16-robolectric-13921718-i7` 依赖加载：缓存读取受限、远端解析遇到 `SocketException: Permission denied: connect`，相关类未进入业务断言。本次在用户补充联网授权后的执行环境中，以现有配置正常执行完整 `test` 已返回 0；没有修改 SDK、删除测试、放宽检查或复制受限缓存。不能将历史环境失败说成业务缺陷，也不能在本次通过后继续将其列为当前阻塞。[Robolectric 官方配置说明](https://robolectric.org/configuring/)解释了运行时依赖解析与离线目录配置；本次没有新增这些配置覆盖。

历史自动审批曾拒绝复制明确的 Robolectric 缓存 JAR 到工作区：审批服务返回 HTTP 404，提示所用 `gpt-5.6-luna` 模型不受支持。本次没有重试该复制或绕过拒绝；正常 Gradle 测试流程已能加载 Android 16 依赖，具体主机权限变化没有另行归因。

## 七、真机验证结果

**尚未验证。** 本次没有探测、安装、控制或测试 iQOO 12，也没有修改其网络、后台、省电或权限。首帧 P95、重缓冲、seek、PSS/Codec/图形缓冲、温度和能耗均无当前实体机证据。

模拟器的真实 Media3 双引擎和 EGL 暂停解码测试只证明该模拟环境上的可运行性；不代表 iQOO 12 的硬件解码、Surface、真实网络或功耗。完整仓库 Path B 仍因本次禁止实体机 install+launch smoke，不能整体标为通过；Robolectric 主机项本次已通过。

## 八、已知问题

- 未证明自然前向首帧 P95 改善 ≥15% 且 ≥75ms、准备命中 ≤150ms、能耗增量 ≤10% 和资源门槛；生产双池仍关闭。
- Feed 仍 O(N) 轻量键与两轮随机顺序；标签聚合仍依赖 Room SQL。没有真实大库端到端性能成绩。
- C2 保护内容走 C1；设备 Codec 不足会退单播放器。池协调器保存有界 owner-token 防重记录，单次长会话达到 512 个不同准备 token 后会安全退单播放器，需在后续长期浏览验收中明确计入。
- Baseline Profile 只具备安全入口生成路径；未生成/验收授权后频道和播放 Profile。
- TDLib 加密只支持新空库候选；旧库迁移未实现，Room 未加密。真实 Keystore/TDLib 打开、损坏恢复和升级矩阵尚未验证。
- R8 的运行时 JNI/账号恢复/播放验收、Android 26/28 运行、16KiB ARM64 运行时与远程 CI 执行均尚未验证。
- Robolectric 的历史外部依赖阻塞已解除；完整 Path B 的实体机 smoke 仍尚未验证，不由主机或模拟器结果替代。

## 九、安全检查结果

非 debug 构建强制空维护者凭证；release 关闭诊断；`local.properties` 未跟踪。仅两项权限、备份/迁移排除、ARM64 native 白名单与 SHA-256、默认开关通过静态脚本核验。没有在聊天、源码或日志中暴露真实 API Hash、验证码、密码、会话及数据库密钥；没有新增权限、Bot API、手写 MTProto、自建代理、公共媒体存储、遥测或上传。

仍为官方 TDLib → 适配器/Repository → ViewModel → Compose 分层。媒体字节只在内部缓存，Room 只保存元数据，当前和唯一下一条由所有权保护。受保护内容不提供导出；不支持流式视频仍明确拦截。

## 十、建议的下一阶段

待用户以后提供明确的专用设备窗口，按 [设备验收清单](STAGE25_DEVICE_ACCEPTANCE.md) 比较 Baseline / SampleQueue / C1 / C2，保留失败/超时及同条件证据。R8、Profile、旧库加密迁移、16KiB 运行时分别验收，不混入播放 A/B。没有提交、推送、签发正式版本或发布。
