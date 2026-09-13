# C2 播放入口与双播放器稳定性修复

日期：2026-09-09。基于现有 Stage 25 工作区；本次未提交、推送或发布。

## 阶段合同

- Outcome：修复从标签筛选“浏览全部视频”进入播放器的初始化异常；当前播放与下一条静音备用播放器可交接、取消和释放；提供新的 C2 ARM64 安装包。
- Scope：`player` 模块中的 `VideoPlayerManager`、`ReusablePlayerLifecycle`、`PlaybackPoolCoordinator`、`StandbyVideoSurface`；相应 JVM/Android 回归测试与测试专用 Activity/manifest；本报告。使用工作区已有 `ForwardingLoadControl` 修复与官方 Media3，不建立另一套播放架构。
- Boundary：不更换 Telegram 账户/凭证策略，不新增生产权限，不发布或自动提交。用户要求仅本地自查，本次不连接或操作用户手机。
- Failure states：备用源/引擎准备失败时释放所有权并回到单播放器；离屏 EGL 失败或超时后降为 C1；播放加载、失败、非流式视频提示继续使用现有 UI。
- Proof：默认配置 `test lint assembleDebug`；C2 参数的实际 Media3/EGL 模拟器测试；Robolectric Compose、API 36 AOSP x86_64 Compose UI；新 C2 APK 的签名、配置、权限、备份与 ABI 校验。

## 一、本阶段目标

用户反馈已安装 C2 在标签筛选页点击“浏览全部视频”即闪退。修复确定的本地缺陷并输出可安装修复包，同时检查双播放器交接与资源生命周期。

## 二、实际完成内容

### 已定位的入口异常

原始 `build/reports/stage25-experiments/apks/velora-poolc2.apk` 中的 `PoolLoadControl` 使用 Kotlin `LoadControl by delegate`。这种写法没有转发 Media3 1.10.1 的 Java default 方法。ExoPlayer 构造期间调用 `getBackBufferDurationUs(PlayerId)`，会落入接口默认实现并抛出 `IllegalStateException("getBackBufferDurationUs not implemented")`。

标签页进入 Feed 时，`PlayerView` 的 attach 会首次创建 ExoPlayer，所以该缺陷与用户所述入口一致。已检查旧 APK 字节码并在模拟器复现同一默认方法异常；没有用户手机堆栈，因此不把它表述为真机堆栈已确认。

工作区在本次开始前已有 `ForwardingLoadControl`，但旧 APK 不包含它。本次将其纳入新构建并补充真实 DefaultLoadControl/ExoPlayer 回归测试，覆盖 prepare/stop/release 账本。

### 加固

- 备用准备的请求会话、HLS 注册和保护租约统一处理所有权转移；拒绝或半途失败也释放未转交的资源。
- 备用准备失败关闭本次播放会话的双播放器功能，释放备用引擎和 Surface，当前播放继续。
- 记录部分媒体绑定，prepare 抛异常时仍能清理；清理失败也执行 release 并失效化 token。
- EGL 在其所属线程初始化和释放；取消屏蔽迟到回调；重复 close 幂等；5 秒准备超时后降为 C1；失败后的当前播放会话不反复创建 EGL。
- 以递增 token 序号水位代替累计 token 集合，消除 512 次准备上限，仍拒绝历史 token。
- 终止播放错误清除待准备目标，避免后台继续尝试旧目标。

## 三、修改文件

本轮新增/修改：

- `player/src/main/java/com/qixuan/channelvideoflow/player/VideoPlayerManager.kt`
- `player/src/main/java/com/qixuan/channelvideoflow/player/ReusablePlayerLifecycle.kt`
- `player/src/main/java/com/qixuan/channelvideoflow/player/PlaybackPoolCoordinator.kt`
- `player/src/main/java/com/qixuan/channelvideoflow/player/StandbyVideoSurface.kt`
- `player/src/test/java/com/qixuan/channelvideoflow/player/ReusablePlayerLifecycleTest.kt`
- `player/src/test/java/com/qixuan/channelvideoflow/player/PlaybackPoolCoordinatorTest.kt`
- `player/src/androidTest/java/com/qixuan/channelvideoflow/player/PlaybackPoolEngineTest.kt`
- `player/src/androidTest/java/com/qixuan/channelvideoflow/player/VideoPlayerManagerIntegrationTest.kt`
- `player/src/androidTest/java/com/qixuan/channelvideoflow/player/PlaybackProofActivity.kt`
- `player/src/androidTest/AndroidManifest.xml`
- 本报告。

仓库还有本次开始前即存在的大量 Stage 25 未提交变更，本清单不将它们冒记为本轮新增。

## 四、关键架构决定

最多两个可复用引擎，只有当前引擎可播放/发声。C2 仅对允许保存的内容使用离屏 Surface；受保护内容走 C1，转为当前播放前设置 FLAG_SECURE。下一条仅在当前首帧出现且缓存/网络/设备条件安全时准备。请求仍通过既有有界 DataSource、所有权租约和累计字节预算；默认移动网络关闭预加载。

测试 Activity 与合成视频仅存在于 Android test APK，不进入交付 APK。默认构建仍遵守现有配置，交付 C2 使用显式构建参数。

