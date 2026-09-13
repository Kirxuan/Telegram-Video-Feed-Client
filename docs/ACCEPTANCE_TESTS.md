# VELORA（曜流）验收测试

> 当前设备授权（2026-09-10）：Stage 27 使用红米 Note 11 Pro+ / Android 13，实体机禁止条款仅为旧阶段历史。加载性能只要求移动数据且必须关闭 Wi-Fi，不要求 Wi-Fi 成绩；每条 adb 写操作显式选择目标。Stage 27 Proof 覆盖秒级预算（5 秒目标/20MiB 上限）、移动数据下 3 秒准入、480p 备用、分档起播门槛（含 800ms/1200ms/1800ms/2500ms 四档与未知条件回退）、两实例/单音频和后台释放。真机成绩与 host/emulator Proof 分开，见 [Stage 27](STAGE27_MOBILE_FAST_START.md)。
>
> 仪器限制：本机 PowerShell 工具宿主无法执行原生进程（adb 输出与退出码均不可见），因此本轮真机证据由同一 adb 手势与相同 `CVF-Transition`/`CVF-Player` 字段的采集器收集，证据保存在 `build/reports/stage27/`。仓库正式仪器仍是 `scripts/run-swipe-first-frame-benchmark.ps1`，本轮同时修复了它无法被 Windows PowerShell 5.1 解析的编码问题（缺少 UTF-8 BOM）。
>
> Stage 27 本轮 Proof 结果：编译 `test lint assembleDebug` PASS；跨模块与变体共 1616 次测试执行，失败/错误/跳过均为 0；API 36 AOSP x86_64 emulator Compose UI `OK (101 tests)`、目标包 crash 0；红米覆盖安装、`MainActivity` topResumed、冷启动 1400ms、crash 记录 0。真机移动数据为三轮各 20 次前滑，快速路径（≤500ms 完成切换）三轮均为 4–6 次，但 P50 在 1784–7246ms 之间波动，**不作可归因的提升声明**。详见 [Stage 27](STAGE27_MOBILE_FAST_START.md)。

## Stage 25 收尾验证（2026-09-09，未发布）

| 项目 | 本次结果与边界 |
|---|---|
| 全屏入口 | 现有横屏定位修复重新安装后通过；共享用例断言完整边界在 Pager 内、48dp 实际像素高度、触摸进入/退出和元数据恢复。不能用语义存在替代屏幕可见和实际触摸。 |
| 尺寸矩阵 | API 36 AOSP x86_64；小屏大字、横屏、平板各 40/40，通过后恢复 size/density/font。 |
| 常规 Compose | 最新 APK 101/101 通过，含上述入口用例。 |
| 主机 Proof | 完整 test、lint、debug/release 及 instrumentation target/test 编译通过；app 三个普通变体各 146 项、instrumentation unit 250 项，player 三变体各 128 项，telegram 三变体各 165 项，model 6 项、domain 72 项；没有失败、错误或跳过。不同变体不当作独立用例累计。 |
| 历史 Robolectric 阻塞 | Android 16 MavenArtifactFetcher 曾有 27 条依赖初始化失败，属于外部环境；本次现有配置正常重跑已恢复。没有复制受限缓存、换 SDK、删测试或放宽检查。 |
| 静态候选 | Release 两项权限/备份/native/凭证零命中、R8 体积、TDLib 新库加密与 16KiB ELF/APK 对齐分别记录；以 [Stage 25 结果](STAGE25_OPTIMIZATION_RESULTS.md) 和 [产物清单](STAGE25_ARTIFACTS.md) 为准。 |
| 实体机 | 实体 iQOO 12 未连接、未探测、未安装、未操作；性能、Codec、PSS、图形缓冲、能耗和真实账号路径尚未验证。完整 Path B 的真机 install+launch 项仍尚未验证。 |

当前工作树默认启用 C1 双播放器；SampleQueue/C2 仍为独立待验收候选。主机/模拟器通过不能代替移动数据连续性验收；备用 READY 还须满足正式起播缓冲，不能仅统计静止首帧。以下 Stage 24 和更早章节属于既有版本历史，不能覆盖本轮设备限制。

> Stage 25 当前轮次不允许任何实体机操作。主机、API 36 AOSP x86_64 Compose/Room、实际 Media3 池与 EGL 测试及后续设备清单见 [Stage 25 实施结果](STAGE25_OPTIMIZATION_RESULTS.md) 和 [设备验收清单](STAGE25_DEVICE_ACCEPTANCE.md)。下文历史通过数量不代表当前工作树。16KiB 静态 ELF/APK 对齐使用 `scripts/verify-native-pages.py` 独立检验；运行时兼容尚未验证。

文档版本：3.5
日期：2026-09-02
状态：Stage 24 已实施用户自行配置、Keystore 加密文件和凭证变更后的客户端重建；1106 项主机测试、lint、debug/release 构建、正式签名和凭证反向扫描通过。仓库所有者报告当前版本真机安装与正常使用通过；本次正式签名 APK 的设备安装、真实 Keystore instrumentation 与逐项账号证据未由 Codex 重复验证。

## 1. 证据分级

- Unit：本机 JVM 单元测试，不需要 Android 设备或真实 TDLib。
- Robolectric/Host Android：若稳定依赖和 API 允许，用于 Android 类的主机测试。
- Instrumented/Compose：连接模拟器或真机的 Android 测试。
- Physical Device：连接并授权的真实手机。
- Manual Telegram：真实个人账号、真实频道和真实媒体的人工验收。

自动化测试不得使用真实手机号、验证码、密码、API Hash 或消息正文。Fake 数据必须显式标记为测试夹具，不能成为生产演示路径。

### 1.1 Compose Path B 门槛

完整 Compose instrumentation 已在 iQOO 12 / OriginOS 6 / Android 16 上确认被 `fast_freezer`/`single-cleaner` 杀死：测试 Activity 当时为 TOP、oom adj 0、屏幕亮、充电且 device idle 未触发。该证据不指向 Compose、Room、SQL、Gradle 或业务断言失败，禁止在未改变设备策略时重复同一条 Vivo `am instrument` 作为修复尝试。

    Proof(Compose) = 编译通过 ∧ Robolectric-Compose 通过 ∧ emulator-Compose-UI 通过 ∧ 当前目标真机 install+launch smoke 通过

| Proof 项 | 固定范围 | 不计入该项 |
|---|---|---|
| 编译 | `:app:compileInstrumentationKotlin` | 设备行为 |
| Robolectric-Compose | `testInstrumentationUnitTest` 中共享 Login、ChannelSelection、Smoke suite | 真机 TDLib、真实账号 |
| emulator-Compose-UI | API 36 AOSP `x86_64` AVD，只安装无 `.so` 的 instrumentation target APK | TDLib native smoke、ARM64 AVD |
| 当前目标真机 launch smoke | debug APK 安装、冷启动、`MainActivity` resumed/top、无目标包 Java/native crash；12F 由仓库所有者指定当前 Android 13 ARM64 手机 | 完整 Compose instrumentation |

Boundary：`Vivo/OriginOS 6 + Android 16 对 adb 安装包的后台/自启动管控属于设备环境限制，非代码缺陷；当且仅当步骤 2/3 已执行仍不可达时，真机完整 instrumentation 不计入 Failure，改由步骤 4 的等价组合证明。`

12F 范围更新：仓库所有者于 2026-07-30 明确不再要求原 iQOO/Vivo 设备，只以当前 Android 13 ARM64 手机作为目标真机。上述 Vivo/OriginOS 诊断历史继续保留，但不是 12F 待补项。

## 2. Stage 24：用户自行配置与免凭证发行

| ID | 验收项 | 当前规则/结果 |
|---|---|---|
| STAGE24-01 | 构建隔离 | 通过：debug 可选读取 ignored local.properties；release/instrumentation/未来非 debug BuildConfig 两字段为空 |
| STAGE24-02 | 输入校验 | API ID 为正整数，API Hash 为 32 位十六进制；错误只显示键和固定文案 |
| STAGE24-03 | 静态存储 | Android Keystore AES-256-GCM、随机 IV、固定 AAD、noBackupFilesDir、原子替换、4KiB 上限 |
| STAGE24-04 | 敏感 UI | API Hash 密码遮罩；提交前取快照并立即从 ViewModel/Compose 状态清除；不用 SavedStateHandle |
| STAGE24-05 | 损坏恢复 | 密文/密钥不可读时失败关闭；用户显式重新输入后重建密钥与密文，不读损坏值 |
| STAGE24-06 | TDLib 拒绝 | SetTdlibParameters 失败回到配置页；新值保存后旧 Client Close → Closed → 新 generation 唯一 Client |
| STAGE24-07 | APK 扫描 | 通过：本机 API ID/API Hash 均零命中；仅 INTERNET/ACCESS_NETWORK_STATE，备份禁用，只有 arm64-v8a |
| STAGE24-08 | 自动化 | 通过：定向测试通过；完整 test 1106/1106、lint、debug/release 构建退出码 0 |
| STAGE24-09 | Android Keystore 真机 | `SecureTelegramCredentialsAndroidTest` 在授权 ARM64 设备执行；本次 Codex 未连接设备，尚未验证 |
| STAGE24-10 | 人工登录 | 仓库所有者报告当前版本真机安装与正常使用通过；首次配置、错误参数修正、登录、重启恢复和退出的逐项证据尚未记录 |
| STAGE24-11 | 正式签名 | 通过：RSA-4096 发布证书、APK Signature Scheme v2/v3、`zipalign` 与 `apksigner verify`；签名材料位于仓库外且不进入 Git |
| STAGE24-12 | 无广告发行依据 | 仓库所有者确认已取得 Telegram 书面例外许可；许可文件和账户信息不进入仓库 |

## 历史交付阶段 2：授权、供应链与主机验收

阶段 2 合并原阶段 2–4，只验收官方 TDLib 授权范围；频道、标签、媒体、缓存和信息流案例仍属未来阶段。完整供应链固定值、哈希、构建入口与许可证见 `docs/TDLIB_BUILD.md`；单次执行命令、退出码与静态扫描结果见 `.superpowers/sdd/task-10-report.md`。

| ID | 验收项 | 当前规则 |
|---|---|---|
| STAGE2-01 | 固定 TDLib | 只接受 `https://github.com/tdlib/td.git`、`1.8.66`、commit `022d60202e446ad1287b9fb68e687c8a0760788b` |
| STAGE2-02 | 固定 OpenSSL | 只接受 `3.5.7 LTS` archive 和 SHA-256 `a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8` |
| STAGE2-03 | native 形态 | 仅 `arm64-v8a`、Android API `26`、`c++_static`；ELF 必须为 AArch64 `DYN` 且没有 `libc++_shared.so` |
| STAGE2-04 | APK ABI | debug APK 的 `lib/` 下只允许 `arm64-v8a`；当前允许固定构建的 `libtdjni.so`、Compose `ui-graphics 1.11.4` 传递的官方 `androidx.graphics:graphics-path:1.0.1` 所提供的 `libandroidx.graphics.path.so`，以及官方稳定 `androidx.datastore:datastore-core-android:1.2.1` 的 `libdatastore_shared_counter.so`；不允许其他 ABI、未知 native 文件或 `libc++_shared.so` |
| STAGE2-05 | manifest | 有效 `uses-permission` 恰好为 INTERNET、ACCESS_NETWORK_STATE；`allowBackup=false`，同时引用两套规则 |
| STAGE2-06 | 凭证与输入 | 不读出 `local.properties` 值；生产授权路径不以 `SavedStateHandle`、DataStore、Room、SharedPreferences 或 `rememberSaveable` 持久化输入 |
| STAGE2-07 | 主机 Proof | 以 Android Studio JBR 运行 fresh `test`、`lint`、`assembleDebug`；通过不代表真机或真实账号行为 |
| STAGE2-08 | 当时的人工边界 | 阶段 2 完成时安装、Compose device test、真实登录/重启/退出均尚未验证；后续结果按阶段 3 表单独记录 |

