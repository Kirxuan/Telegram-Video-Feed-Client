# VELORA（曜流）— 仓库协作规则

本文件适用于仓库根目录及全部子目录。开始任何修改前，必须先阅读 README.md，以及与任务相关的 docs 文档。

## 1. 项目定位

- 应用名称：VELORA；中文名：曜流。
- 包名：com.qixuan.channelvideoflow。
- 用途：供使用者以自己的 Telegram 个人账号和自己的 Telegram API 参数使用的原生 Android 频道视频浏览器。
- 正式发行基线：VELORA 1.2（2026-09-13 用户明确授权提交、推送与正式发布）。发布说明与本次验证范围见 docs/RELEASE_1.2.md；Stage 25–29 文档保留历史候选与测试上下文，不能用历史测试替代本版本尚未验证的设备项目。后续新阶段仍须用户明确批准。
- 仓库所有者确认已取得 Telegram 对本次无 sponsored messages/广告发行的书面例外许可；许可文件和任何账户信息不得提交到仓库。
- 未经用户明确批准，不得进入下一阶段。

## 2. 固定技术合同

- Kotlin、Jetpack Compose、Material 3。
- Kotlin Coroutines、Flow。
- AndroidX Media3 ExoPlayer。
- Room、DataStore。
- Telegram 官方 TDLib，使用个人用户账号授权；禁止 Bot API 和手写 MTProto。
- Gradle Kotlin DSL 与 gradle/libs.versions.toml 版本目录。
- MVVM、Repository、分层架构和依赖注入。
- 优先 Hilt。只有出现可复现的 TDLib 生命周期冲突并形成书面架构决定后，才可改用手动构造函数注入。
- minSdk 26；compileSdk 使用本机已安装的最新稳定正式平台；targetSdk 使用与已安装正式平台和稳定 AGP 兼容的最新正式 API。
- 只使用稳定依赖。任何 alpha、beta、RC 或 snapshot 都必须先说明必要性并取得用户同意。

## 3. 强制依赖方向

依赖必须保持以下方向：

Compose UI → ViewModel → UseCase/Repository 接口 → Repository 实现 → 基础设施适配器 → 官方 TDLib、Room 或 Media3。

