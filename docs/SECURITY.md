# VELORA（曜流）安全设计

> Stage 25 候选补充：双池仍只有 ACTIVE 可请求音频焦点；C2 的 EGL 输出只用于可保存内容，受保护内容使用 C1。窗口保护同时覆盖当前 pager 内容与尚未退役的播放绑定，交接前同步设置安全标志。数据库加密作为独立的默认关闭候选：新空库生成 Keystore 封装密钥；旧库无密钥返回迁移必需；密钥/密文损坏失败关闭，不自动迁移、删除旧库或回退空密钥。Room 元数据尚未加密，不能把 TDLib 加密候选说成全库加密。验证边界见 [Stage 25 结果](STAGE25_OPTIMIZATION_RESULTS.md)。

Stage 25 收尾仅操作 `emulator-5580 / CVF_STAGE25_API36_X86_64`，布局脚本校验完整 AVD 身份和 API 36 / x86_64；没有连接、探测、安装或操作实体手机。本次重新核验 release 的两项权限、备份禁用、native 白名单、凭证零命中与生产默认开关，结果和 APK 哈希见 [Stage 25 产物](STAGE25_ARTIFACTS.md)。R8 和 TDLib 加密 APK 只构建、不安装；静态检查不证明真实 Keystore、账号恢复或 16KiB ARM64 运行时兼容。

文档版本：2.3
日期：2026-09-02
状态：Stage 24 已实现用户自行配置与 Android Keystore 加密存储；非 debug BuildConfig 空值、正式签名、APK 本机凭证零命中、权限/备份/ABI 审计已通过。仓库所有者报告当前版本真机正常使用通过；本次正式签名 APK 的设备安装和真实 Keystore instrumentation 未由 Codex 重复验证

> 视频字节仍只存在于 `cacheDir/tdlib/files`。应用不建立 Media3 第二份缓存；官方 `GetStorageStatistics` 精确统计 `FileTypeVideo`，默认 500MB 配额通过未 pin 文件的 LRU/TDLib 删除能力执行。内容导出仍不支持；阶段 8 已由 app 层 `WindowSecurityController` 按当前消息的 `canBeSaved` 设置和恢复 `FLAG_SECURE`。

## 安全问题报告

请不要在公开 Issue、Discussion 或 Pull Request 中提交 Telegram API ID/API Hash、手机号、验证码、两步验证密码、会话文件、数据库、私有媒体、设备序列号、私有路径或完整日志。

优先使用 GitHub 仓库 **Security → Report a vulnerability** 的私密报告入口（启用后可见）。如果入口不可用，请只创建一个不含漏洞细节和敏感数据的 Issue，请求维护者建立私下联系渠道。报告中可包含受影响版本、可复现的最小步骤、预期影响和已经脱敏的诊断信息。

项目不会要求报告者提供真实 Telegram 凭证或真实账号测试数据。

## 1. 安全目标

- 保护 Telegram API Hash、授权验证码、两步验证密码、TDLib 会话和数据库材料。
- 不扩大当前 Telegram 账号的访问权限。
- 不绕过频道内容保护或导出限制。
- 将视频字节限制在应用内部缓存并实施容量上限。
- 使日志、备份、测试和 Git 历史不包含敏感信息。
- 在个人 debug APK 可能被反编译的现实下明确剩余风险。

## 2. 威胁边界

本设计防护：

- 凭证被误提交到 Git。
- 凭证、验证码或密码被日志记录。
- Android 自动备份/设备迁移复制会话和敏感数据。
- 视频被写入相册、公共目录或第二套完整缓存。
- 受保护内容被应用提供保存、分享或截图入口。
- 快速滑动后旧文件请求继续下载并占用空间。
- 退出账号后旧索引和媒体残留。

本设计不能完全防护：

- 已 root、已被恶意调试或系统完整性失守的设备。
- 用户主动反编译自己安装的 APK。
- Telegram 服务、设备固件或第三方键盘自身的安全问题。
- 用户在其他设备或外部摄像机上记录屏幕。

## 3. API 凭证

### 3.1 凭证来源

debug 开发构建的可选回退值只允许存在于根目录 local.properties：

    TELEGRAM_API_ID=<用户本地值>
    TELEGRAM_API_HASH=<用户本地值>

公开发行路径固定为：

- `release`、`instrumentation` 及未来其他非 debug 构建的 BuildConfig API ID/API Hash 强制为空，不读取 `local.properties` 的真实值。
- 使用者首次启动时只在设备 UI 中填写自己的 API ID/API Hash。
- 格式通过后，由 Android Keystore 中不可导出的 AES-256 密钥执行 AES-GCM 加密；密文写入 `noBackupFilesDir/credentials/telegram-api.v1`。
- 存储文件采用带版本的定长明文结构、认证附加数据、随机 GCM IV、4KiB 读取上限和临时文件原子替换；明文字节使用后覆盖。
- 密文或密钥不可读时失败关闭，界面要求重新输入；不会回退到损坏值、日志或公共存储。

