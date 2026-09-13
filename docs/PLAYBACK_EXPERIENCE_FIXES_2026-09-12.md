# 播放体验修复：简介折叠栏、进度条位置、播放期间不熄屏（2026-09-12，本地验证）

## 工作合同

- Outcome：竖屏播放时简介默认只占底部一小栏，不再遮挡画面；进度条上移并离开系统手势区；播放期间不因系统自动熄屏而中断，暂停或退出后恢复原有熄屏策略。
- Scope：app 的 `VideoPlaybackScreen.kt`、`WindowScreenOnController.kt`（新增）、`strings.xml`；测试 `VideoPlaybackScreenTest.kt`、`MetadataSummaryContractTest.kt`（新增）、`WindowScreenOnControllerTest.kt`（新增）。
- Boundary：不改播放内核、TDLib 所有权、缓冲/预算策略与 Stage 28 的下载窗口规则；不改详情底部弹层的内容与入口；不操作手机、不执行 adb、不清账号或缓存、不提交、不发布。
- Failure states：简介为空时不渲染预览行；无 caption/tags 溢出时不显示「展开」按钮；非 CONTENT 阶段或暂停时不持有屏幕常亮标志；离线、非流式、失败、空列表状态保持原样。
- Proof：定向单元测试 + 完整 `test`、`lint`、`assembleDebug`、`:app:compileInstrumentationKotlin`。真机与模拟器 UI：尚未验证。

## 1. 简介改为底部折叠栏（默认收起，点击上拉展开）

原实现把 `FeedMetadata` 常驻渲染在 `BottomStart`：频道名、体积/清晰度、最多 3 行文案、标签、展开按钮、发布时间全部叠在画面上，竖屏时遮住画面下部。

现在拆成两层：

- **折叠栏**（默认）：单行卡片，高度不低于 48dp 触控目标，右侧「展开简介」文案加向上箭头。第一行显示频道名，第二行显示简介单行预览（有文案用文案，没有文案用标签），换行符被压平为空格，因此多行文案不会把栏撑高。
- **展开面板**（点击折叠栏后）：`AnimatedVisibility` 从底部向上展开，挂载原有 `FeedMetadata`（标签、发布时间、原画回退提示与「展开」入口全部保留）。文案上限从 3 行提升到 `EXPANDED_CAPTION_MAX_LINES = 6`，溢出判定仍然由 `onTextLayout` 的真实视觉溢出驱动，「展开」按钮只在确实溢出时出现，点击后仍打开原有「视频详情」底部弹层。

状态管理：`expandedMetadataKey` 与既有 `detailVideoKey` 同构，按视频 key 隔离；切换到下一条视频、队列换代或离开页面即收起，因此「默认收起」对每条视频都成立。展开期间 `autoHideEligible` 为 false，播放中 3 秒自动隐藏控件不会把用户刚展开的简介吃掉。

底部遮罩层高度由屏幕高度 46% 降到 34%（`BOTTOM_SCRIM_HEIGHT_FRACTION`），进一步减少对画面的压暗。

## 2. 进度条上移

原实现 `PROGRESS_BOTTOM_OFFSET = 16.dp`，且可见轨道贴在 48dp 触控盒的**下边缘**（`align(BottomCenter)`），所以可见细线几乎贴着系统手势条，起拖容易触发系统返回手势；同时 48dp 触控区与当时的简介块底部相互重叠。

现在：

- `PROGRESS_BOTTOM_OFFSET = 32.dp`，触控区整体上移。
- 可见轨道改为在 48dp 触控盒内**垂直居中**（`align(Alignment.Center)`），轨道中心距安全底边 `32 + 24 = 56dp`。
- 简介栏底部改为 `METADATA_BOTTOM_OFFSET = PROGRESS_BOTTOM_OFFSET + 48.dp + 8.dp = 88.dp`，即由进度条偏移推导而来，两者不再重叠。右侧操作栏沿用 `162.dp`（`ACTION_RAIL_BOTTOM_OFFSET`），与原有的 180dp 底部预留约定保持一致。

拖动依然映射到 x 轴，触控高度、scrub 提示、seek 行为均未改变。

## 3. 播放期间禁止自动熄屏

新增 `WindowScreenOnController`（与既有 `WindowSecurityController` 同一形态）持有窗口级 `FLAG_KEEP_SCREEN_ON`，由 `KeepScreenOnEffect` 在 `VideoPlaybackScreen` 中驱动。