- UI 不得直接使用 TDLib、Room DAO 或创建 ExoPlayer。
- ViewModel 只能调用 UseCase 或 Repository 接口。
- TDLib 类型、回调和错误不得越过 telegram 模块的数据边界。
- 默认优化路线固定为 ACTIVE/STANDBY 双 ExoPlayer，最多两实例并循环复用。STANDBY 静音且不获取音频焦点；任意时刻只有 ACTIVE 发声。正常滑动不得销毁即将晋升的备用绑定。硬件资源不足时允许安全降级，但不再要求先做单播放器方案。
- 备用解码 READY 不等于具备起播缓冲。晋升须满足当前正式起播门槛或剩余时间线已完整缓冲；不得用仅半秒缓冲或首个静止画面冒充连续可播放。性能报告独立统计实际可播放耗时与首帧耗时。
- Stage 26 追加策略：AUTO 备用优先选择 Telegram 已提供的 480p（缺失则选择合法省流源），晋升沿用同一文件；不得伪造低清版本。
- Stage 27 预算与起播策略（当前生效）：备用预算以“秒”表达而不是字节，目标是备够 5 秒，字节数由目标码率推导，硬上限 20MiB；大码率视频自然多分配，低清视频自然少分配，上限是上限而非承诺。备用播放器以实际缓冲 5 秒停止加载。
- Stage 27 备用准入：移动数据下只要当前缓冲至少 3 秒且缓冲未下降即可开始准备；不得重新引入独立的 8 秒门槛，也不得在池安全层重复实现比预算层更严的阈值。依据是 TDLib NEXT_PRELOAD 优先级 8 低于 CURRENT_STARTUP/SEEK/CONTINUATION 的 32/30/24，备用请求在协议层无法抢占当前播放。
- Stage 27 链路让出（当前生效）：移动数据且备用**已获准**时，当前播放器保留 20 秒前瞻后停止加载，并把每次请求的预读窗口收窄到 1MiB，把链路让给备用；备用未获准、未开启移动预加载或非移动数据时必须立即恢复完整预算。不得把该上限降到 12 秒重缓冲门槛附近：`StandbyContentionSimulationTest` 已否决 12 秒封顶，因为交换时刻的储备正是吸收交接抖动的余量。
- Stage 27 起播分档：低清目标（短边≤480）在快慢吞吐较低值≥2.0×平均码率且首字节 P90≤350ms 时用 800ms；同条件但余量≥1.6×且首字节 P90≤500ms 时用 1200ms；更高清晰度仅在余量≥2.5×且首字节 P90≤500ms 时用 1800ms；未知或弱网保留 2500ms。不得为了让数字好看而在弱网或未知条件下使用低门槛，重缓冲恢复门槛不降。
- Stage 27 参数取舍必须由确定性判据支撑：涉及“当前流 vs 备用”的资源分配改动，先在 `player/src/test/.../StandbyContentionSimulationTest.kt` 的竞争模型里跑通，并同时在 STRICT_PRIORITY / PROPORTIONAL / EQUAL 三种调度假设下不劣化；真机随机媒体轮次之间的 P50 差异可达 4 倍，**不得**用 20 次随机前滑的端到端延迟宣称因果。
- Stage 27 机制度量优先：判断备用是否真的在工作，看周期性采样里的 `standbyHeld` / `standbyYielding` / `standbyAheadMs` / `standbyGate[...]`（状态量），而不是只看端到端延迟（计时量）。`budgetBytes=0` 是 3 秒准入与播放器状态守卫的正常结果，不是缺陷；不得为提升命中率而调低 3 秒门槛。
- Stage 28 下载窗口单主导（当前生效）：同一文件只要拥有任意 `CURRENT_PLAYBACK` owner（无论是否已满足），残余的 `NEXT_PRELOAD` owner 就不得参与 plan 合并或驱动请求（`TelegramFileManager.selectSteeringOwners`）。已下载字节留在 TDLib，残余区间由播放 read-ahead 自然覆盖；播放暂停/释放后预加载 owner 立即恢复驱动。设备证据：修复前同文件 owner 交替拉锯产生 25 次/20 滑 `DISJOINT_SWITCH`，带拉锯文件下载仅 479 KiB/s，而无拉锯文件 1.3-3.4 MiB/s；修复后拉锯归零。不得为其他目的重新引入"同一文件双 owner 区间合并"。
- Stage 28 未出首帧的有界预加载（当前生效）：`AdaptivePreloadPolicy` 在"当前未渲染首帧"时返回 CONSERVATIVE（256 KiB）而非 OFF；硬阻塞（离线/存储/内存/省电/热/网络切换/连续失败/重缓冲）保持 OFF。依据：`NEXT_PRELOAD` 优先级低于一切当前播放优先级，TDLib 资源分配天然让当前流先行；此 256 KiB 恰好覆盖滑动瞬间的 TTFB。**不得**把这理解为"允许预加载抢占当前播放"，也不得在池备用（重武器）上放宽 3 秒准入。
- Stage 28 渐进起播门槛（当前生效）：未渲染首帧时起播门槛从基准值按 500 ms/s 线性衰减、下限 1000 ms、grace 2 s；门槛只降不升，重缓冲恢复（12 s）与暂停恢复路径不得受影响（`relaxedStartupMillis` 只作用于 `!parameters.rebuffering` 的首播分支）。调整衰减率/下限必须用 `PoolStartupReservoirTest` 的纯函数用例锁定新预期。
- 当前播放器的压缩样本采用 32MiB 停止加载阈值，备用样本采用 20MiB 阈值；两者都约束加载决策，不是进程内存硬上限。达到当前样本阈值且已有可播放数据时允许受限缓冲起播以避免死锁，这是资源降级，须继续报告重缓冲；单个在途样本可能越过阈值，不得把它称为进程内存硬上限。
- 视频字节只允许位于应用内部缓存目录；Room 只保存元数据。

## 4. 阶段工作合同

每个实现阶段开始前必须写明：

1. Outcome：一个可观察的用户行为或架构能力。
2. Scope：预计新增或修改的确切文件、模块。
3. Boundary：本阶段明确不做的事项。
4. Failure states：加载、空状态和适用的错误状态。
5. Proof：能够独立验证本阶段的测试或构建命令。

一次只完成一个阶段。若 Proof 失败，停止扩展功能，先定位首个根因。

## 5. 开始修改前的检查

每次必须先执行只读检查：

1. 确认仓库根目录和当前分支。
2. 执行 git status，保护用户的未提交修改。
3. 阅读所有适用的 AGENTS.md、README.md 和相关 docs 文件。
4. 查看当前模块、构建文件和实现路径，不创建平行架构。
5. 给出当前阶段计划和验证方法。

禁止覆盖、删除或格式化与当前阶段无关的用户文件。禁止 git reset --hard、git clean -fd 和自动 push。

## 6. Telegram 与授权安全