## 3. 历史交付阶段 3：频道发现与多选

交付阶段 3 对应开发计划中的原“阶段 5”。真实频道标题和账号信息不得写入测试报告；自动化测试只使用 Fake Repository。

| ID | 验收项 | 结果 |
|---|---|---|
| STAGE3-01 | TDLib 类型筛选 | 通过：同时要求 `chatTypeSupergroup.isChannel` 与 `supergroup.isChannel`，并严格检查可访问成员状态 |
| STAGE3-02 | 排除非频道 | 通过：私聊、秘密聊天、普通群、非频道超级群、Left/Banned/Restricted/非成员 Creator 均有映射/Repository 测试 |
| STAGE3-03 | 异步分页 | 通过：主列表和归档列表各自 `loadChats` 至 TDLib 404 页尾；失败不执行破坏性全量对账 |
| STAGE3-04 | 实时元数据 | 通过：处理新聊天、标题和 supergroup 状态更新；失去访问立即隐藏并取消选择 |
| STAGE3-05 | Room | 通过：版本 1 schema、事务对账、元数据更新保留选择、两选关闭/重开数据库仍保留 |
| STAGE3-06 | 搜索与多选 | 通过：ViewModel Fake 测试覆盖搜索、精确 ID 保存、持久选择恢复与访问丢失交集 |
| STAGE3-07 | UI 状态 | 通过（host 编译/逻辑）：加载、内容、空、搜索空、错误、缓存刷新错误、保存中/成功/失败和 FLOOD_WAIT 倒计时均有生产状态 |
| STAGE3-08 | 媒体边界 | 通过：生产模块无 `GetChatHistory`、`DownloadFile`、Media3/ExoPlayer 调用 |
| STAGE3-09 | 主机 Proof | 通过：fresh `test`、`lint`、`assembleDebug` 均退出码 0 |
| STAGE3-10 | 真机安装 | 通过：V2307A、Android 16/API 36、arm64-v8a 上 `installDebug` 成功 |
| STAGE3-11 | 真实账号列表/搜索/持久化 | 通过：真实频道页为非空可用状态；搜索无匹配状态正确；保存 2 个频道后强停/重启仍恢复 2 个 |
| STAGE3-12 | Room instrumentation | 通过：3/3 DAO 用例在 V2307A 通过 |
| STAGE3-13 | Compose instrumentation | 未通过：Vivo 上 AndroidX `ActivityScenario` 无法启动测试宿主 Activity；单独最小 ActivityScenario 和显式 `@RunWith(AndroidJUnit4::class)` 均同样挂起，非业务断言失败 |
| STAGE3-14 | 退出真实频道后移除 | 通过：所有者退出测试频道后首次刷新即从 92 个可用变为 91 个可用 + 1 个不可用；不可用项选择为 0，UI 无错误 |

## 4. 历史交付阶段 4：频道视频索引、历史分页和标签系统

真实频道标题、消息正文、标签文本和账号标识均不写入本报告；以下数量来自应用停止扫描后的 Room 聚合查询，不是估算。

| ID | 验收项 | 结果 |
|---|---|---|
| STAGE4-01 | 消息类型边界 | 通过：mapper/Repository 只接受普通 `messageVideo`；动画、视频留言、Stories、直播、文档及其他消息不会建立视频记录 |
| STAGE4-02 | 联合唯一键与去重 | 通过：Room 复合主键为 `chatId + messageId`；真机全库 553 条记录、553 个唯一键，重复记录 0 |
| STAGE4-03 | 历史分页与即时落库 | 通过：所选频道稳定点已扫描 1,189 条消息/13 页并索引 540 个视频；每页事务同时提交记录、计数和最旧游标 |
| STAGE4-04 | 恢复与最新同步位置 | 通过（自动化）：Fake/Room 测试覆盖边界重复、已删除边界、最旧游标和 `lastNewMessageId` 恢复；真实进程重启跨扫描恢复尚未单独计时取证 |
| STAGE4-05 | 增量新建/编辑/删除 | 通过（自动化）：新消息幂等 upsert，编辑原子替换 caption/标签，删除软删除并清关联；真实测试频道三种操作尚未逐项人工取证 |
| STAGE4-06 | 标签实体与回退 | 通过：UTF-16 entity 优先，缺少 hashtag entity 才走 Unicode 回退；中文/英文、越界、surrogate、孤立井号、嵌入单词和去重均有测试 |
| STAGE4-07 | 标签筛选 | 通过：Room/纯领域测试覆盖标签 OR/AND、频道 OR、频道与标签 AND、空频道不隐式全选；英文键使用 NFKC + `Locale.ROOT` |
| STAGE4-08 | 真实标签分布 | 通过：所选频道 540 个视频中，无标签 117、单标签 90、多标签 333、含中文标签 378、含英文标签 350；类别可重叠 |
| STAGE4-09 | 前台/暂停边界 | 通过：UI 可暂停/继续；Home 后 Room 状态为非用户 `PAUSED`，没有后台无限扫描 |
| STAGE4-10 | FLOOD_WAIT/重试 | 通过（自动化）：持久截止时间、全局门控、取消和最多 3 次网络重试有测试；真实扫描曾连续出现 3 次脱敏 `TIMEOUT`，用户继续后恢复扫描，稳定统计点异常计数为 0 |
| STAGE4-11 | 无媒体下载 | 通过：历史请求只设置元数据参数，生产 Kotlin 索引路径没有 `DownloadFile`、Media3 或 ExoPlayer |
| STAGE4-12 | 主机 Proof | 通过：fresh `test`、`lint`、`assembleDebug` 均退出码 0 |
| STAGE4-13 | 真机模块测试 | 通过：V2307A Android 16 上 Room 9/9、TDLib native 1/1 instrumentation 通过 |
| STAGE4-14 | Vivo 完整 Compose instrumentation（历史诊断） | 被 OriginOS 阻塞：全应用 16 项在首个既有 `LoginScreenTest` 后测试进程崩溃；隔离阶段 4 的 3 项 `ChannelSelectionScreenTest` 也在首项后相同崩溃。日志显示 `am_app_frozen`/`fast_freezer` 与 `am_kill`/`single-cleaner`；不作为 Compose 代码失败 |
| STAGE4-15 | Compose Path B | 通过：host 编译、共享 Robolectric Compose suite、instrumentation target APK 无 `lib/` 条目、`CVF_AOSP_API36_X86_64`（API 36/x86_64）18/18 Compose UI instrumentation、V2307A Android 16 ARM64 Vivo install + launch smoke 均通过 |

## 5. 阶段 0 文档验收（历史基线）

| ID | 检查 | 期望 |
|---|---|---|
| DOC-01 | 仓库根目录文件清单 | 只有已批准的七份文档和 .git 元数据，无业务代码 |
| DOC-02 | Git 状态 | 七份文档为未跟踪/新增；无未知覆盖、无提交、无 push |
| DOC-03 | 文档链接 | README 指向六份 docs，文件均存在 |
| DOC-04 | 关键数字搜索 | minSdk=26、默认缓存=500MB、只预加载下一条一致 |
| DOC-05 | 流式边界搜索 | 所有文档都要求 supportsStreaming=false 不自动完整下载 |
| DOC-06 | 权限搜索 | 只允许 INTERNET 和 ACCESS_NETWORK_STATE |
| DOC-07 | 敏感数据搜索 | 无真实 TELEGRAM_API_ID/API_HASH、手机号、验证码或密码 |
| DOC-08 | 范围检查 | 无 Gradle、Kotlin、XML、TDLib 源码/二进制和假数据 |

## 6. JVM 单元测试

### 6.1 标签解析

| ID | 输入/条件 | 期望 |
|---|---|---|
| TAG-01 | formattedText 中 #学习 对应 textEntityTypeHashtag | displayName=#学习，normalizedName=学习 |
| TAG-02 | #单片机、#学习、#娱乐 三个中文实体 | 三个标签全部提取 |
| TAG-03 | #Kotlin 与 #kOtLiN | normalizedName 都为 kotlin，使用 Locale.ROOT |
| TAG-04 | 同一视频包含两次 #学习 | 只保留一个 normalizedName 关联，原始显示有效 |
| TAG-05 | #ESP32_学习123 | 支持英文、数字、下划线和中文 |
| TAG-06 | emoji 位于 hashtag 之前，实体使用 UTF-16 offset | 切片准确且不拆 surrogate pair |
| TAG-07 | 实体 offset 越界 | 跳过实体并返回脱敏解析警告，不崩溃 |
| TAG-08 | 没有 hashtag 实体，文本为“内容 #学习” | 回退解析出 #学习 |
| TAG-09 | 没有实体，文本为“abc#学习” | 回退不误识别嵌入普通词的井号 |
| TAG-10 | 孤立 #、# 后空格、普通井号说明 | 不生成标签 |
| TAG-11 | formattedText 存在非 hashtag 实体和 # 文本 | 仅当 hashtag 实体完全不存在时运行回退，并符合规则 |
| TAG-12 | #Ｋｏｔｌｉｎ 全角字符 | NFKC 后 normalizedName=kotlin |

### 6.2 筛选