- 判定收敛为纯函数：`shouldKeepScreenOn(uiState) = phase == CONTENT && player.isPlaying && !player.isPaused`。
- 暂停、播放结束、切到非 CONTENT 阶段或离开页面 → 立即清除标志，交回用户设置的熄屏时间；不持久化任何设置，不申请权限，不使用 WakeLock。
- `FLAG_KEEP_SCREEN_ON` 是窗口级标志，只在窗口可见时生效，因此退到后台无需额外处理。
- 仅在改动标志时清除，不无条件 clear，避免影响同窗口的其他持有者。

## 修改文件

- `app/src/main/java/com/qixuan/channelvideoflow/feature/video/VideoPlaybackScreen.kt`
- `app/src/main/java/com/qixuan/channelvideoflow/feature/video/WindowScreenOnController.kt`（新增）
- `app/src/main/res/values/strings.xml`
- `app/src/sharedTest/java/com/qixuan/channelvideoflow/feature/video/VideoPlaybackScreenTest.kt`
- `app/src/test/java/com/qixuan/channelvideoflow/feature/video/MetadataSummaryContractTest.kt`（新增）
- `app/src/test/java/com/qixuan/channelvideoflow/feature/video/WindowScreenOnControllerTest.kt`（新增）

## 测试契约变更说明

折叠栏改变了「简介」的默认可见形态，因此既有测试中所有依赖「常驻即可见」的断言改为先展开：

- 新增测试辅助 `expandMetadataIfCollapsed()`：以折叠栏的语义描述「展开视频简介」为判据，仅在收起状态点一次，避免重复点击导致再次收起。
- 在 20 处 `DetailsExpand`（「展开」按钮）交互前插入该辅助调用；`DetailsExpand` 的语义本身未变，仍然是「真实视觉溢出时才出现、点击打开视频详情」。
- `modalGesturesCannotReachPlaybackPagerProgressOrActionsAndCloseRestoresThem`：展开面板本身是用户主动打开的覆盖层，会盖住屏幕中心，因此在关闭弹层后先收起简介栏，再验证播放控件恢复可达。
- 返回键行为未变：仍然是「有详情弹层→关弹层，否则退出页面」。展开的简介栏不额外占用一次系统返回。

新增用例：

- `descriptionBarStartsCollapsedAndOneTapRevealsTheFullMetadata`：默认收起（完整文案不在语义树中、无「展开」按钮），一次点击后完整文案与「展开」按钮出现，再次点击恢复收起。
- `progressBarClearsTheBottomGestureStripAndTheDescriptionBar`：进度条触控区距底部 ≥30dp、轨道中心距底部 ≥46dp、且不与简介栏重叠（这三条在旧偏移下均不成立）。
- `MetadataSummaryContractTest`：简介预览取值与换行压平；`shouldKeepScreenOn` 在播放/暂停/非播放/非内容阶段四种组合下的取值。
- `WindowScreenOnControllerTest`：置位与清除 `FLAG_KEEP_SCREEN_ON`。

## 验证

环境：Windows，`JAVA_HOME=E:\Android Studio\jbr`。

```powershell
.\gradlew.bat :app:testInstrumentationUnitTest --tests "*VideoPlaybackScreenTest" :app:testDebugUnitTest --tests "*WindowScreenOnControllerTest" --tests "*MetadataSummaryContractTest" --no-daemon --console=plain --max-workers=1
.\gradlew.bat test lint assembleDebug :app:compileInstrumentationKotlin --no-daemon --console=plain --max-workers=1
```

- 定向：`VideoPlaybackScreenTest` 81/81、`MetadataSummaryContractTest` 2/2、`WindowScreenOnControllerTest` 1/1，0 failures / 0 errors。
- 全部测试使用 Fake 数据与 Robolectric，不使用真实 Telegram 凭证、账号或媒体。
- 无新增权限、无新增生产依赖、无数据库迁移、无公开存储写入。

### 尚未验证

- 真机（红米 Note 11 Pro+）上的实际观感与手势手感，以及系统自动熄屏在真实 ROM 上的表现。
- 完整 Compose Proof 中的 emulator-Compose-UI 与 Redmi install+launch 环节：本次未执行 adb，因此未验证。
- 展开面板在超长文案下的实际高度占用：本次只做静态上限（文案 6 行 + 标签 2 行），未做真机视口实测。