开发构建仍具备：

- .gitignore，明确忽略 local.properties。
- secrets.defaults.properties，仅含空值或说明，不含可用共享凭证。
- Gradle 配置，只为 debug 从 `local.properties` 读取并做类型/空值校验；未保存设备端参数时，debug 可用它初始化 TDLib。

不得使用公开共享 api_id/api_hash，不得要求用户在聊天中粘贴 api_hash。

### 3.2 构建与运行时边界

- Gradle 只可将本地值注入 debug BuildConfig，不得输出到 Gradle 日志、异常或生成报告。
- 非 debug BuildConfig 必须为空；公开 APK 的反编译扫描必须同时检查维护者 API ID 和 API Hash 均不存在。
- 缺失值时显示用户自行配置页面；格式或存储失败只列固定键名/固定错误类别，不输出值。
- 提交前检查 git diff、git diff --cached 和 git check-ignore local.properties。
- 构建目录不加入版本控制。

### 3.3 剩余风险

公开源码和正式 APK 不包含维护者的真实 API 参数。使用者填写的参数在运行时必须以明文传给 TDLib，因此已 root、恶意调试、键盘/系统受控或进程内存被读取的设备仍可能泄露；Android Keystore 主要保护静态存储和普通文件提取，不承诺抵御已失守设备。任何人仍不得公开分发预先写入自己 API 参数的 APK。

## 4. 授权输入和会话

- 手机号只在 UI 和授权请求所需内存中存在；当前生产 logger 不记录手机号，未来若加入掩码日志也只能记录不可逆掩码。
- 验证码和两步验证密码使用不持久化 Compose 状态，不写 SavedStateHandle、DataStore、Room、文件或日志。
- 授权成功、取消、退出、关闭或不可恢复错误后覆盖/释放输入引用。
- 不在异常中附带原始 TDLib 授权对象。
- 当前 TDLib database root 使用 `Context.noBackupFilesDir`，files root 使用 `Context.cacheDir`；两者均为应用私有目录，不硬编码公共路径。
- TDLib 数据库密钥如启用，只在进程内生成/获取，不记录、不备份、不进入 BuildConfig。
- API Hash 只在配置 UI、加密/解密和 TDLib 初始化所需内存中短暂存在；提交配置后立即从 ViewModel/Compose 状态清除。

## 5. Android 备份与迁移

第一版选择最保守策略：整个应用不参加备份。

AndroidManifest 必须显式设置 android:allowBackup=false。由于 Android 12+ 某些厂商对设备到设备迁移的行为可能不同，还必须同时提供：

- android:dataExtractionRules 指向 res/xml/data_extraction_rules.xml。
- android:fullBackupContent 指向兼容 Android 11 及以下的 res/xml/backup_rules.xml。
- 云备份和 device-transfer 对 file、database、sharedpref、root 及 device_* 域全部排除。

至少排除：

- TDLib 数据库和授权会话。
- 本地密钥。
- Room 数据库及日志文件。
- DataStore。
- 媒体缓存和缩略图缓存。

当前 manifest 已设置 `android:allowBackup=false`，并引用 `data_extraction_rules.xml` 与 `backup_rules.xml`；阶段 4 再次静态确认 merged manifest 只有 INTERNET 与 ACCESS_NETWORK_STATE 两项权限。debug APK 已在真机安装，但安装成功不替代其余数据清理验收。

## 6. 存储

- 当前 TDLib database root 放在 app-private `noBackupFilesDir`，files root 放在 app-private `cacheDir`。
- 未来临时视频也只能放在内部 `cacheDir` 下的专用 TDLib 媒体目录。
- 不使用 externalFilesDir、externalCacheDir、MediaStore、Downloads、DCIM 或 Movies。
- 不申请 READ_EXTERNAL_STORAGE、WRITE_EXTERNAL_STORAGE、MANAGE_EXTERNAL_STORAGE。
- 文件名使用 TDLib/内部不含说明文字或标签的标识，避免路径泄露内容。
- 打开文件前验证规范化路径仍位于允许的应用私有根目录，防止路径混淆。
- 不向 FileProvider 暴露媒体缓存，不生成可供其他应用读取的 content URI。

Android 可能在存储不足时删除 cacheDir 文件。DataSource 每次读取前验证文件和连续区间，文件消失时返回可恢复错误，不假设缓存永久存在。

## 7. 数据最小化

阶段 4 的 Room 只保存频道元数据，以及建立索引/筛选所需的 `chatId + messageId`、TDLib file 标识、视频属性、caption、标签、删除标记、同步游标和脱敏扫描诊断。Room 不保存媒体字节、完整 TDLib 对象或授权材料。