## 五、执行的命令

环境：`JAVA_HOME=E:\Android Studio\jbr`、`GRADLE_USER_HOME=E:\Telegram Android Developer\.gradle-user-home`。Gradle 串行执行并启用 offline、strict dependency verification、max-workers=1、no-daemon、console=plain、Kotlin in-process。

- `gradlew.bat :player:testDebugUnitTest`
- `gradlew.bat :player:assembleDebugAndroidTest -PcvfPlaybackPoolCandidate=C2`
- 显式指定 `adb -s emulator-5580` 安装 player test APK 并执行 `AndroidJUnitRunner`。
- `gradlew.bat test lint assembleDebug`：BUILD SUCCESSFUL；汇总 1,654 个 JVM/Robolectric 用例，失败/错误/跳过均为 0；lint 0 errors（43 warnings）。
- `gradlew.bat :app:compileInstrumentationKotlin`：BUILD SUCCESSFUL。
- `gradlew.bat :app:testInstrumentationUnitTest --tests ...`（标签、视频、登录、频道、设置、Compose smoke）：BUILD SUCCESSFUL，128 actionable tasks。
- `gradlew.bat :app:assembleInstrumentation :app:assembleInstrumentationAndroidTest`：重新打包当前源码成功，target APK 时间为 2026-09-09 15:14:35。
- `scripts/run-emulator-compose-tests.ps1 -Serial emulator-5580 -SkipBuild`：使用上述重新打包结果，PASS，API 36 AOSP x86_64，101 tests；最终日志为 `fresh-emulator-compose-proof.log`，instrumentation 耗时 63.991 秒。
- `gradlew.bat :app:assembleBenchmark -PcvfSampleQueuePreloadEnabled=false -PcvfPlaybackPoolCandidate=C2`：BUILD SUCCESSFUL，交付 APK 生成。
- `scripts/verify-stage25-release-boundaries.ps1 -ApkPath build/reports/c2-stability/VELORA-C2-fixed-20260909.apk`：PASS。

播放器 Android instrumentation：显式安装测试 APK 到 `emulator-5580` 并执行 `AndroidJUnitRunner`，C2 配置 6/6 通过。

## 六、测试结果

实际 Media3/EGL 模拟器：6/6 通过。包括旧 default-method 异常回归、C1/C2 两引擎循环交接、真实可见 Surface 下当前/备用交接与静音约束、快速准备取消、保护内容、后台/重复释放、备用保护租约失败降级，以及 12 次立即重复关闭 Surface 后回调屏蔽和线程终止。

默认全仓 Proof、Compose 编译/Robolectric/模拟器部分以及 C2 播放器 Proof 均通过。完整 `Proof(Compose)` 中的 Vivo install+launch smoke 尚未验证，故不宣称完整真机门槛已完成。日志保存在 `build/reports/c2-stability/`。

中间失败已保留日志：初期并行 Gradle 导致生成类竞争（改为串行后通过）；把 C2 参数用于默认配置约束测试导致预期不匹配（默认配置重跑通过）；新增测试的 Kotlin 局部 lateinit 反射语法错误（已修正并通过 Android 编译与运行）。

## 七、真机验证结果

尚未验证。用户明确要求手机继续自用、只做本地自查。未执行真机安装、登录、TDLib 网络播放或 Vivo smoke。API 36 AOSP x86_64 仅用于无账户的 Media3/EGL 和 Compose，未运行 TDLib native。

## 八、已知问题

真实手机厂商解码器、OriginOS、Telegram 网络/HLS 及长时间功耗性能尚未验证。模拟器测试不能证明所有手机绝对无 bug，也不构成 C2 相对基线性能提升的真机数据。交付 APK 是 benchmark/debug 签名实验包，适合覆盖安装本地使用；正式 release 默认仍关闭 C2。

## 九、安全检查结果

通过。交付 APK 为 `build/reports/c2-stability/VELORA-C2-fixed-20260909.apk`，40,849,255 bytes，SHA-256：`3dd5b4c215236e550e30c1513850a560ecc228a05e5985d8cf58f458cf72b05e`。签名证书 SHA-256 为 `bdc32abdc6ac9fd56bb7d4226b920eecfc87fd71cc21ebfa669e56d5466dabec`，与旧 C2 一致；zipalign 通过；BuildConfig 的 C2 candidate 为 `C2`，非 debug API ID/API Hash 为空。仅含 `INTERNET`、`ACCESS_NETWORK_STATE`；备份与设备迁移排除通过；native 仅 `arm64-v8a` 的 `libandroidx.graphics.path.so`、`libdatastore_shared_counter.so`、`libtdjni.so`。测试使用内部缓存的合成视频，无真实账号、API Hash、验证码或密码。

## 十、建议的下一阶段

将 `VELORA-C2-fixed-20260909.apk` 传到手机后打开，选择更新/覆盖安装。它与旧 C2 包名、签名和版本码一致，符合覆盖安装条件；不要先卸载，以保留当前应用数据。实际手机更新安装尚未验证。当前阶段不自动扩展新功能；后续若有实际异常，以对应错误证据继续修复。