测试数据：频道 A 含视频 A1(#学习)、A2(#娱乐)；频道 B 含 B1(#学习,#单片机)、B2(#其他)；频道 C 含 C1(#学习)。

| ID | 选择 | 期望 |
|---|---|---|
| FILTER-01 | 频道 A/B，标签 学习/单片机，OR | A1、B1 |
| FILTER-02 | 频道 A/B，标签 学习/单片机，AND | 仅 B1 |
| FILTER-03 | 频道 A/B，无标签 | A1、A2、B1、B2 |
| FILTER-04 | 仅频道 B，标签 学习，OR | 仅 B1 |
| FILTER-05 | 无频道，任意标签 | 空列表，不隐式选择全部 |
| FILTER-06 | A1 标记 isDeleted | 从所有结果排除 |
| FILTER-07 | 频道 B 标记不可访问 | B1、B2 从结果排除 |
| FILTER-08 | #Kotlin/#kotlin 变体 | 标准化后同一筛选键 |

### 6.3 联合主键与 Room

| ID | 操作 | 期望 |
|---|---|---|
| DB-01 | 插入 (chatId=1,messageId=10) 和 (2,10) | 两行共存，证明 messageId 非全局唯一 |
| DB-02 | 同一复合键重复 upsert | 只有一行，内容更新 |
| DB-03 | 替换视频标签事务中途失败 | caption 与标签均回滚 |
| DB-04 | 删除视频 | isDeleted=true，交叉引用清除，播放历史策略按设计保留/更新 |
| DB-05 | 迁移测试 | 每次 schema 版本升级保留合法元数据并满足新约束 |

### 6.4 Stage 23 视频过滤分页和增量同步

| ID | 场景 | 期望 |
|---|---|---|
| SYNC-01 | `SearchChatMessages(FilterVideo)` 首页 next=91 | 独立过滤游标保存 91，页面数据/统计/游标同一事务完成 |
| SYNC-02 | 下一页包含重复边界 91，next=82 | 复合键幂等，游标推进 82，候选数和唯一数语义分离 |
| SYNC-03 | 页事务失败 | 游标保持原值，重试同页不重复 |
| SYNC-04 | 返回少于 100 条但 next 非零 | 提交短页并继续，不误判完成 |
| SYNC-05 | updateNewMessage 同一消息重复到达 | 复合键 upsert 一行 |
| SYNC-06 | 进程离线期间新增 105..101 | 恢复对账写入五条并在旧边界停止 |
| SYNC-07 | 边界消息被删除 | 扫描到小于边界的消息后安全停止 |
| SYNC-08 | updateMessageContent 修改 caption | 视频和标签在一个事务更新 |
| SYNC-09 | updateDeleteMessages | 视频失效并从队列移除 |
| SYNC-10 | 频道返回无访问权限 | 停止频道任务、标记不可用、保留可诊断状态 |
| SYNC-11 | 任一频道 FLOOD_WAIT 30 | 共享扫描闸门使所有尚未发出的频道请求等待服务器期限；普通失败仍按频道隔离 |
| SYNC-12 | 空页但 next 推进 | 原子持久 next，继续请求 |
| SYNC-13 | 非空最终页 next=0 | 最终页先提交，再标记完成 |
| SYNC-14 | 非零 next 相同或反向 | 原子记录 `PAGINATION_STALLED`，保留上一个有效 cursor，停止无限循环 |
| SYNC-15 | 页面结果前取消 / 页面事务后重启 | 前者无提交；后者从已提交 next cursor 恢复 |
| SYNC-16 | 100 万消息五档视频密度 | 新旧普通 messageVideo 键集合完全一致；1% 理论请求页 10,000→100；100% 不劣于基线 |
| SYNC-17 | 多频道严重不均衡 | 近期首轮覆盖全部频道；每轮每频道一页；worker 上限 2 |
| SYNC-18 | 大量/无 hashtag | 批量行数可增长，但每页 DAO 往返保持有界 |
| SYNC-19 | v4 完整/未完整频道迁移 | 完整频道保留完成事实；未完整频道保留索引并从新过滤 cursor 0 重扫；不清库 |
| SYNC-20 | UI 扫描统计 | 折叠态完整显示处理视频数/已索引数；展开四格显示已处理视频/搜索页数/唯一索引/完整频道；近似总数单独显示“估计约…仅供参考”，不出现“已扫描普通消息数” |
| SYNC-21 | 未完成频道 recent 游标与历史游标逐页对齐 | 每个对齐页同时推进持久历史游标，recent 越过边界后直接从未处理页回填，不重复请求已提交 recent 页；最终键集合不变 |
| SYNC-22 | 频道统计窄屏与大字体 | 单频道进度保持“状态/已处理”与“搜索页数/已索引”两行；API 36 x86_64 的 360dp、320dp + 1.35 字号截图无关键数字丢失 |

### 6.5 随机播放

| ID | 场景 | 期望 |
|---|---|---|
| RAND-01 | 五条视频完成一轮 | 每条恰好出现一次 |
| RAND-02 | 开始第二轮 | 首条不等于第一轮末条 |
| RAND-03 | 队列只有一条 | 允许轮次边界重复 |
| RAND-04 | 轮内删除尚未播放项 | 该项不再出现，其他项不重复 |
| RAND-05 | 筛选改变 | 旧轮次丢弃，新集合重新洗牌 |
| RAND-06 | 固定 FakeRandomSource | 结果确定、测试可复现 |
| RAND-07 | 随机稳定页含旧 TDLib `fileId` | 首次绑定前按联合键刷新消息引用；连续翻页各自绑定新 `fileId`，离页取消不会晚绑定；顺序模式不增加刷新 |
| RAND-08 | 新建播放页会话，Repository 首次数据尚未到达 | 首个 Loading state 已为 RANDOM；首次 Content 不发生 LATEST→RANDOM 跳变 |
| RAND-09 | UI 接收默认播放 state | “随机”首次即为 selected，“最新”从未短暂 selected |
| RAND-10 | 当前会话切换 LATEST 后 Room Flow 再发射 | 当前会话继续 LATEST，不被数据刷新重置 |
| RAND-11 | 上一个会话使用 LATEST 后新建 ViewModel | 新会话重新默认 RANDOM，不读取 DataStore 上次选择 |
| RAND-12 | RANDOM 空列表或筛选变为空 | Loading/Empty 保持 RANDOM，不崩溃、不创建虚拟 bind/preload 请求 |
| RAND-13 | 快速切换顺序或筛选 | 旧 plan、binding 和 preload 继续按既有 generation 失效，不改变随机轮次算法 |
| RAND-14 | RANDOM benchmark 样本 | 每个成功样本含 order、方向、轮次边界、promoted/plan age、刷新结果/耗时、首区间、READY 与首帧；实际方向必须与 `-Direction` 一致，无法证明 RANDOM、方向不符或字段不足时非零失败 |
| RAND-15 | 当前轮最后一项 | `nextEntry`、PlaybackPlan 和唯一预加载目标均为预生成 `upcoming.first`，不是旧轮第一项 |
| RAND-16 | upcoming 第一项 settle | 晋升原 upcoming 的 items/generation，不二次 shuffle；已准备 token 继续合法且不重复刷新 |
| RAND-17 | 空/单项队列 | 空集合不生成 upcoming；单项允许重复但不建立无意义 next preload |
| RAND-18 | 删除当前最后项或 upcoming 项 | 重新核对边界与来源集合，不越界；旧 plan generation 失效 |
| RAND-19 | 新增视频或 Room fileId 更新 | 新项进入随机尾部/upcoming 来源；既有 current/upcoming key 顺序不变，对象引用更新 |
| RAND-20 | 筛选、顺序或访问集合变化 | current/upcoming 与旧 PlaybackPlan 同时失效，不保留轮次历史 |
| RAND-21 | 随机 Pager 跨轮 | 当前轮末页之后映射到真实 upcoming；媒体 key 仍为 chatId+messageId，虚拟 Compose key 含 pagerPage |
| RAND-22 | 当前准备与唯一 next 同时解析同一 `VideoKey` | Repository single-flight 只调用一次官方 `getMessage`，两个 waiter 共享同一结果 |
| RAND-23 | target prepare 未完成时该页 settle | settled 绑定复用目标 plan/同一 in-flight refresh，不补发第二次 `getMessage` |
| RAND-24 | RANDOM + ORIGINAL 的 `getMessage` 挂起 | 3 秒软期限到达后绑定 Room 索引引用，不继续把可见等待暴露到 Repository 15 秒硬上限 |
| RAND-25 | refresh 抛 `CancellationException` | 继续传播并取消最后 waiter 的共享请求；不转换为 fallback |
| RAND-26 | refresh 抛普通 `Exception` / `Error` | 普通异常安全回退；`Error` 不被吞掉或伪装成成功 |
| RAND-27 | 首次绑定的旧 `fileId` 返回 FILE_UNAVAILABLE | 同一 Loading 内 single-flight refresh 一次，按当前质量重选并重绑；不先要求用户点重试 |
| RAND-28 | 恢复后 `fileId` 相同或重绑后再次 FILE_UNAVAILABLE | 进入既有明确错误页；同一恢复键不再自动刷新，显式用户重试仍可用 |
| RAND-29 | 透明恢复期间快速离页或 generation 改变 | job 取消或结果失效；迟到结果不得绑定到新目标 |
| RAND-30 | refresh 发现消息删除/不再是普通 `messageVideo` | Room 安全标记删除/unsupported，当前项显示“视频已不可播放”，不循环 |
| RAND-31 | refresh 返回 FLOOD_WAIT | 不主动重试；一次请求后回退索引引用或结束透明恢复，遵守服务器等待 |
| RAND-32 | 质量、网络、账号、队列或随机轮次变化 | 既有 current/next plan 与恢复 token 失效；不跨 session 复用引用 |
| RAND-33 | LATEST + ORIGINAL | 继续使用索引原画，不增加 `getMessage` |

### 6.6 缓存 LRU 与保护

| ID | 场景 | 期望 |
|---|---|---|
| CACHE-01 | 三项不同 lastAccessedAt | 按最旧到最新选择清理 |
| CACHE-02 | 最旧项为 CURRENT_PLAYBACK | 跳过当前，选择下一未保护项 |
| CACHE-03 | 最旧项为 NEXT_PRELOAD | 跳过下一条，选择下一未保护项 |
| CACHE-04 | 当前与下一条释放 | 随后可被 LRU 选择 |
| CACHE-05 | 达到 500MB 默认上限 | 清理到不超过上限或明确报告无法回收 |
| CACHE-06 | 可选上限 200MB、500MB、1GB、2GB、5GB、10GB、15GB、20GB | 字节换算和保存准确 |
| CACHE-07 | 存储不足 | 先取消预加载，再清理未保护项 |
| CACHE-08 | 清空缓存 | 停止预加载/当前 DataSource，媒体删除，Room/登录/DataStore 保留 |
| CACHE-09 | 目标路径越出私有根目录 | 拒绝删除并记录脱敏安全错误 |

### 6.7 授权状态

| ID | TDLib 更新序列 | 期望应用状态 |
|---|---|---|
| AUTH-01 | WaitTdlibParameters | Initializing 并发送一次参数 |
| AUTH-02 | WaitPhoneNumber | WaitingPhoneNumber |
| AUTH-03 | WaitCode | WaitingCode |
| AUTH-04 | WaitPassword | WaitingPassword |
| AUTH-05 | Ready | Authorized，并清理 code/password |
| AUTH-06 | LoggingOut→Closing→Closed | LoggingOut→Closed，资源释放 |
| AUTH-07 | 错误验证码后仍 WaitCode | 中文错误 + WaitingCode，不模拟成功 |
| AUTH-08 | 错误密码后仍 WaitPassword | 中文错误 + WaitingPassword |
| AUTH-09 | FLOOD_WAIT | 显示等待截止时间，按钮受控 |
| AUTH-10 | 未知授权状态 | 安全不支持提示，不误报 Authorized |
| AUTH-11 | 日志捕获 | 不含手机号、验证码、密码、api_hash、数据库密钥 |

### 6.8 网络、电量与温度策略

| ID | 输入 | 期望 |
|---|---|---|
| POLICY-01 | Wi-Fi、正常、非省电 | 当前允许；下一条按 Wi-Fi 设置 |
| POLICY-02 | 移动数据、默认设置 | 当前允许；下一条禁止 |
| POLICY-03 | 移动数据、用户开启 | 当前允许；下一条仍受电量/温度约束 |
| POLICY-04 | 无网络 | 未缓存媒体不播放；预加载禁止 |
| POLICY-05 | 低电量 | 预加载禁止 |
| POLICY-06 | 省电模式 | 预加载禁止 |
| POLICY-07 | THERMAL_STATUS_MODERATE | 停止下一条预加载 |
| POLICY-08 | THERMAL_STATUS_SEVERE+ | 只保留当前播放 |
| POLICY-09 | API 26–28 | 热状态 UNKNOWN，不伪造 NONE，不崩溃 |
| POLICY-10 | 网络回调释放 | unregister 恰好一次，不泄漏 callback |

### 6.9 快速滑动和播放器所有权

| ID | 场景 | 期望 |
|---|---|---|
| PLAYER-01 | 依次请求 A、B、C，仅 C 稳定 | 只绑定 C；A/B generation 失效 |
| PLAYER-02 | A 正在发声后绑定 B | A 先停止并释放，再播放 B；无重叠音频 |
| PLAYER-03 | Compose 重组 100 次 | ExoPlayer 实例数仍为 1 |
| PLAYER-04 | 进入后台 | 立即暂停并停止预加载 |
| PLAYER-05 | 耳机断开 | handleAudioBecomingNoisy 导致暂停 |
| PLAYER-06 | 音频焦点丢失 | 按策略暂停/降低音量，行为确定 |
| PLAYER-07 | supportsStreaming=false | 不绑定播放器，显示精确提示 |
| PLAYER-08 | 高码率流发生 rebuffer | 继续缓冲并至少积累 12 秒再续播；正常缓冲按时间维持 50–60 秒，不产生第二个播放器 |
| PLAYER-09 | Debug 播放会话观测 | 初始 BUFFERING、暂停和 seek 不计 rebuffer；首次 READY 后的自动播放 BUFFERING 计数并累计恢复时长；每 5 秒采样缓冲前瞻，释放时输出汇总 |
| PLAYER-10 | 候选夹具：页面开始 unstable，目标与唯一 next 的 key/fileId 匹配 | 不提前 stop；已有 next owner 与进行中请求保留 |
| PLAYER-11 | 候选夹具：匹配目标 settle 并开始 CURRENT_STARTUP acquire | 当前 owner 建立前 next owner 不释放；建立并登记成功后才释放 |
| PLAYER-12 | 反向、目标改变或慢拖回弹 | 旧 generation 立即失效；没有其他 owner 时取消，不能错误 bind 或永久提优 |
| PLAYER-13 | 快速 A→B→C | A/B 的状态与迟到回调失效；只有 C 可保留或绑定 |
| PLAYER-14 | 相同 VideoKey 但质量 fileId 改变 | 拒绝错误晋升并取消旧 fileId owner |
| PLAYER-15 | CURRENT_STARTUP acquire 失败 | 释放匹配 next owner，进入既有可恢复错误路径 |
| PLAYER-16 | 页面退出、硬策略变化、seek、账号 release 或 logout | 当前/next owner 全部释放，等待者被唤醒，旧回调不能恢复 owner |
| PLAYER-17 | supportsStreaming=false 的 next | 不创建预加载或晋升 owner |
| PLAYER-18 | 连续切换 speculative target | 任意时刻只有一个 speculative lease/fileId；256 KiB 上限不变 |
| PLAYER-19 | 生产构造与运行时 | `PRODUCTION_OWNER_PROMOTION_ENABLED` 和 `PRODUCTION_REUSE_CONTAINED_ACTIVE_REQUEST` 都为 false，维持 13A 行为 |
| PLAYER-20 | 候选测试与 Compose 重组 | 候选不创建第二个 ExoPlayer/PlayerView；既有单播放器数量门槛保持 |
| PLAYER-21 | Pager 未越过 snap 中点 | 不提交预测 target；慢拖回弹恢复当前页，不错误 bind |
| PLAYER-22 | 越过中点后反向或快速换向 | 唯一 target 从 forward next 以 `forward→null→committed` 替换；不同时预加载两个方向 |
| PLAYER-23 | 快速跨多页与 stale round callback | 只有最终 settled key 可 bind；旧 round/filter generation 回调不能绑定 |

### 6.10 服务端视频清晰度

| ID | 场景 | 期望 |
|---|---|---|
| QUALITY-01 | TDLib 返回直接 `alternativeVideos` | 映射 fileId、remoteUniqueId、size、width、height、codec；TDLib 类型不越界 |
| QUALITY-02 | 省流模式有更小 H.264 候选 | 选择最小已知文件；候选更大时保留原画 |
| QUALITY-03 | 720p 且原画高于 720p | 选择像素预算不超过 1280×720 的最高安全版本，同分辨率取较小文件 |
| QUALITY-04 | AUTO 网络变化 | Wi-Fi 使用 720p 策略；移动数据、OTHER、OFFLINE 使用省流策略 |
| QUALITY-05 | 原画、无候选、非 H.264 或无效信息 | 不选择候选，安全回退索引原画 |
| QUALITY-06 | 当前项与下一条 | 两者刷新后使用同一偏好/网络策略和各自最终 fileId |
| QUALITY-07 | 非原画刷新挂起 | 3 秒后取消等待并回退原画，不阻塞稳定绑定 |
| QUALITY-08 | 最新顺序选择原画 | 不为取得候选额外刷新消息 |

### 6.11 DataSource 范围与取消

| ID | 场景 | 期望 |
|---|---|---|
| DATA-01 | position=0,length=已知 | Media3 等待窗口从 0 开始且不超过 256 KiB；当前播放 TDLib 请求可有界前读但单次不超过 4 MiB |
| DATA-02 | position=5MB | 从 5MB 打开并返回对应字节 |
| DATA-03 | length=LENGTH_UNSET | 以固定块请求，open 返回已知剩余或 LENGTH_UNSET |
| DATA-04 | seek 到新位置 | 旧 owner 释放，新 generation/offset 生效 |
| DATA-05 | updateFile 尚无连续前缀 | 有界等待，不读取垃圾区 |
| DATA-06 | updateFile 覆盖所需范围 | 从 local.path 的绝对 offset 读取正确字节 |
| DATA-07 | close 等待中的 DataSource | 等待取消，owner 释放，无死锁 |
| DATA-08 | 最后 owner 释放 | 调用 cancelDownloadFile；仍有 owner 时不取消 |
| DATA-09 | 超时/断网/文件消失 | 明确 IOException 类别，有限重试 |
| DATA-10 | 主线程调用 open/read | 立即拒绝，防止 ANR |
| DATA-11 | late updateFile | 被旧 generation 忽略 |
| DATA-12 | 文件总大小未知后变为已知 | 不越界，后续读使用新状态 |
| DATA-13 | 顺序读取跨越 256 KiB 边界 | 先取得下一段 owner 再释放当前 owner，TDLib 有界前读不被边界切换取消 |
| DATA-14 | 单一前台 owner 请求 256 KiB 窗口 | TDLib `DownloadFile` 从该 offset 开始、单次受限为 4 MiB；lease 只等待原 256 KiB 可读窗口 |
| DATA-15 | length=LENGTH_UNSET 且 file.size 已知 | open 返回从 position 起的精确剩余长度；file.size 未知时仍返回 LENGTH_UNSET |
| DATA-16 | 弱网每 15 秒内仍有相关连续字节增长 | 刷新无进展窗口并继续等待；总等待最多为 6 个窗口（90 秒） |
| DATA-17 | owner 在 Loader 等待时释放 | 立即 notify 等待线程并返回文件请求已取消，不等到 stall 超时 |

## 7. Compose/仪器测试

本节 UI-01 至 UI-16 定义页面断言；实际 Compose 门槛按 1.1 Path B 执行。共享 suite 位于 `app/src/sharedTest/java`，同时由 Robolectric 和 API 36 AOSP x86_64 instrumentation 使用。`instrumentation` target APK 排除全部 `.so`，因此它不得用于 TDLib native 验收。

| ID | 页面 | 步骤 | 期望 |
|---|---|---|---|
| UI-01 | 登录 | 依次注入 WaitingPhone/Code/Password/Error | 只显示对应输入，中文错误存在，敏感输入不跨状态保留 |
| UI-02 | 频道 | 显示三个频道，搜索并多选两个 | 选择数=2，保存意图含正确 chatId |
| UI-03 | 标签 | 选择两个标签并切换 OR/AND | ViewModel 收到准确筛选，清空恢复无标签 |
| UI-04 | Feed 空状态 | Repository 返回空 | 显示无视频说明和频道/筛选入口 |
| UI-05 | Feed 播放错误 | 注入 DecoderUnsupported/Timeout | 中文错误和重试按钮正确 |
| UI-06 | Feed 不支持流式 | supportsStreaming=false | 显示“该视频暂不支持流式播放。”，无播放请求 |
| UI-07 | 设置 | 选择 200MB、500MB、1GB、2GB、5GB、10GB、15GB、20GB | 设置保存并显示正确值 |
| UI-08 | 设置清缓存 | 点击并注入成功/部分失败 | 显示真实结果，不影响登录和索引 |
| UI-09 | 深色模式 | 系统深色主题 | 文字和控件可读，无品牌复制 |
| UI-10 | 系统返回 | 各二级页面返回 | 返回栈正确，不跳过必需授权步骤 |
| UI-11 | 受保护视频 | 切入/离开保护项 | FLAG_SECURE 设置并恢复 |
| UI-12 | 权限 | 检查 merged manifest | 只有 INTERNET 和 ACCESS_NETWORK_STATE |
| UI-13 | 设置质量 | 依次选择自动、省流、720p、原画 | 四个选项可见，选择事件和持久状态一致 |
| UI-14 | Feed 初始顺序 | 直接渲染新会话首个 state | “随机”首次即 selected，“最新”不出现中间 selected state |
| UI-15 | 1.1.0 品牌 locale | 分别注入 `en-US`、`zh-CN`、`zh-HK` 并检查 APK badging | 英文显示 `VELORA` 与英文标语；简体/繁体中文显示“曜流”与中文标语；品牌名高度至少 28dp；APK `versionName=1.1.0` 且启动器标签一致 |
| UI-16 | 创造者 locale | 分别注入 `en-US`、`zh-CN`、`zh-HK` 并渲染登录页 | 英文只显示 `Created by Kirxuan`；简体/繁体中文只显示“创造者：麒轩”；验证码等授权状态仍在默认视口可见 |

## 8. 真机自动/半自动验证

前置：连接并授权手机，记录型号、Android 版本、SDK、ABI、网络类型和可用空间。

1. adb devices：设备状态必须是 device，不是 unauthorized/offline。
2. adb shell getprop ro.product.cpu.abi：记录唯一目标 ABI。
3. gradlew.bat installDebug：安装成功。
4. gradlew.bat connectedDebugAndroidTest：运行适用 UI 测试。
5. 旋转不作为专项适配，但竖屏生命周期不能崩溃。
6. 后台/前台、锁屏、耳机拔出、Wi-Fi/移动数据切换。
7. dumpsys/日志确认任意时刻只有一个主要播放器和有限文件请求。
8. 清缓存前后检查应用私有占用，不检查或写入公共相册。

历史阶段 4 结果：debug APK 安装通过；Room instrumentation 9/9、TDLib native instrumentation 1/1 通过。历史 Vivo 完整 instrumentation 在首个既有 `LoginScreenTest` 后被 OriginOS 进程管控杀死，仅执行 1/16；隔离频道套件也在首项后被相同链路中止，仅执行 1/3。它不再是 Compose 门槛。该阶段 Path B 四项全部通过：host 编译、共享 Robolectric Compose suite、无 native instrumentation target APK、`CVF_AOSP_API36_X86_64`（API 36/x86_64）18/18 Compose UI instrumentation，以及 V2307A Android 16 ARM64 install + launch smoke。

历史阶段 4 的真实扫描统计在执行全应用 instrumentation 之前，通过强停生产应用并直接聚合其 Room 数据库取得。当时 `connectedDebugAndroidTest` 收尾卸载目标 debug 包并清除了当时的本地 TDLib 会话和 Room 数据；该陈述不描述阶段 8 当前设备状态。

## 9. 真实 Telegram 人工验收

以下不能用 Fake 证明生产可用：

- MANUAL-01：真实手机号→验证码→两步验证→授权成功。
- MANUAL-02：杀进程/重启后会话恢复。
- MANUAL-03：退出账号后会话、账号索引和媒体清除。
- MANUAL-04：频道列表只含账号有权访问的频道。
- MANUAL-05：选择频道后近期视频快速可用，后台页继续扫描。
- MANUAL-06：重启后从历史游标继续。
- MANUAL-07：频道新增、编辑、删除消息被增量同步。
- MANUAL-08：中英文真实 hashtag 与 Telegram 客户端显示一致。
- MANUAL-09：支持流式视频从开头、seek 到中间和快速滑动可播放。
- MANUAL-10：supportsStreaming=false 不触发完整下载并显示精确提示。
- MANUAL-11：Wi-Fi 只预加载下一条少量数据；移动数据默认不预加载。
- MANUAL-12：缓存上限和 LRU 在实际 TDLib 文件上生效。
- MANUAL-13：受保护内容无保存/导出/分享并启用 FLAG_SECURE。
- MANUAL-14：getMessageLink 成功跳转原消息，失败显示中文错误。
- MANUAL-15：断网、恢复、文件失效、解码不支持和 FLOOD_WAIT 行为。
- MANUAL-16：30 分钟连续滑动没有多播放器发声、明显资源泄漏或无界缓存增长。
- MANUAL-17：温度上升/省电/低电量条件下停止预加载。
- MANUAL-18：同一真实视频切换原画与省流/720p 后，实际 fileId、分辨率和文件大小符合选择；没有候选时正常回退。

不得把自动化 Fake 结果写成上述人工验收已通过。

## 9.1 交付阶段 8 证据摘要

- Fresh `test`、`lint`、`assembleDebug` 全部退出码 0；Gradle XML 共记录 400 次测试执行（包含 debug/release/instrumentation variant 的相同 JVM 测试变体），失败/错误/跳过均为 0。
- Compose Path B 最终为：编译通过、Robolectric 共享 suite 30/30、API 36 AOSP x86_64 emulator 30/30、ARM64 真机 install+launch smoke 通过；instrumentation target APK 的 native 条目数为 0。
- Room 在 API 36 x86_64 emulator 上 12/12，通过 1→2、2→3、3→4 迁移。根 `connectedDebugAndroidTest` 在 Android 13 真机因 OEM 拒绝新测试包安装而实际运行 0 条，不得写为通过；TDLib ARM64 native 本阶段复验为尚未验证。
- Android 13 ARM64 真机完成最终 APK 数据保留安装、冷/热启动、登录恢复、后台/锁屏/杀进程恢复、100 次连续滑动、30 组快速往返、热状态/省电状态注入、90° 旋转、生产私有视频缓存清理、系统返回、播放器释放和音频焦点申请/释放。真实退出登录、低存储、物理耳机拔出、其他应用抢占音频焦点以及真实受保护消息仍为尚未验证。
- 权限恰好为 `INTERNET` 与 `ACCESS_NETWORK_STATE`；merged manifest 为 `allowBackup=false` 并引用两套排除全部 app 数据的备份规则。真实凭证的仓库外泄扫描、允许日志的静态/动态扫描均为 0 命中。
- 完整 34 项矩阵、性能、缓存、首个失败根因和 debug APK 哈希见 `docs/STAGE8_FINAL_ACCEPTANCE.md`。该报告不宣称项目已经完全稳定。

## 9.2 优化阶段 9 证据摘要

- 回归测试先复现两项根因：已知 file.size 的无界 DataSpec 仍返回 LENGTH_UNSET；相关区间持续增长时仍在固定截止点抛出 TelegramFileTimeoutException。
- `TelegramMediaDataSourceTest` 与 `TelegramFileManagerTest` 目标单元测试已通过，覆盖已知/未知长度、慢速持续进展、无进展截止、90 秒硬上限和 owner 释放唤醒。
- 256 KiB 下一条预加载、4 MiB 当前前读、50–60 秒播放器缓冲、500MB TDLib 私有缓存和唯一 ExoPlayer 均未改变。
- Fresh `test`、`lint`、`assembleDebug` 均退出码 0；XML 共 410 次测试执行，0 failure、0 error、0 skipped。Android 13 ARM64 真机 `installDebug` 通过。
- 真实弱网首帧、seek、rebuffer 和有效吞吐对照仍为尚未验证；完整结果见 `docs/STAGE9_WEAK_NETWORK_HANDOFF.md`。

## 9.3 优化阶段 10 证据摘要

- `PlaybackSessionMetricsTest` 覆盖初始 BUFFERING、自动播放 rebuffer、恢复时长、重复回调、seek/暂停排除和 reset；目标测试与播放器编译通过。
- Fresh `test`、`lint`、`assembleDebug` 均退出码 0；XML 共 416 次测试执行，0 failure、0 error、0 skipped。
- Android 13/API 33 ARM64 真机保留账号与缓存覆盖安装成功；APK 权限仍只有 `INTERNET` 和 `ACCESS_NETWORK_STATE`。
- 真实 61.7 MiB / 21 分 50 秒 / 1080p 流式样本连续播放 209.97 秒：首次 READY 1.70 秒，释放前 position 209.97 秒、bufferedPosition 267.03 秒、前瞻 57.06 秒、rebuffer 0、目标包错误 0。
- 4–12 MiB 两个连续 4 MiB 边界耗时合计 175.79 秒，约 46.6 KiB/s；样本平均媒体字节率约 48.2 KiB/s。返回频道页后私有 TDLib 缓存 15 秒不再增长。
- 本次保留用户现有 Wi‑Fi、VPN、账号和缓存，不是人工限速或严格空缓存实验；高码率大文件、seek、断网恢复仍为尚未验证。完整证据见 `docs/STAGE10_WEAK_NETWORK_BASELINE.md`。

## 9.4 优化阶段 11 证据摘要

- 选择策略、TDLib 候选映射、Repository 瞬时刷新、DataStore、当前/下一条一致选择、3 秒回退和设置 UI 均有自动化覆盖。
- Fresh `test`、`lint`、`assembleDebug` 均退出码 0；72 个 XML 文件共 437 次测试执行，0 failure、0 error、0 skipped。
- Compose Path B 最终为：编译通过、指定 Robolectric-Compose suite 通过、API 36 AOSP x86_64 emulator 31/31、Android 13 ARM64 真机 install+launch smoke 通过。
- 同一真实视频从原画 1670×1080 / 20,360,719 bytes 切换为 Telegram 服务端省流 740×480 / 3,284,570 bytes，完整文件预算减少约 83.9%；最终实际绑定为替代 fileId，首次 READY 386ms，rebuffer 0。
- APK 权限仍只有 `INTERNET` 和 `ACCESS_NETWORK_STATE`；备份关闭，源码中未新增本地转码、HLS、公共存储、硬编码凭证或敏感日志路径。
- 严格空缓存蜂窝流量抓包、更多 codec/视频覆盖率和播放中无缝升降清晰度仍为尚未验证。完整证据见 `docs/STAGE11_SERVER_VIDEO_QUALITY.md`。

## 9.5 优化阶段 12F 证据摘要

- 三态策略只驻留内存，输入限于网络/计费、低存储/低内存、power/thermal、用户质量选择、当前项缓存命中、最近五个 bind→首帧样本、失败/rebuffer/首帧；不使用设备、账号、频道或内容身份。
- OFF 立即让路；CONSERVATIVE/NORMAL 的唯一下一条上限均为 256 KiB。真机 64 KiB 先出现 5/12 不完整、第二轮 P50/P90 1121/1622ms；128 KiB 只有 8/12，两个小前缀均被否决而非隐藏。
- 最终正常 12 次 FIRST_FRAME 12/12，gesture→首帧 868/1136/1217ms，bind→首帧 241/506/581ms，较 12A P50/P90 改善 20.3%/34.6%，promoted 12/12，rebuffer/crash 0/0。快速 10 次最终可见项 FIRST_FRAME 1，SUPERSEDED 18，FAILED 0。
- slow drag 为 UNCHANGED 1 且 bind/prepare 0；Home/前台、返回频道再进入、暂停/继续均通过且 0 rebuffer/crash。当前手机最终反向补验为 SUPERSEDED 2、UNCHANGED 1、preload yield/resume 1/1、FAILED/rebuffer/crash 0/0。
- 当前手机依次确认 AUTO、DATA_SAVER、HD_720、ORIGINAL 并恢复原 DATA_SAVER；fresh 定向测试证明质量变化使旧 plan generation 失效，seek/暂停缓冲不计 rebuffer。
- benchmark 默认不清数据/缓存、不改网络/VPN，只清本轮 main/crash logcat；不能安全确认播放页或样本不足时非零退出。UID netstats 不可安全聚合时报告“尚未验证”，不伪造 0 bytes。
- fresh 主机 `test`、`lint`、`assembleDebug` 通过；两组 Robolectric 通过。API 36 x86_64 emulator 首次 37/38（一个无 crash 的 Espresso idle timeout），隔离复跑后完整 38/38 通过；当前 Android 13 ARM64 实体机 install+launch smoke 通过。
- 仓库所有者于 2026-07-30 更新范围：当前手机是唯一实体机目标；原 iQOO/Vivo smoke 与所有真实网络切换/UID traffic 免验，不再属于未完成项。VPN/Clash Meta 未关闭或修改；历史流量“尚未验证”继续保留，不能改写为 0。
- 完整对比、失败证据、矩阵、安全审计、尚未验证项和回滚见 `docs/STAGE12F_FINAL_PERFORMANCE_ACCEPTANCE.md`。

## 9.6 优化阶段 13A 证据摘要

- `DEFAULT_VIDEO_FEED_ORDER` 是新播放会话唯一默认顺序事实，UiState 与 ViewModel criteria 均引用 RANDOM；顺序仅驻留当前 ViewModel，不增加 DataStore/Room 字段。
- JVM/Compose 窄测试覆盖首个 Loading 与首次 Content、UI 首次选择、会话内 LATEST、Room Flow 更新、新建 ViewModel、空列表、筛选变化和随机转场上下文；窄测试已通过。
- `CVF-Transition` 在既有 Debug 脱敏 summary 中增加 order、方向、轮次边界、refreshMillis 与 bind→first-byte；字段只观测既有状态和安全单调时间戳，不参与调度。
- RANDOM benchmark 在 UI/成功 summary 两层确认顺序，并对 order、请求方向一致性、轮次边界、promoted/plan age、刷新、首区间、READY 与真实首帧设完整性门槛；UI 解析识别 Compose selected 父语义节点，且任意已有首帧日志不能绕过 UI 门槛。缺失、LATEST 或方向不符必须 FAIL。
- 首次 fresh full test 在 118 项中的旧 Compose 复合用例暴露隐式 LATEST 夹具；目标用例连续两次稳定复现后，只把该点击 RANDOM 的夹具改为显式 LATEST。修复后目标测试和 fresh 全量 test 通过。
- fresh `test`、`lint`、`assembleDebug`、instrumentation 编译、两组指定 Robolectric 和 API 36 x86_64 emulator 39/39 通过；2026-08-01 当前 Android 13/API 33 ARM64 实体机保留数据 install+launch smoke 通过。
- RANDOM Normal 第一轮成功样本 5/12，均证明 RANDOM/FORWARD/字段完整、promoted 5/5 且 0 FAILED/UNSUPPORTED/rebuffer/crash；其 warm/mixed bind→首帧 P50/P90/max 为 5,871/8,029/8,029ms。第 6 个自然冷缺页样本迟到完成，bind→first-byte 12,457ms、bind→首帧 12,623ms、gesture→首帧 13,242ms，超过固定 12 秒门槛。
- 同协议第二轮首条再次超过 12 秒且报告为 0/12；没有通过提高超时、清缓存或改网络制造 PASS。Fast Reverse 10 次在严格 RANDOM UI 门槛下产生 SUPERSEDED 9、UNCHANGED 1、FIRST_FRAME 0；最终跨随机轮次回到同一项，没有生成 bind/READY/真实首帧样本，因此报告按合同 FAIL。真实 RANDOM 性能基线未通过；完整阶段记录见 `docs/STAGE13A_RANDOM_DEFAULT_BASELINE.md`。
- 13A 补验未改变数据、缓存、网络或 VPN：Normal Forward 为 4/12，四个成功样本均为 RANDOM/FORWARD、字段完整、promoted 4/4、refresh SUCCESS 4/4，gesture→首帧 P50/P90/max 为 1,539/8,156/8,156ms，bind→首帧为 919/7,533/7,533ms，bind→first-byte 为 718/6,931/6,931ms；第五次超过 12 秒。Fast Reverse 仍为 SUPERSEDED 9、UNCHANGED 1、FIRST_FRAME 0。两项均严格 FAIL，0 FAILED/UNSUPPORTED/rebuffer/crash。
- 用户明确免除非计量 Wi‑Fi Normal 复测后，仅调整 Fast 观测协议：新增可选 `-FastCheckpointEvery`，默认0仍保持原连续 Fast；显式3把总计10次分为3/3/3/1，每批内仍为80ms手势与100ms间隔。真机补验严格 PASS，FIRST_FRAME 2、SUPERSEDED 6、UNCHANGED 2；成功样本全部 RANDOM/REVERSE、字段完整，refresh SUCCESS 2/2，0 FAILED/UNSUPPORTED/rebuffer/crash。gesture→首帧 P50/P90/max 为 2,180/12,020/12,020ms，bind→首帧为 1,630/11,342/11,342ms，bind→first-byte 为 1,418/10,890/10,890ms。

## 9.7 优化阶段 13B 候选与回滚证据摘要

- 红测稳定证明旧生产行为会在 `onPageUnstable()` 关闭匹配 next owner，并在同 fileId 从 `NEXT_PRELOAD` 提到 `CURRENT_STARTUP` 时取消活动请求后重建。
- 候选状态由 player 层的单一 snapshot 与 generation 管理；只有 `VideoKey + fileId + generation` 全匹配才 commit。DataSource 在当前 lease acquire 并登记成功后、等待字节前通知 handoff，随后才释放旧 next owner。
- app/player/telegram 回归测试覆盖匹配 owner 顺序、反向/改变/回弹、A→B→C、质量 fileId 变化、acquire 失败、硬策略/seek/release/logout、unsupported streaming、迟到回调与唯一 speculative fileId；既有测试继续证明单 ExoPlayer/PlayerView 上限。
- telegram 候选仅对同 fileId 且活动请求包含目标范围时原位提优；解析器只把明确的 `NEXT_PRELOAD→CURRENT_STARTUP result=REUSED_ACTIVE` 计为活动请求复用，不能误计正常的 STARTUP→CONTINUATION 降级。
- 第一份真机报告 0/12 时应用不在播放页，属于无效 setup 样本。安全进入播放测试页并确认 RANDOM 后，有效 Normal Forward 报告为 FIRST_FRAME 4/12、严格 FAIL，成功样本 RANDOM/FORWARD/字段完整，promoted 4/4，FAILED/UNSUPPORTED/rebuffer/crash 0/0/0/0。
- 有效样本 bind→first-frame P50/P90/max 为 1,027/8,925/8,925ms；13A 同协议可比较基线为 919/7,533/7,533ms，P90/max 均恶化约 18.5%。bind→first-byte P90 仍为 8,822ms，不能使用“完全缓存、无可比性”例外。
- 当次自适应预加载因 `NETWORK_NOT_ALLOWED` 为 OFF：promotion attempt/matched/terminal 为 10/0/10，活动请求复用 0，scheduler 明确复用 0。没有真实匹配 handoff 样本，也没有可靠性能收益。
- 按合同回滚：`VideoPreloadManager.PRODUCTION_OWNER_PROMOTION_ENABLED=false`，`TelegramFileManager.PRODUCTION_REUSE_CONTAINED_ACTIVE_REQUEST=false`，且单元测试锁定。生产恢复 13A 行为；候选仅由测试构造函数显式启用，保留红测、解析器和失败证据。
- 回滚后的 telegram/player/app 模块测试、fresh `test`（345 tasks）、`lint`（220 tasks）、`assembleDebug`、instrumentation 编译、两组 Robolectric、API 36 x86_64 emulator Compose UI 39/39、当前 Android 13/API 33 ARM64 真机 install+launch smoke 均 PASS。
- 13B 性能验收结论是 FAIL/生产未启用，不得写为已交付。完整合同、比较与尚未验证项见 `docs/STAGE13B_PRELOAD_OWNER_PROMOTION.md`；当时未进入 13C，之后的 13C 由 2026-08-03 用户单独明确授权，且没有翻转这两个生产开关。
- 2026-08-09 在已验证非计量 Wi-Fi、同一手机、同一 13C 代码和不清数据/缓存/网络/VPN的条件下完成 A/B。关闭 13B 候选基线严格 PASS：FIRST_FRAME 12/12，bind→first-frame P50/P90/max 630/1,599/1,783ms，YIELD/RESUME 12/12，rebuffer/crash 0/0。
- 临时开启候选后严格 FAIL：FIRST_FRAME 11/12，SUPERSEDED 10、UNCHANGED 1；promotion attempt/matched/terminal 11/11/11、`reusedActiveRequest=8`、明确 `NEXT_PRELOAD→CURRENT_STARTUP REUSED_ACTIVE=2`、提前取消 0、handoff P50/P90/max 457/466/467ms。owner 正确交接已经得到真实证据。
- 候选 bind→first-frame P50/P90/max 969/3,033/4,013ms，较同设备关闭候选基线恶化约 53.8%/89.7%/125.1%；同时违反 FIRST_FRAME 100%、P90 至少改善 15%和最大值不恶化超过10%三项门槛。候选是后测且缓存条件更有利，仍无可靠收益，因此强制回滚。
- benchmark 入口不再在已安全确认播放页时发送 warm launcher intent，避免工具把自己的前置页面重置为频道页；新增受限 `-ReportStage stage13b|stage13c`，默认仍为 stage13c，脚本测试 PASS。无效并发/setup 样本不参与 A/B。
- 两个生产开关恢复 false 后重新安装基线 APK，并重跑 fresh 三模块测试（146 tasks）、full test（345 tasks）、lint（220 tasks）、assembleDebug、instrumentation 编译、两组 Robolectric、API 36 x86_64 emulator 40/40 和当前手机 launch smoke，均 PASS。

## 9.8 优化阶段 13C 随机轮次与方向目标证据摘要

- 红测确定性复现旧边界：当前最后项 next 指向旧轮第一项，settle 后才重新 shuffle 出不同首项；实现后 next 在播放最后项前已等于 `upcoming.first`。
- `RandomRoundState` 只保存 current/upcoming、各自 generation、current index 和 previous boundary key；upcoming 生成只处理 Room 已有元数据，不触发媒体下载或批量消息刷新。
- 边界 settle 晋升同一个 upcoming items/generation，并只生成新的唯一 upcoming；PlaybackPlan token 包含 round generation，晋升后仍通过 gate，不能靠移除 generation 校验通过测试。
- 来源刷新保留 current/upcoming 的复合 key 顺序并替换 fileId 等对象字段；删除当前末项/upcoming 项、新增项、筛选和顺序变化都有确定性测试。
- Pager current/upcoming 映射以绝对 round anchor 维持边界页连续；媒体身份仍为 `chatId + messageId`，虚拟随机 Compose key 继续含 pagerPage。
- committed target 只在 `currentPage` 越过 snap 中点后发布。反向或快速换向以 `forward→null→committed` 替换唯一候选；慢拖回弹不错误 bind，快速跨页只绑定最终 settled key。
- player 既有 FakeGateway 继续证明连续 target 的 `maxActiveLeases=1`；本阶段没有修改 player/Media3 参数或 256 KiB 上限，13B 两个生产开关仍为 false。
- benchmark parser/runner 新增随机边界计划晋升 count/eligible/rate，默认报告目录为 `build/reports/stage13c`；脚本测试 PASS。13B 续验增加显式 stage13b 证据目录，但没有改变 stage13c 默认值。
- 连续 Fast 单批真机首跑在发手势前暴露 PowerShell `StrictMode` 标量 `.Count` 缺陷；先增加红灯回归断言，再将 `$fastBatches` 显式声明为 `int[]`。脚本测试与同协议重跑均 PASS，未更改生产手势、Pager 或播放器参数。
- fresh `test` 为 345/345 tasks、lint 为 220/220 tasks，assembleDebug、instrumentation 编译、两组 Robolectric 均 PASS；API 36 AOSP x86_64 emulator 为 40/40 PASS。
- 2026-08-09 当前 Android 13/API 33 ARM64 手机 install+launch smoke PASS；当前 13C 且 13B 开关关闭的 RANDOM Normal Forward 12/12、Normal Reverse 12/12、慢拖跨中点后回弹、连续 Fast Reverse 10 次均 PASS，0 rebuffer/crash。Normal Reverse 的首次同页预热样本严格保留 FAIL，稳定重跑才计 PASS；慢拖产生 forward target `SUPERSEDED=1` 后最终 `UNCHANGED=1`，没有新 bind/首帧，`playerInstances=1`。Fast 最终 FIRST_FRAME 1、SUPERSEDED 15、promoted 1/1。
- 当前筛选约 5810 项，少量安全手势未产生真实 forward 随机轮次边界；该真机项仍“尚未验证”。未清数据、改筛选、退出账号或修改网络/VPN制造小队列；确定性 domain/ViewModel/Compose 边界晋升和 stale callback 测试已通过。完整记录见 `docs/STAGE13C_RANDOM_ROUND_TARGETING.md`。未进入 13D。

## 9.9 优化阶段 13D 启动区间观测与候选证据摘要

- `TelegramMediaDataSource` 只在 Media3 `open(DataSpec)` 边界记录首个未命中 HEAD/TAIL/MIDDLE/UNKNOWN、当前已覆盖字节和 extractor DataSpec switch；内部 256 KiB chunk 前进不误计为 extractor seek。
- `VideoPlayerManager` 在真实首帧输出 bind→first byte、first byte→READY、候选、覆盖、current 是否复用 next owner；benchmark 汇总 TDLib switch/merge/cancel 和 NO_PROGRESS。日志不含路径、remote id、网络身份、媒体内容或媒体字节。
- 两轮初始正常与一轮 Fast 的 28 个成功首帧中，HEAD 26、NONE 2，TAIL/MIDDLE/UNKNOWN 均为 0。候选 A/B 的 tail miss 证据前提不存在，因此没有运行 tail64/tail128 真机 APK。
- BuildConfig 只接受 BASELINE、TAIL_64、TAIL_128、HEAD_512_WIFI。纯规划与 manager 自动化覆盖 256/320/384/512 KiB 硬上限、unknown size、small file 不重叠、tail 缓存去重、tail 失败、同目标顺序 lease、target 改变双段取消、唯一 fileId、OFF/默认移动网络/unsupported/logout/release 为 0。
- benchmark 增加每个已验证手势前的幂等 WAKEUP，修正实体机短灭屏使手势只唤醒的协议缺陷；旧 0 样本失败报告保留。修正后的 BASELINE 正常轮为 12/12 PASS（P90/max 4,305/4,369ms）和 8/12 FAIL（6,794/6,794ms），Fast checkpoint 轮无首帧，证明当前自然网络窗口存在严重长尾。
- HEAD_512_WIFI 候选在修正后的同协议 Forward-prime 60 秒仍无首帧并记录 4 次 NO_PROGRESS。当前项首帧前策略 OFF，故该轮没有 speculative next 请求、实际额外字节 0；它既不能归因于 512 KiB，也不能证明任何收益。没有完成两轮 12+10，更没有达到 FIRST_FRAME 100%、P90 改善 15% 和 max 上界。
- 生产结论为 FAIL/不启用候选：`STARTUP_RANGE_CANDIDATE=BASELINE`、head 256 KiB、tail 0、额外 speculative 0。候选停止后默认 player 单测、assemble 与实体机覆盖安装/launch smoke 再次 PASS。完整记录见 `docs/STAGE13D_RANDOM_STARTUP_RANGES.md`；未进入 13E。

## 9.10 优化阶段 13E 消息引用解析与透明恢复证据摘要

- `TelegramMessageRepository` 返回应用自有 `VideoReferenceResolution`，明确区分成功、消息删除、unsupported 与脱敏瞬时失败；TDLib 类型、callback 和完整错误不越过 telegram 边界。
- `TdLibTelegramMessageRepository` 以 `VideoKey` 协调活动 single-flight。并发 current/next waiter 共享一次官方 `getMessage`；最后 waiter 取消会取消请求，账号退出清空全部 flight；完成后不形成历史缓存。
- 所有需要消息解析的 current/唯一 next 路径统一使用 3 秒软期限，包括 RANDOM + ORIGINAL；Repository 的 15 秒硬上限只保留为基础设施防线。普通异常安全回退索引引用，Cancellation/Error 不被吞掉。
- FILE_UNAVAILABLE 在同一转场/Loading 内最多透明恢复一次。刷新成功后按当前质量重新选择；同一 fileId、刷新失败、第二次 FILE_UNAVAILABLE、删除或 unsupported 才完成错误转场。页面与质量/网络/账号/队列/random generation 共同阻止迟到绑定。
- 不增加 16 项历史缓存，继续复用现有 current/next `PlaybackPlan` 两槽及其 generation 失效；不修改 Pager 动画、预加载预算或 13D `BASELINE` 生产参数。
- 转场摘要与 benchmark 增加 `transparentRecoveryAttempts` 和脱敏 outcome 枚举。2026-08-10 fresh 定向续验为 Repository 14、ViewModel 57、transition metrics 14、callback gate 2、player error policy 3，共 90/90 PASS；PowerShell parser 测试也 PASS。完整 Proof、Compose Path B 与真机结果见 `docs/STAGE13E_RANDOM_REFERENCE_RESOLUTION.md`。
- Repository 在返回前触发的同 key Room 元数据写回不会取消该次解析；删除/unsupported 同步把当前项移出列表时，终态仍可完成并覆盖 Empty 页面为明确不可播放状态。成功绑定仍要求完整 key/generation 有效。
- 最终 fresh `test` 345/345、lint 220/220、assembleDebug、Compose 主机步骤、API 36 x86_64 emulator 41/41 与当前 Android 13 ARM64 真机 install+launch smoke 均 PASS；Vivo/iQOO Android 16 尚未验证。
- 初始 setup/无首帧和续验早期媒体长尾 FAIL 报告均保留。有效播放页的迟到终局中 refresh 只有 5–44ms，长时间来自 bind→first-byte/READY 的 12.5–34.5 秒窗口，未把媒体网络长尾误判为 15 秒 Repository 刷新。
- 网络窗口恢复后的完整 RANDOM Normal Forward 严格 PASS：FIRST_FRAME 12/12、refresh SUCCESS 12/12、promoted 12/12，message refresh P50/P90/max 22/46/49ms，bind→first-frame 910/3,368/3,834ms，rebuffer/crash 0/0。正常已准备项没有被 refresh 增加可见长尾。
- 连续 RANDOM Fast Reverse 单批 10 手势严格 PASS：FIRST_FRAME 1、SUPERSEDED 18、FAILED/UNSUPPORTED/UNCHANGED 0，最终 refresh 13ms、bind→first-frame 1,394ms，rebuffer/crash 0/0。快速滑动无终态刷新风暴；并发同 key 只调用一次官方 `getMessage` 由 fresh Repository 测试直接计数断言。
- 自然 stale `fileId` 没有出现，真机一次透明恢复仍“尚未验证”；没有清库、清缓存、退出账号或改网络/质量制造样本。自动化已覆盖恢复成功、当前质量重选、同 fileId/二次失败终止、删除/unsupported、FLOOD_WAIT、软超时与快速离页无迟到绑定。
- 原 9 份设备/测试日志与续验新增 16 份 benchmark report/log 的两轮敏感字段扫描均为 0；续验 51 个转场摘要中 FAILED/UNSUPPORTED、UNKNOWN 顺序/方向、非单 player instance 均为 0，目标包 crash buffer 为 0。Manifest 实际权限仍仅 INTERNET、ACCESS_NETWORK_STATE，`local.properties` 仍被 git ignore。13D `BASELINE`、head 256 KiB、tail 0、额外 speculative 0 与两个关闭的生产实验开关均未改变。

## 9.11 优化阶段 13F Pager settle 与最终验收证据摘要

- 单变量候选只把 Pager snap 设为稳定 API `tween(360)`；没有修改手势阈值、settledPage 最终 bind、播放器、Surface、预加载数量/字节、TDLib、质量、缓存、权限、Room 或 DataStore。
- 新鲜 13E 默认 release→settle P90/max 为 470/471ms，clean gfx 现代 jank 9.89%；候选两轮 release→settle 为 376/376ms、375/376ms，但现代 jank 11.66%/10.79%，P90/P95 帧耗时也回退，因此候选被否决并恢复默认 Pager。
- 恢复默认后的最终两轮 Normal Forward 均 FIRST_FRAME 12/12、FAILED/rebuffer/crash 0；bind→首帧 P90/max 分别为 1,193/1,325ms 与 619/1,578ms。Fast Reverse 10 次 FIRST_FRAME 1、SUPERSEDED 17、FAILED/rebuffer/crash 0，最终 key 唯一。
- 慢拖未跨中点得到 UNCHANGED 且无 bind terminal；正向后立即反向得到旧 target SUPERSEDED、最终原页 UNCHANGED。API 36 emulator 41/41 覆盖 target 回摆、ticker、VideoKey 占位、PlayerView attach/detach 和 ActivityScenario.recreate 活动绑定上限 1。
- 实体机返回频道记录 release binding 和 surface detach；同进程再进入的 `playerInstances=2` 是串行累计创建数，旧 engine 已先 release/置空，活动绑定仍为 1。进程重建后 PID 改变且再进入为 `playerInstances=1`。
- 受保护页在当前 OEM 上接受 adb Pager swipe、拒绝 adb click/seek；实体机 pause/resume/seek 为尚未验证，自动化与模拟器证据不得冒充实体机人工结果。13F 两轮没有自然命中随机边界，未清缓存/改筛选制造样本；相同最终生产配置的 13E 新鲜基线已有一次边界 PASS。
- Home 后按既有 PLAYER-04 只执行播放器 pause 和预加载 stop，没有 full release；页面返回与进程终止的完整释放通过，但后台项按本次 13F 字面合同为 FAIL。该生命周期不能作为 Pager 单变量附带修改。
- 不同 RANDOM 媒体、缓存温度和自然网络窗口不能写成严格因果 A/B。相对 13A 累积生产基线的算术方向满足 15% 门槛，但严格同媒体/同缓存验证仍为尚未验证。完整证据、失败候选和回滚顺序见 `docs/STAGE13F_RANDOM_FINAL_ACCEPTANCE.md`。

## 9.12 Stage 18 HLS、ABR、动态预热与最终验收

- Mapper 同时覆盖 `AlternativeVideo.video`/`hlsFile`，反射边界证明应用层无 `TdApi.*`；Room schema 不保存 manifest/token/fileId 临时映射。
- HLS parser 覆盖 master/media、MAP、BYTERANGE、循环、超限、外部 scheme、路径穿越和 generation 伪造；所有成功 range 仍经 Fake `TelegramFileGateway` 的 offset/limit、timeout/cancel/owner 断言。
- fast/slow EWMA、TTFB、缓存排除、网络复位、立即降级、延迟升级、抗震荡、deadline abandon、最低安全表示层和同表示层不重建计划均有纯 Kotlin 测试。
- next budget 覆盖 0/2/5/10 MiB、512 KiB chunk、移动数据默认 0、低 buffer/seek/rebuffer 抢占、目标改变取消、唯一下一条、owner 防误删和 10 MiB 硬上限。
- SampleQueue 测试证明同一 preload builder/ExoPlayer、MediaSource 单次交接、取消/错误目标不交接、current+唯一 next 生命周期、metadata-only 的 0 payload track 预备、NEXT→CURRENT 后解除 preload cap、Surface/单播放器回归和独立 feature flag；真实 A/B 未执行，故默认关闭。
- 固定 seed `18018` 在 0.35/0.5/1/2/12 Mbps 各执行 30 次转场；可持续档 rebuffer=0、FIRST_FRAME=30/30，所有档 crash/black/wrong/audio overlap=0，waste P95≤512 KiB，单目标最大 3,164,063 B、硬上限测试为 10 MiB，移动数据默认 0。
- 合成已准备首帧总范围：P50 130.3～142.0 ms、P95 192.7～227.5 ms、max 261.3 ms。0.5/1/2 Mbps 相对 Stage 17 合成 P95 改善 88.7%/80.2%/72.6%；12 Mbps 改善 20.9%。这些数值不能冒充真机/CDN 测量。
- 真实账号 HLS 覆盖、TDLib native/CDN、iQOO 12、真实 Surface/decoder 与 SampleQueue A/B：尚未验证。完整证据见 `docs/STAGE18F_FINAL_PERFORMANCE_AND_SECURITY_ACCEPTANCE.md`。

## 9.13 Stage 19 UI、Motion 与 Android 16 系统 UI

| ID | 验收项 | 结果 |
|---|---|---|
| UI19-01 | Motion Tokens 与共享组件 | PASS：按压/状态/内容时长集中；卡片、按钮、搜索、分段选择、状态面板复用同一体系 |
| UI19-02 | 动画可测试 | PASS：暂停测试时钟可检查动画中/结束后语义；loading 阻止重复提交 |
| UI19-03 | 登录/授权 | PASS：步骤、idle/loading/success/error/disabled、IME 与 safe drawing 覆盖 |
| UI19-04 | 频道列表 | PASS：loading/empty/content/error/retry 与选中反馈覆盖；行动画有限 |
| UI19-05 | 标签筛选 | PASS：focused/clear/empty/error、OR/AND 与选择语义覆盖；业务组合语义未改 |
| UI19-06 | 缓存容量 | PASS：原八档和默认值未改，离散 Slider 与文字同步 |
| UI19-07 | 视频控制层 | PASS：VerticalPager/单播放器不变，loading/pause/error/unsupported 安全区和 48dp 目标覆盖 |
| UI19-08 | edge-to-edge/IME/cutout | PASS：API 36 three-button/gestural 各 2/2，AOSP 非零 hole-cutout 1/1；登录提交和底部操作避开系统区域 |
| UI19-09 | 三键导航 | PASS：navigation mode 0、冷启动、实际点击 AOSP 三键返回按钮后根返回到 Launcher |
| UI19-10 | 手势导航/预测返回路径 | PASS：navigation mode 2、边缘返回到 Launcher；根页面无 `BackHandler` |
| UI19-11 | 横屏/大字体 | PASS：2400×1080 rotation 90 resumed；font scale 1.3 可滚动且操作可达 |
| UI19-12 | Compose emulator suite | PASS：API 36 AOSP x86_64 95/95 |
| UI19-13 | 视觉截图 | PASS：六张截图人工核查无裁切、重叠、闪烁、文字不可读或背景过强 |
| UI19-14 | iQOO 12 / OriginOS 6 / Android 16 | 尚未验证：用户设备本阶段不可用，未连接、安装、启动或运行测试 |

Stage 19 emulator-only 命令：

    .\scripts\run-emulator-compose-tests.ps1 -Serial <x86_64-emulator-serial>
    .\scripts\run-stage19-system-ui-qa.ps1 -Serial <x86_64-emulator-serial>
    .\scripts\run-stage19-visual-qa.ps1 -Serial <x86_64-emulator-serial>

`run-stage19-system-ui-qa.ps1` 会分别切换 three-button/gestural，验证冷启动和根返回，并补充横屏、大字体证据，结束时恢复 emulator 初始配置。它拒绝非 emulator、非 API 36 或非 x86_64 serial，不得用于物理设备。

## 10. 每阶段命令基线

当前已存在 Gradle Wrapper；阶段 2 的主机基线必须在 Android Studio JBR 下使用 `--rerun-tasks --no-daemon --console=plain`：

    $env:JAVA_HOME = 'E:\Android Studio\jbr'
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    .\gradlew.bat lint --rerun-tasks --no-daemon --console=plain
    .\gradlew.bat assembleDebug --rerun-tasks --no-daemon --console=plain

阶段 12F benchmark 解析/分位数/脱敏脚本测试：

    .\scripts\tests\SwipeFirstFrameBenchmark.Tests.ps1

阶段 13A RANDOM 正常/快速协议（`-Serial` 必填；报告必须确认 `order=RANDOM`）：

    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -Direction Forward -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 10 -PerSwipeTimeoutSeconds 12 -Mode Fast -Direction Reverse -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 10 -PerSwipeTimeoutSeconds 12 -Mode Fast -Direction Reverse -FastCheckpointEvery 3 -SkipBuild

13A 报告写入 `build/reports/stage13a`。`-FastCheckpointEvery 0` 是默认连续 Fast；非零值只在显式补验时分批等待可见项终局，总手势数不变，报告必须披露实际批次。脚本不得清应用数据/缓存、退出账号或修改网络/VPN；样本不足、字段不完整或无法证明 RANDOM 时不得生成 PASS。

阶段 13B 历史候选使用相同 Normal Forward 协议，并在报告中额外要求 promotion attempt/matched/terminal、活动请求复用、提前取消和 owner handoff 分位数：

    .\scripts\tests\SwipeFirstFrameBenchmark.Tests.ps1
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -Direction Forward -ReportStage stage13b -SkipBuild

13B 的历史报告保留在 `build/reports/stage13b`。有效失败证据是 `random-swipe-first-frame-normal-forward-20260801-131509.md`；生产候选已回滚为默认关闭。若未来重新实验，必须先得到用户对新阶段的明确授权，并在允许唯一下一条预加载的可比网络条件下重新完成全部 Proof，不能直接翻转生产常量。

阶段 13C 继续复用同一 runner，但当前报告写入 `build/reports/stage13c`，并额外报告随机边界 PlaybackPlan 原子晋升率：

    .\scripts\tests\SwipeFirstFrameBenchmark.Tests.ps1
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -Direction Forward -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -Direction Reverse -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 10 -PerSwipeTimeoutSeconds 12 -Mode Fast -Direction Reverse -SkipBuild

若当前筛选不能安全到达小队列随机轮次边界，边界真机项写“尚未验证”，不得清数据、改用户筛选、退出账号或修改网络/VPN来制造样本。

阶段 13D 使用互斥构建值，并要求报告同时给出 startup miss 分类、first-byte 拆分、覆盖、实际请求/完成的额外字节和调度计数：

    .\scripts\tests\SwipeFirstFrameBenchmark.Tests.ps1
    .\gradlew.bat assembleDebug --no-daemon --console=plain
    .\gradlew.bat assembleDebug -PcvfStartupRangeCandidate=TAIL_64 --no-daemon --console=plain
    .\gradlew.bat assembleDebug -PcvfStartupRangeCandidate=TAIL_128 --no-daemon --console=plain
    .\gradlew.bat assembleDebug -PcvfStartupRangeCandidate=HEAD_512_WIFI --no-daemon --console=plain
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -Direction Reverse -ReportStage stage13d -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 10 -PerSwipeTimeoutSeconds 12 -Mode Fast -Direction Reverse -FastCheckpointEvery 3 -ReportStage stage13d -SkipBuild

TAIL_64/TAIL_128 只有在 BASELINE 真实出现 tail startup miss 后才可逐一构建；本阶段没有满足此前提。任一候选失败后必须不带实验属性重新构建 BASELINE 并重跑测试。不得同时启用多个候选，也不得清缓存、改网络/VPN/质量或提高超时制造收益。

阶段 13E 主机与 RANDOM 安全样本协议：

    .\gradlew.bat :telegram:testDebugUnitTest --no-daemon --console=plain
    .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
    .\gradlew.bat :player:testDebugUnitTest --no-daemon --console=plain
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    .\gradlew.bat lint --rerun-tasks --no-daemon --console=plain
    .\gradlew.bat assembleDebug --no-daemon --console=plain
    .\scripts\tests\SwipeFirstFrameBenchmark.Tests.ps1
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -Direction Forward -ReportStage stage13e -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 10 -PerSwipeTimeoutSeconds 12 -Mode Fast -Direction Reverse -ReportStage stage13e -SkipBuild

13E 报告必须包含透明恢复尝试/outcome、FIRST_FRAME、FAILED、rebuffer 与 crash；正常已准备项不得因 refresh 增加首帧，快速连续滑动不得产生重复 getMessage 风暴。不得清 TDLib 数据库、清缓存、退出账号或改网络/质量来制造冷引用；没有自然冷引用时写“尚未验证”。

阶段 13F 最终默认配置与报告协议：

    $env:JAVA_HOME = 'E:\Android Studio\jbr'
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    .\gradlew.bat lint --rerun-tasks --no-daemon --console=plain
    .\gradlew.bat assembleDebug --rerun-tasks --no-daemon --console=plain
    .\gradlew.bat :app:compileInstrumentationKotlin --no-daemon --console=plain
    .\gradlew.bat :app:testInstrumentationUnitTest --tests "com.qixuan.channelvideoflow.feature.auth.LoginScreenTest" --tests "com.qixuan.channelvideoflow.feature.channels.ChannelSelectionScreenTest" --tests "com.qixuan.channelvideoflow.test.ComposeSmokeTest" --no-daemon --console=plain
    .\gradlew.bat :app:testInstrumentationUnitTest --tests "com.qixuan.channelvideoflow.feature.settings.CacheSettingsScreenTest" --no-daemon --console=plain
    .\scripts\tests\SwipeFirstFrameBenchmark.Tests.ps1
    .\scripts\run-emulator-compose-tests.ps1 -Serial <x86_64-emulator-serial>
    .\scripts\run-vivo-launch-smoke.ps1 -Serial <current-physical-serial>
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -Direction Forward -ReportStage stage13f -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 10 -PerSwipeTimeoutSeconds 12 -Mode Fast -Direction Reverse -ReportStage stage13f -SkipBuild

13F 至少需要两轮 Normal 12 次和一轮 Fast 10 次，报告必须证明 `order=RANDOM`、样本完整、FAILED/rebuffer/crash 为 0。若实验 Pager 动画，任一候选必须单独运行 Compose/实体机协议；settle 收益不足或 jank 增加时立即恢复默认。不得清用户数据/缓存、退出账号、改网络/VPN/质量、扩大预加载或移除内容保护来制造结果。不同媒体/网络条件只能作方向观察，不能描述为严格因果 A/B。

实体机正常与快速协议（`-Serial` 必填，不得硬编码设备；默认不清数据/缓存、不改网络/VPN）：

    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 12 -PerSwipeTimeoutSeconds 12 -Mode Normal -SkipBuild
    .\scripts\run-swipe-first-frame-benchmark.ps1 -Serial <physical-device-serial> -SwipeCount 10 -PerSwipeTimeoutSeconds 12 -Mode Fast -SkipBuild

执行前由人工安全进入播放页；脚本只能在确认固定播放语义后继续。报告位于 `build/reports/stage12f`，不得把 UID traffic 的“尚未验证”改成 0，也不得为制造冷样本清用户缓存。

Compose Path B 连接 emulator/真机时：

    E:\AndroidStudio2.0\platform-tools\adb.exe devices
    .\gradlew.bat :app:compileInstrumentationKotlin --no-daemon --console=plain
    .\gradlew.bat :app:testInstrumentationUnitTest --tests "com.qixuan.channelvideoflow.feature.auth.LoginScreenTest" --tests "com.qixuan.channelvideoflow.feature.channels.ChannelSelectionScreenTest" --tests "com.qixuan.channelvideoflow.test.ComposeSmokeTest" --no-daemon --console=plain
    .\gradlew.bat :app:testInstrumentationUnitTest --tests "com.qixuan.channelvideoflow.feature.settings.CacheSettingsScreenTest" --no-daemon --console=plain
    .\scripts\run-emulator-compose-tests.ps1 -Serial <x86_64-emulator-serial>
    .\scripts\run-vivo-launch-smoke.ps1 -Serial <current-physical-serial>

先用 SDK Manager 安装 `system-images;android-36;default;x86_64`，创建 `CVF_AOSP_API36_X86_64`，并只在该 AVD 上运行 Compose UI instrumentation。不得用 ARM64 AVD 替代，也不得在 x86_64 emulator 跑 TDLib native smoke 后把 ABI 不匹配记为产品失败。

记录命令、退出码、首个失败根因和验证层级。host 退出码 `0` 不能改写为真实 Telegram 人工验收通过。

## 10.1 Stage 23 本次验证边界

- 生产扫描路径静态边界：`telegram/src/main` 中没有 `GetChatHistory`/`getChatHistory`；唯一初始索引入口为 `SearchChatMessages` 视频过滤搜索；没有扫描级 `DownloadFile`。
- 主机确定性模型：固定 seed 的 5/5 模型通过；五档密度最终键集合与旧全历史参考完全相同，1% 模型请求页由理论 10,000 降为 100。client/repository/model 定向 60/60、ViewModel 7/7 通过；新增对齐 recent 场景把请求/事务页 6→4，键集合不变。
- 全量主机 Proof：JUnit XML 1,081/1,081，`test`、`lint`、`assembleDebug` 均以 `--rerun-tasks` 退出码 0；最终 release APK 构建通过。
- Room v5 migration/DAO 与 `EXPLAIN QUERY PLAN`：明确指定 `emulator-5580` 的 API 36 AOSP x86_64 AVD，15/15 通过。
- Compose Path B：instrumentation 编译通过、Robolectric-Compose 定向 28/28、API 36 AOSP x86_64 emulator 最终代码 `OK (99 tests)`；另有 360dp 与 320dp + 1.35 字号的三张最终扫描统计截图，关键处理量、页数、唯一索引和完成频道均可见。
- APK 静态边界：debug/release 权限都恰好为 INTERNET、ACCESS_NETWORK_STATE；只含 `arm64-v8a` 既有 native 白名单；instrumentation target/test APK 均无 `.so`。
- 用户明确禁止连接/安装/启动任何实体机，因此 Vivo/iQOO 12/ARM64 install+launch、实体机 instrumentation、真实账号和真实网络 benchmark 全部为**尚未验证**；实体机缺席不阻止 Stage 23 主机/emulator 范围收口。
- 不得执行 `run-vivo-launch-smoke.ps1`、`installDebug` 到实体机、实体机 `connectedDebugAndroidTest` 或 TDLib ARM64 native smoke。

## 11. 第一版完成门槛

第一版只有在以下条件全部满足时才可称为完成：

1. 要求的 JVM 和 Compose Path B 四项 Proof 通过。
2. test、lint、assembleDebug 全部新鲜通过。
3. 目标真机 installDebug 成功。
4. 真实 Telegram 人工清单逐项记录通过/失败/尚未验证。
5. 缓存、权限、备份、日志和内容保护安全检查通过。
6. 没有通过禁用测试、宽泛 suppress、假数据或删除功能隐藏失败。