不得保存：

- API Hash、验证码、密码、数据库密钥。
- TDLib 授权会话对象。
- 完整序列化 TDLib update 或 message 对象。
- 媒体字节。
- 不受限制的调试消息正文副本。

阶段 4 会为离线索引/标签更新保存 caption；它属于真实消息内容，只存在于应用私有 Room，默认不写日志，并在退出账号时随账号索引删除。备份规则完全排除该数据库。

## 8. 网络与协议

- 只通过官方 TDLib 与 Telegram 通信。
- 不实现 MTProto，不添加代理抓取、机器人 API、Telethon、Pyrogram 或第三方 Telegram 网关。
- 不将消息链接当成媒体 URL，不尝试推导 CDN/文件直链。
- 不添加证书忽略、明文流量白名单或自定义不安全 TLS。
- Android manifest 默认 cleartextTrafficPermitted=false；如 TDLib native 不走 Android Network Security Config，该设置仍保护应用其他网络路径。
- 应用不发送分析、崩溃上报或遥测数据。

## 9. 内容保护

- message.canBeSaved=false 或 messageProperties 指示保护时，领域模型设置 isProtected=true。
- UI 不显示保存、导出、转发本地文件或分享入口。
- 不复制到公共存储，不通过其他账号/API 重新获取。
- 播放受保护视频时 WindowSecurityController 增加 FLAG_SECURE；绑定普通视频或离开 Feed 后立即清除该 flag。
- FLAG_SECURE 是应用内防护，不宣称能阻止所有外部录制方式。
- 受保护字节仍使用相同内部缓存和 LRU；不会获得更宽松路径。

## 10. 日志与调试

允许的 Debug 字段：

- 授权状态名称。
- TDLib 请求类型和脱敏关联 ID。
- chatId、messageId、fileId。
- 播放器状态。
- offset/limit、缓存字节数和命中状态。
- 不含敏感文本的错误码/错误类别。

禁止：

- api_hash、验证码、两步验证密码、完整手机号。
- 数据库密钥、授权 token、会话路径内容。
- 完整 TDLib 对象和可能包含敏感输入的 exception.message。
- 媒体原始字节。
- 默认情况下的消息正文。

调试正文开关默认关闭，只在 debug 构建可用；即使打开，也不得记录凭证和媒体。Release 编译时移除或禁用详细日志。

## 11. 权限

允许：

- android.permission.INTERNET：TDLib 网络通信。
- android.permission.ACCESS_NETWORK_STATE：监听网络类型和可用性。

禁止联系人、短信、通话记录、手机状态、麦克风、摄像头、位置、通知和存储权限。若未来功能需要新增权限，必须单独阶段说明用途、数据流和替代方案，并取得用户明确同意。

## 12. 依赖与供应链

- TDLib 只从 `https://github.com/tdlib/td.git` 获取，当前固定 `1.8.66` / `022d60202e446ad1287b9fb68e687c8a0760788b`。
- Android/Jetpack 依赖只从 Google Maven、Maven Central 等官方发布渠道获取。
- 所有版本集中到 libs.versions.toml，并只选稳定版本。
- 禁止来源不明的预编译 .aar/.jar/.so。
- 当前 native 产物记录 TDLib/OpenSSL SHA、`arm64-v8a`、NDK `23.2.8568313`、CMake `3.22.1`、构建命令和文件哈希；见 `docs/TDLIB_BUILD.md` 与 `telegram/tdlib/TDLIB_PROVENANCE.md`。
- 当前 debug APK 的 native 白名单还包含两个可追溯的官方 AndroidX 传递文件：`androidx.graphics:graphics-path:1.0.1` 的 `libandroidx.graphics.path.so`，以及 `androidx.datastore:datastore-core-android:1.2.1` 的 `libdatastore_shared_counter.so`；后者 APK/AAR 的 ARM64 SHA-256 均为 `deed4546c8dafad0e68ea2c25e4c0a62ca97343614ae386b7ed2af6abb7fa999`。
- 不在 CI 或脚本中静默下载可执行文件；阶段内需说明来源。
- 每次依赖变更检查许可证和传递依赖用途。

## 13. 缓存清理安全

配额为 500MB 默认值，可选 200MB、500MB、1GB、2GB、5GB、10GB、15GB、20GB。界面显示 TDLib 精确视频字节；TDLib 尚未 Ready 时的私有目录物理扫描必须明确标记为启动估算。

自动 LRU：

1. 读取当前和下一条 owner pin。
2. 只选择未 pin 且 TDLib 标记可删除的媒体。
3. 再次验证目标位于允许私有目录。
4. 在 `TelegramFileManager` 的删除预留边界内再次确认 owner/pin 后调用官方 `deleteFile`；有任何 pin 时禁止对全局 `OptimizeStorage`。
5. 更新缓存统计；失败不删除 Room 索引。

