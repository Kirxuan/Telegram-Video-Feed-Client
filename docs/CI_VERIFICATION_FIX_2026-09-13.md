# 1.2 发布后的 CI 依赖校验修复

## 工作合同

- Outcome：GitHub Actions 的 Android host proof 在严格依赖校验下通过配置阶段并运行完整主机检查。
- Scope：`gradle/verification-metadata.xml`、`.github/workflows/android.yml`、本文。
- Boundary：不改变应用依赖版本、生产代码、权限、签名、已发布的 1.2 APK 或版本标签；不关闭严格校验，不添加宽泛信任规则。
- Failure states：缺失校验项、哈希不匹配或后续构建失败必须保持 CI 失败；依赖校验报告作为诊断制品保留。
- Proof：使用实际 Gradle 严格校验复现三个失败条目，补充登记后重跑；随后以 GitHub Actions 完整流程验证。

## 根因

发布提交 `cbac65f` 的首次云端构建在根项目 classpath 配置时失败。日志列出的三个元数据文件没有登记在版本库校验清单中，而非已有登记哈希与下载内容不匹配：

| 文件 | 官方文件 SHA-256 |
|---|---|
| `com.google.guava:guava-parent:33.3.1-jre` 的 POM | `55441db27e8869dfefe053059bdf478bdc7e95585642bf391f0023345fd56287` |
| `com.google.guava:guava-parent:33.3.1-android` 的 POM（第二轮运行时依赖检查发现） | `6e11986ea7250b51f847157e2dc937f32a306804dfce0007a5e81ddb9b95c579` |
| `org.junit:junit-bom:5.10.2` 的 Gradle module 元数据 | `de23b114b3e4119a8fe6eb17bed5a3852816698bace67071579d6d927ebb080a` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0` 的 POM | `1239e9dbe1397cd5971342956b2511bc3ace7b641842e4372a088dcfa8b9ad55` |

文件从 Maven Central 的 `repo.maven.apache.org/maven2/` 通过 HTTPS 获取。JUnit 与 Coroutines 的文件哈希匹配仓库发布的 `.sha256`；Guava 的 `.sha256` 不存在，其文件匹配发布的 `.sha1`，并确认 `repo1.maven.org` 返回的文件 SHA-256 完全一致，再登记 SHA-256。

本机已有缓存时 `help --dependency-verification=strict` 可以通过，说明已有缓存下的成功不能替代首次解析依赖的覆盖。本次最小复现直接解析这三个官方制品，保持 Gradle 的严格校验机制。

## 验证记录

- 修复前：最小 Gradle 工程 `verifyCiMetadata --dependency-verification=strict` 失败，列出与云端相同的三个制品。
- 修复后：同一工程、同一命令通过，三个制品均被校验。
- 独立空缓存下的完整项目 `help` 也复现了最初三个校验失败（4m41s）；已有缓存的本机成功不能替代此项。
- 第二次云端运行通过根项目配置，在 `:app:checkBenchmarkAarMetadata` 发现缺少 Guava Android 版父 POM。将其加入独立配置的最小复现，确认先失败，登记官方文件 SHA-256 后再验证。
- 第三次运行推进到 kapt 后发现 `guava-parent:33.0.0-jre` 未登记。进一步核对清单中已有依赖对应的父 POM、BOM 和 Gradle module，补齐 Guava 父 POM `33.0.0-jre` / `33.2.1-jre` / `33.4.8-jre`、Coroutines BOM `1.6.4` 与 JUnit BOM `5.9.2` 的 module，共计补充九个元数据文件；不改变依赖版本或现有校验值。
- 最小复现中的各版本使用独立 configuration，避免 Gradle 版本冲突消解使某个待验证版本被替换。错误哈希负向对照仍触发严格校验失败。
- 云端完整流程：修复推送后验证，以 GitHub Actions 对应提交结果为准；本文首次提交时尚未验证。

原始运行：[Android host proof #1](https://github.com/Kirxuan/Telegram-Video-Feed-Client/actions/runs/34740965194)。后续状态以仓库 [Actions](https://github.com/Kirxuan/Telegram-Video-Feed-Client/actions) 中修复提交对应的运行记录为准。