- debug 开发构建可从未跟踪的 local.properties 读取 TELEGRAM_API_ID 和 TELEGRAM_API_HASH；任何非 debug 构建必须强制使用空 BuildConfig 值。
- 公开版只能由使用者在设备 UI 中填写自己的 API ID/API Hash；静态校验通过后，使用 Android Keystore 的 AES-GCM 密钥加密并写入 noBackupFilesDir 下的应用私有文件。
- 运行时凭证不得写入 Room、DataStore、SharedPreferences、日志、备份、公共存储或崩溃信息；加密文件不可读时必须失败关闭并要求重新输入。
- 不得要求用户在聊天中粘贴真实 api_hash。
- 不得在源码、文档、测试、提交或日志中出现真实凭证。
- 不得记录验证码、两步验证密码、完整手机号、数据库密钥、会话数据或完整 TDLib 对象。
- 验证码和密码不得持久化；授权成功、取消或失败终止后清空内存引用。
- 授权状态必须来自 TDLib updateAuthorizationState，不得用延时、模拟数据或假登录代替。
- FLOOD_WAIT 必须遵守服务器等待时间；重试必须有上限、退避、可取消且不阻塞主线程。

## 7. 媒体、缓存与内容保护

- 不得把 Telegram 消息链接伪装成媒体 URL，也不得将消息链接交给 ExoPlayer 播放。
- 通过自定义 Media3 DataSource 将 DataSpec 的 position/length 转换为 TDLib downloadFile 的 offset/limit 区间请求。
- 区间请求必须可取消、可超时，并在 ExoPlayer 加载线程而非主线程等待。
- 第一版只播放 supportsStreaming=true 的普通 messageVideo。
- supportsStreaming=false 时不得自动完整下载，必须显示“该视频暂不支持流式播放。”
- 只预加载唯一下一条；用户开启移动数据预加载后，授权必须贯穿策略、预算与播放器，禁止再次被 Wi-Fi-only 判断屏蔽。移动数据以实际缓冲 5 秒为目标，字节由峰值码率推导，新增请求硬上限 20MiB/下一目标，取消不退款；未开启时保持关闭。当前首帧、seek、重缓冲和设备压力优先于备用下载。
- 默认媒体缓存上限为 500MB，可选 200MB、500MB、1GB、2GB、5GB、10GB、15GB、20GB。
- 不得同时建立 TDLib 完整缓存和 Media3 完整文件缓存。
- 当前播放和下一条预加载数据必须通过所有权令牌保护，清理时不可误删。
- 不得写入 MediaStore、Downloads、DCIM、Movies 或其他公共目录。
- 不得请求广泛存储权限。
- 对受保护内容不提供保存、导出或分享；播放期间按策略启用 FLAG_SECURE。

## 8. 数据与同步规则

- 视频唯一键是 chatId + messageId，禁止假设 messageId 全局唯一。
- 频道只包含当前账号可访问、chatTypeSupergroup 且 supergroup.isChannel=true 的聊天。
- 历史扫描分页写入 Room，每页独立事务提交并保存游标。
- 增量同步处理新消息、内容更新和删除更新；不得重复插入。
- 扫描阶段只写元数据，不得下载完整视频。
- 标签优先使用 formattedText 中的 textEntityTypeHashtag；仅在无实体时使用经测试的 Unicode 回退解析器。
- 英文标准化必须使用 Locale.ROOT；频道为 OR，频道与标签之间为 AND，标签支持 OR/AND。
- 退出账号时停止请求和播放，清理账号相关索引与缓存，不保留跨账号数据。

## 9. 权限和隐私

第一版清单只允许：

- android.permission.INTERNET
- android.permission.ACCESS_NETWORK_STATE

新增任何权限必须先说明理由并获得用户确认。禁止联系人、短信、电话、麦克风、摄像头、位置、通知和广泛存储权限。

Android 备份必须禁用，并同时用 dataExtractionRules/fullBackupContent 排除运行时 API 参数文件、TDLib 数据库、会话、本地密钥、媒体缓存、敏感 DataStore 和 Room 数据，覆盖云备份与设备迁移差异。

## 10. 测试与验证

业务逻辑必须依赖接口并可使用 Fake 测试。不得在自动化测试中使用真实 Telegram 账号、验证码、密码或 api_hash。

Windows 上优先执行：

    .\gradlew.bat test
    .\gradlew.bat lint
    .\gradlew.bat assembleDebug

连接授权真机时再执行：

    adb devices
    .\gradlew.bat installDebug
    .\gradlew.bat connectedDebugAndroidTest