一键清空：

1. 禁止新预加载。
2. 取消 NEXT_PRELOAD。
3. 暂停播放器并关闭当前 DataSource。
4. 删除所有可删除媒体缓存。
5. 保留登录、频道/视频/标签 Room 索引和 DataStore 设置；只允许清除 `media_cache_entries` LRU 元数据。
6. 向用户报告成功、部分失败或失败，不虚报释放空间。

## 14. 退出账号安全

退出不是“一键清空缓存”。阶段 3 已在 TDLib 登出事件上清空单账号频道 Room 表并使旧刷新 generation 失效；完整产品仍必须在停止所有消费者后调用 TDLib logout，等待状态机关闭，然后清除本账号其余 Room 索引、TDLib 数据库/会话、媒体缓存和内存授权状态。删除失败时显示明确错误并在下次启动阻止旧会话被误当成新账号数据。

## 15. 安全测试门槛

- local.properties 被 Git 忽略且不在 index。
- secrets.defaults.properties 无可用凭证。
- release BuildConfig 的两个凭证字段均为空，APK 扫描不含维护者本机值。
- Keystore 密文篡改、损坏、格式错误和写入失败均失败关闭；显式重新输入后可重建密钥与密文。
- 源码和资源搜索不含真实 api_hash、验证码和密码。
- merged manifest 只有两项允许权限。
- merged manifest 禁用备份并引用两套排除规则。
- TDLib、Room、DataStore 和媒体目录均在应用私有路径。
- 受保护页面设置/恢复 FLAG_SECURE。
- CacheManager 不删除当前/下一条 pin。
- 快速滑动释放旧 owner。
- Release 日志不包含详细调试路径。
- debug APK 反编译抽查不包含仓库提交的真实凭证。

## 16. 事件响应

若发现凭证或敏感数据误提交：

1. 立即停止继续提交或推送。
2. 告知仓库所有者具体文件和 Git 状态，不在聊天复述秘密值。
3. 由所有者在 my.telegram.org 采取适用的凭证处置。
4. 经明确授权后清理 Git 历史；不得自行执行破坏性重写。
5. 修复忽略、日志或构建规则并增加回归测试。

## 17. Stage 18 内部媒体资源安全

- HLS manifest 只允许受控标签与 Telegram 内部资源；拒绝 HTTP(S)、file/content、未知 scheme、外部 host、路径穿越、超限和递归 playlist。
- Media3 只看到短生命周期 opaque token；token 绑定账号 generation 和 owner，不接受用户提供 fileId，session/退出账号会立即撤销。
- manifest 最大 256 KiB、最多 4096 行、单行最多 2048 字符；解析异常 fail closed 并单次回退 direct MP4。
- 当前和唯一下一条继续使用缓存 owner token。新增 next 网络流量按 512 KiB chunk 记账，绝对上限 10 MiB；缓存命中字节不算新增网络但仍受所有权保护。
- 指标只有状态、脱敏 key、buffer/throughput/TTFB、range、预算和计数；不记录正文、真实/内部 URL、token、路径、凭证或媒体内容。
- 没有新增权限、公共存储、本地服务器、HTTP proxy、Media3 disk cache、第二播放器或第二播放内核。

## 18. Stage 19 UI 与窗口安全

- Manifest 仍只允许 `INTERNET` 和 `ACCESS_NETWORK_STATE`；只为 MainActivity 明确 `adjustResize`，没有通过主题或 manifest 退出 Android 16 edge-to-edge。
- 敏感登录输入没有逐字动画、动态日志或持久化；loading/error/success 只传播状态名和公开语义。
- 没有 WebView、HTML/CSS/JavaScript、远程运行时代码、在线素材、付费内容或许可不明源码；所有视觉机制使用稳定 Compose API 独立实现。
- 没有公共存储写入、导出/分享入口或新缓存。TDLib 私有数据、Room、DataStore、Media3、owner token、`FLAG_SECURE` 和备份排除规则未改。
- 不设置全屏 `systemGestureExclusion`。视频交互层通过 Insets 避开系统手势/挖孔；应用根页面不拦截系统返回。
- 背景是静态低透明度缓存绘制；没有无限背景动画、全屏 blur、Shader、粒子或持续边框动画，视频解码优先级不受影响。

## 19. 官方依据

- [Android 应用专属存储](https://developer.android.com/training/data-storage/app-specific)
- [Android 自动备份](https://developer.android.com/identity/data/autobackup)
- [TDLib 入门](https://core.telegram.org/tdlib/getting-started)
- [TDLib message.canBeSaved](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1message.html)
- [TDLib 官方源码](https://github.com/tdlib/td)
