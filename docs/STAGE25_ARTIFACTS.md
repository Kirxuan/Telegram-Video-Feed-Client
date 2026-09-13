# Stage 25 收尾构建产物

日期：2026-09-09。来源为 `main / adf40c2` 上保留既有候选的未提交工作树；仅靠该 HEAD 不能复现这些未提交改动。所有文件均为本机产物，`build/` 被 Git 忽略，没有提交、推送或发布。

正式版本仍为 Stage 24 / VELORA 1.1.0。四种播放候选、R8 和 TDLib 加密候选为 benchmark/debug 签名，不能覆盖正式签名安装；默认 release 是未签名 APK。加密候选未安装，不可用于已有 TDLib 数据库。

| 产物 | 字节 | SHA-256 | 边界 |
|---|---:|---|---|
| [默认 release](../app/build/outputs/apk/release/app-release-unsigned.apk) | 待本轮 release 重建 | 待本轮 release 重建 | 未签名；默认 C1 双播放器 |
| [Baseline](../build/reports/stage25-experiments/apks/velora-baseline.apk) | 40,849,255 | `928b8a4c2cad3893df42c5714c0b99c7cceaec55ea5637ad466042e32441a455` | benchmark/debug 签名；单播放器 |
| [SampleQueue](../build/reports/stage25-experiments/apks/velora-samplequeue.apk) | 40,849,255 | `1cdab306e1d91cc4ad46cd380a444242c57359eaa2957ea5a177131386d86680` | benchmark/debug 签名；仅 SampleQueue |
| [PoolC1](../build/reports/stage25-experiments/apks/velora-poolc1.apk) | 40,849,255 | `0878405d9d3a3d883a3fdbadaf267211c6dafcb958f44b861777e6276c108533` | benchmark/debug 签名；C1 |
| [PoolC2](../build/reports/stage25-experiments/apks/velora-poolc2.apk) | 40,849,255 | `8b0a804691252fec68bd75cbd7582acc49186621ace9356927a8c63a2fd3a863` | benchmark/debug 签名；C2 |
| [R8 对照](../build/reports/stage25i/velora-unminified.apk) | 40,849,255 | `928b8a4c2cad3893df42c5714c0b99c7cceaec55ea5637ad466042e32441a455` | benchmark/debug 签名；未压缩 |
| [R8 候选](../build/reports/stage25i/velora-r8-candidate.apk) | 30,471,752 | `8986e4b22fdcc4fe40d3933a414ed8d5abfb5e9d3c50c21471f683aebc026c7b` | benchmark/debug 签名；R8/资源压缩 |
| [TDLib 加密候选](../build/reports/stage25-experiments/apks/velora-database-encryption.apk) | 40,849,255 | `3a77576d53741b87295dcf2e7a0ede9fed74a493f0c830eebc250f95838001a4` | benchmark/debug 签名；只支持新空 TDLib 库 |
| [Compose target](../app/build/outputs/apk/instrumentation/app-instrumentation.apk) | 19,246,938 | `94653b2f2d3c762b8454cb1bda955e660a6aff0cd8b26d35cced72e8f4f56323` | debug 签名；测试专用，无 native |
| [Compose tests](../app/build/outputs/apk/androidTest/instrumentation/app-instrumentation-androidTest.apk) | 1,579,608 | `75c4717e3f1d43fc4e79601f77e41c6e30675a9614feb3c319ece449a6773a95` | debug 签名；共享 UI 测试 |

R8 对照 40,849,255 字节，候选 30,471,752 字节，减少 10,377,503 字节（25.40%）。这是体积收益；JNI、账号恢复、播放等真实设备运行时兼容性尚未验证。

默认 release 的权限仅为 INTERNET 和 ACCESS_NETWORK_STATE，备份禁用，本机凭证反向扫描命中 0；三个 ARM64 native 文件的 ELF PT_LOAD、APK 条目 16KiB 对齐和 zipalign 检查通过。16KiB ARM64 运行时尚未验证。

UI 验证仅使用上表 Compose target/tests，目标为 `emulator-5580 / CVF_STAGE25_API36_X86_64`（API 36 AOSP x86_64）：常规 101/101，三种尺寸各 40/40。没有将任何 benchmark、R8 或加密候选安装到设备。实体 iQOO 12 未连接、未探测、未安装、未操作；真机性能、Codec、PSS、图形缓冲、能耗和真实 Telegram 账号路径尚未验证。

生产默认：双播放器 C1；SampleQueue=false、Pool=C1、C2 为独立候选、Owner Promotion=false、R8/资源压缩=false、TDLib 加密候选=false、release 详细诊断=false。移动数据不预热 STANDBY。

证据位于 `build/reports/stage25-experiments/`：`closeout-final-proof.log`、`closeout-host-counts.json`、`compose-final.log`、`layouts/*.log`、`closeout-*-build.log`、`closeout-artifacts.json`、`native-pages.json`、`closeout-zipalign.log`；release 边界报告位于 `build/reports/stage25k/release-boundaries/`。测试/运行边界详见 [优化结果](STAGE25_OPTIMIZATION_RESULTS.md)和[后续设备验收](STAGE25_DEVICE_ACCEPTANCE.md)。