阶段 0 没有 Gradle 工程，上述命令不可用时必须报告“尚未验证”，不得伪造结果。

### Compose 门槛（Path B）

当前授权测试手机为红米 Note 11 Pro+，移动数据为加载性能验收网络，不要求 Wi-Fi 性能测试。历史“禁止实体机”和“必须先验证单播放器”阶段边界不适用于 Stage 27。所有 adb 修改命令必须用 -s 显式选择已核实的红米设备，不操作其他实体机。保留账号、凭证与索引，不通过清数据制造成绩。

Stage 27 的 Compose Proof 为编译、Robolectric-Compose、API 36 AOSP x86_64 emulator-Compose-UI 与 Redmi install+launch；以下 Vivo 规则仅保留为历史环境说明，不要求连接 Vivo。真实流量下单独报告全量切换与 READY 命中子组、失败/超时、重缓冲、内存及样本局限，不承诺任意视频秒开或全局理论最优。移动数据验收必须在 Wi-Fi 关闭状态下进行；不得用主机、模拟器或 Wi-Fi 结果代替流量成绩。

在 iQOO 12 / OriginOS 6 / Android 16 上，完整 Compose instrumentation 曾被系统 `fast_freezer`/`single-cleaner` 杀死；这不是 Compose 代码、Room、SQL、Gradle 或 TDLib native 的失败证据。不得在没有改变设备策略的情况下重复同一条 Vivo `am instrument` 命令。

Compose Proof 固定为：

    Proof(Compose) = 编译通过 ∧ Robolectric-Compose 通过 ∧ emulator-Compose-UI 通过 ∧ Redmi 真机 install+launch smoke 通过

执行顺序和脚本如下：

    $env:JAVA_HOME = 'E:\Android Studio\jbr'
    .\gradlew.bat :app:compileInstrumentationKotlin --no-daemon --console=plain
    .\gradlew.bat :app:testInstrumentationUnitTest --tests "com.qixuan.channelvideoflow.feature.auth.LoginScreenTest" --tests "com.qixuan.channelvideoflow.feature.channels.ChannelSelectionScreenTest" --tests "com.qixuan.channelvideoflow.test.ComposeSmokeTest" --no-daemon --console=plain
    .\gradlew.bat :app:testInstrumentationUnitTest --tests "com.qixuan.channelvideoflow.feature.settings.CacheSettingsScreenTest" --no-daemon --console=plain
    .\scripts\run-emulator-compose-tests.ps1 -Serial <x86_64-emulator-serial>
    adb -s <已核实的红米 serial> install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s <已核实的红米 serial> shell am start -W -n com.qixuan.channelvideoflow/.MainActivity

完整主机检查优先添加 `--max-workers=1`；本机并行 lint 分析曾触发 Kotlin FIR 内部异常，不能通过关闭 lint 或删除用例规避。真机启动后还须核实 MainActivity resumed/top 和无目标包 crash。下述 Vivo 项仅为历史环境解释。

- Emulator UI 只使用 API 36 AOSP `x86_64` AVD；不得用 ARM64 AVD，也不得在该 emulator 跑 TDLib native smoke。
- instrumentation build type 必须排除 target APK 的所有 `.so`，但不得改变 debug/release 生产 APK 中的 TDLib native `.so`。
- Vivo 只验证安装、冷启动、`MainActivity` resumed/top 和无目标包 crash；完整 Vivo instrumentation 仅保留作系统环境诊断证据，不是通过条件。
- Boundary：`Vivo/OriginOS 6 + Android 16 对 adb 安装包的后台/自启动管控属于设备环境限制，非代码缺陷；当且仅当步骤 2/3 已执行仍不可达时，真机完整 instrumentation 不计入 Failure，改由步骤 4 的等价组合证明。`

## 11. 日志规则

- Debug 仅允许记录状态名、请求类型、chatId/messageId、播放器状态、区间范围、缓存统计和脱敏错误码。
- Release 关闭详细调试日志。
- 默认不记录消息正文；只有用户主动开启调试开关时才允许短期记录，并仍不得包含凭证、密码或媒体字节。

## 12. 完成汇报

每阶段完成后必须按以下顺序汇报：

1. 一、本阶段目标
2. 二、实际完成内容
3. 三、修改文件
4. 四、关键架构决定
5. 五、执行的命令
6. 六、测试结果
7. 七、真机验证结果
8. 八、已知问题
9. 九、安全检查结果
10. 十、建议的下一阶段

所有未实际验证的内容必须写“尚未验证”。不得自动提交；测试通过后只可建议提交，并在提交前展示变更摘要。
