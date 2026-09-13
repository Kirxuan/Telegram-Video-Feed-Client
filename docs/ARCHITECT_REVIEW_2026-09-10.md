# VELORA（曜流）全面架构审查与优化方案

审查日期：2026-09-10
审查范围：全仓库（`app` / `player` / `telegram` / `telegram:tdlib` / `core:model` / `core:domain` / `core:database` / `benchmark` / `scripts` / Gradle 配置 / docs）
审查基线：正式发行 Stage 24 / VELORA 1.1.0；工作树为 Stage 26（未提交，117 项变更）
审查性质：**只读审查，未修改任何生产代码**

---

## 零、审查结论摘要

本项目工程质量显著高于同规模 Android 项目：分层边界清晰、失败关闭路径完整、generation 门控体系严密、性能度量可归因、文档与代码同步度罕见地高。**不存在需要推翻整体架构的理由**。

但审查发现了 **3 个结构性问题** 和 **11 个可优化点**，其中 4 项属于「投入低、收益确定」的高价值项，2 项属于「需要实验证明」的中价值项，1 项是当前最大但被现有文档低估的风险。

### 核心判断（按重要性排序）

| # | 判断 | 类别 | 优先级 |
|---|---|---|---|
| 1 | **当前移动数据连续性 FAIL 的首因不是播放器实例数**，而是「TDLib 单文件游标 + 首帧关键路径的串行等待」。继续在双池上投入边际收益递减 | 方向性判断 | **最高** |
| 2 | `VideoPlayerManager` 的 4Hz ABR 评估与 25ms 级 `progressTicker` 耦合在同一 Handler 上，且 `refreshPlaybackProgress()` 无条件重算 ABR —— 高频路径存在确定性的可消除浪费 | 性能 | 高 |
| 3 | `VideoPlaybackScreen.kt`(2893) + `VideoPlaybackViewModel.kt`(1999) + `VideoPlayerManager.kt`(1986) 三个超大文件构成回归定位瓶颈，且 State 竞态无法被静态证明 | 可维护性 | 高 |
| 4 | `videos` 表软删记录无界增长（仅前台触发清理 + 30 天保留），长期运行索引必然膨胀 | 稳定性 | 高 |
| 5 | `TelegramMediaDataSource.acquireAndOpen()` 单函数 >140 行、6 层嵌套；`awaitAvailable(15s)` **同步阻塞在 ExoPlayer 加载线程**。这是设计正确但难以验证的风险集中点 | 可维护性/正确性 | 中高 |

---

## 一、项目现状的客观评估

### 1.1 值得保留并作为基线冻结的设计（明确不推翻）

审查中反复确认以下方案不应被替换，理由充分：

| 设计 | 保留理由 |
|---|---|
| 唯一 TDLib 私有媒体缓存（禁用 Media3 SimpleCache 双缓存） | 双缓存会造成同一视频两份磁盘副本与 LRU 语义冲突；当前设计是唯一自洽解 |
| owner token + generation 的多层陈旧回调拒绝 | 覆盖了账号/质量/队列/网络/轮次/绑定全部维度，实测证明了价值（见 Stage 26 的多项修复） |
| RangeRequestCoordinator 区间合并 + 优先级仲裁 | 尊重 TDLib 单文件下载游标的物理约束；aria2 式多连接不适用于此 |
| 自绘 Canvas 图标与渐变海报（不引入 Coil/Glide） | 零额外依赖实现零网络图片加载，包体与启动收益明确，且视觉可控 |
| 枚举式导航（不引入 Navigation Compose） | 仅 4 个顶层目的地；Navigation Compose 会带来参数序列化开销与额外依赖 |
| 轻量键快照 + 窗口化水合（`VideoFeedKeySnapshot` + `FEED_HYDRATION_RADIUS = 3`） | 已把「全量对象快照」降为「全量键 + 有界对象」，是正确方向 |
| `toPresentationSnapshot()` 剥离进度字段 | 4Hz 快照只驱动进度条子树的隔离手段正确 |
| 移动数据新增请求 2MiB 预算 + 取消不退款 | 预算语义清晰，防止无限重试放大流量 |
| 两处 10MiB 上限（`ALLOWED_NEXT_BYTE_BUDGETS` / `ABSOLUTE_MAX_BYTES`） | 数值一致虽为巧合，但都是保守边界 |

### 1.2 历史已否决方案（不得重新包装）

| 方案 | 否决证据 | 处置 |
|---|---|---|
| Owner Promotion（同区间仅改优先级） | Stage 13B 同设备 A/B：关闭 P90 1,599ms vs 开启 3,033ms，**恶化 89.7%** | 永久关闭，`PRODUCTION_OWNER_PROMOTION_ENABLED = false`；不得与 SampleQueue 或双池组合后重新提出 |
| KSP 替代 KAPT | Hilt 2.58 的 KSP 支持不满足「只用稳定依赖」合同 | 保持 KAPT |
| 全量 `@Immutable` 标注 | 缺乏 Compose compiler metrics 支撑，属猜测式优化 | 先开 `cvfComposeCompilerReports` 取数再决定 |
| `@Synchronized` 全面替换显式锁 | 先需证明线程所有权，否则掩盖真实竞态 | 保持 `synchronized(lock)` + `monitor` 双层 |
| 合并多个 DataStore | 无足够收益，不承担迁移风险 | 保持 |
| 8 并发 `getFile` | TDLib 串行边界；应改为「取消全量启动扫描 + 预算化对账」 | 保持串行 |
| LRU 加索引「按列顺序猜」 | 必须先用 `EXPLAIN QUERY PLAN` 证明 | 见 §3.3，该验证**尚未执行** |

---

## 二、核心方向性判断（本轮最重要的结论）

### 2.1 双播放器不是当前瓶颈的决定性证据

Stage 26 提供了两个关键对照数据：

```
C1（082001，无离屏 Surface）：bind→首帧 P50/P95 = 2036 / 12355ms，READY 命中 1/20，连续性 FAIL
C2（083000，离屏 Surface）  ：bind→首帧 P50/P95 =  995 /  2044ms，READY 命中 3/20，连续性 FAIL
```

**C2 在首帧指标上大幅优于 C1（P95 改善 83%），但连续性仍然 FAIL，且重缓冲从 6 次升到 7 次。**

关键推论：如果双池准备真的命中，`bind→首帧` 应接近 `readyToFirstFrame`（历史真机值 P50 88–89ms / P90 226–229ms）。实际 C2 命中的 3 次子组首帧 P50 是 **100ms** —— 正好吻合。

也就是说：**C2 的机制是有效的，命中时确实是「秒开」；问题在于 20 次里只有 3 次命中。**

为什么命中率只有 15%？Stage 26 已经诊断出根因链：

1. 移动数据下备用下载预算仅 2MiB/目标 → 对 2560×1440 / 964MB 这类视频，2MiB 不足 1 秒
2. 备用解码 READY 不代表具备起播缓冲 → 需要额外 `hasPoolStartupReservoir` 校验
3. 起播修复后备用时间上限提到 3s，样本内存仍 1MiB

**这是一个「预算 vs 素材体积」的结构性失配，不是播放器数量问题。**

### 2.2 建议的方向调整

**不建议继续投入第二个播放器的进一步优化。建议把 Stage 27 的重心转移到「让第一个播放器更快拿到可解码样本」。**

具体候选方向（按预期收益排序）：

#### 方向 A：首段范围与容器索引的联合优化（推荐）

**问题**：首段 64KiB `INITIAL_REQUIRED_BYTES` 是 2026-09-10 才从 256KiB 降下来的（Stage 26 第 7 条）。但对 >1GB 的 4K 视频，MP4 的 `moov` 索引可能在文件尾部。此时 ExoPlayer 的 Extractor 必须先拿 64KiB → 解析发现需要尾部 → 再发一次区间请求 → 才能拿到索引。

`StartupPreloadCandidate` 已支持 `TAIL_64` / `TAIL_128` / `HEAD_512_WIFI` 候选，但：

- 生产默认仍是 `BASELINE`
- `HEAD_512_WIFI` 明确标注仅 Wi-Fi

**候选方案对比**：

| 方案 | 机制 | 收益 | 成本 | 风险 |
|---|---|---|---|---|
| A1 保持 BASELINE | 让 Extractor 自行探索 | — | — | 长尾取决于文件结构 |
| A2 首段 + 尾段并发探测 | 首个 DataSpec 同时申请 head 64KiB 与 tail 64KiB，Extractor 无论 moov 在哪都能一次拿全 | 消除「两次串行区间请求」的 RTT 惩罚，估计对 moov-at-end 文件节省 1 个完整 RTT + 等待 | 多 64KiB 流量；需在 `TelegramMediaDataSource.open()` 增加一个 spec | 尾部 64KiB 对 faststart 文件是浪费（概率约 50%） |
| A3 解析后定向补取 | 首 64KiB 解析出 moov 位置后再请求 | 零浪费 | 无法消除第二次 RTT——**这正是当前行为** | — |

**推荐：A2，但条件化启用。**

- 判定条件：`fileSize > 4MiB && durationSeconds > 60`（小文件 moov 一定在头部附近）
- 流量代价：+64KiB/视频（相对 2MiB 预加载预算是可接受的）
- 必须在 `TelegramFileManager` 的区间合并逻辑中把 tail 请求当作**独立 owner**，不能污染当前播放的 merged range —— 现有 `MAX_FOREGROUND_REQUEST_BYTES = 4MiB` 足够容纳

**为什么推荐它**：这是唯一能在**不增加任何依赖、不改播放器架构、不违反 TDLib 约束**的前提下压缩首帧关键路径的方案。且它直接针对当前唯一没被优化的环节。

**必须诚实标注**：收益依赖源文件结构分布，仓库内**没有真实 moov 位置统计**。建议 Stage 27 先做一个只读统计（对已索引的 N 个视频抽样，记录 `fileSize` / `durationSeconds` / 首帧是否发生二次区间请求），用真实分布决定是否值得。

#### 方向 B：Extractor 层的前置探测（不推荐，仅记录）

用 `Mp4Extractor` 直接解析已缓存的头部字节，提前判断 moov 位置。**不推荐**：Media3 内部 API 不稳定，且会绕过 `MediaSource` 抽象，与依赖方向合同冲突。

#### 方向 C：继续提高移动数据备用预算（不推荐）

`ABSOLUTE_MAX_BYTES = 10MiB` 已是当前设计的保守边界。提高到 20MiB 意味着：
- 用户在移动数据下每条视频多消耗最多 10MiB
- 对 4K 视频仍然不够（964MB 的 1% 是 9.6MiB，只能覆盖约 1 秒）

**这是线性投入对指数需求，不划算。** 且与 AGENTS.md「移动数据新增请求预算上限 2MiB/下一目标」的合同冲突，需要重新授权。

#### 方向 D：接受当前性能，改为 UX 补偿（务实的兜底）

既然冷缺页的长尾在网络侧不可消除，可考虑：

- 长尾期间给出**确定性进度语义**（当前是 `FeedStateGraphic` + 加载态）
- 明确区分「正在从网络读取」vs「已缓冲可播放」vs「源不支持流式」
- 让用户理解等待原因，而不是面对一个无差别转圈

**这不是性能优化，但对 P95 12s 的真实体验改善可能大于再压缩 500ms。**

---

## 三、具体优化清单

### 3.1 【高价值 · 低风险】ABR 评估与进度采样解耦

**位置**：`player/.../VideoPlayerManager.kt`

**现状**：
```kotlin
private val progressTicker = object : Runnable {
    override fun run() {
        refreshPlaybackProgress()          // 每 250ms
        if (progressTickerRunning) {
            progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MILLIS)
        }
    }
}
```
`PROGRESS_UPDATE_INTERVAL_MILLIS = 250L`，而 `refreshPlaybackProgress()` 内部调用 `applyPlaybackRisk()`，后者包含完整的 ABR 决策（`PlaybackRiskController.evaluate` + `latestPreloadSafety` 重算 + `updatePoolPreparation()`）。

**问题**：ABR 的输入中 `minimumSwitchIntervalMillis = 12_000L`、`upgradeStableWindowsRequired = 4`，其**有效决策频率远低于 4Hz**。以 4Hz 调用一个以 12s 为冷却期的控制器，每秒浪费 3 次完整评估。

**候选方案对比**：

| 方案 | 机制 | 收益 | 成本 |
|---|---|---|---|
| B1 保持 | 每次 tick 全量重算 | — | 确定性浪费 |
| B2 降低 ABR 频率 | 独立 1s 定时器求值 ABR，进度仍 4Hz | 减少 75% ABR 计算 | 需引入第二个 Handler 任务 |
| **B3 变更驱动 + 低频兜底（推荐）** | 进度 tick 只更新 UI 快照；ABR 仅在「缓冲变化 > 阈值 ∨ rebuffer ∨ seek ∨ 网络代次变化」或每 1s 兜底时求值 | 平峰期接近 0 次无效评估；尖峰期不延迟响应 | 需定义触发条件集合 |
| B4 不降频，只前置短路 | 在 `applyPlaybackRisk()` 入口检查 `now - lastAbrEvaluationMillis < 1000` 直接返回 | 改动最小（约 5 行） | 收益小于 B3 但风险最低 |

**推荐：先用 B4 做基线（改动 5 行，可立即验证），若 Compose 重组或电池数据表明仍不足，再升级到 B3。**

**理由**：B4 的语义已被代码隐含支持——`lastAbrEvaluationMillis` 字段已存在，说明作者已意识到节流需求但未实施。这是一个明确未完成的小改动。

**必须验证**：ABR 节流后，`PlaybackRiskControllerTest` 与真实回退路径必须仍通过；`SWITCH_COOLDOWN` 与 `UPGRADE_STABILITY` 的行为不得因调用频率降低而改变。这需要补充测试，**当前尚未验证**。

---

### 3.2 【高价值 · 中风险】软删记录的无界增长

**位置**：`core/database/.../VideoIndexDao.kt` + `telegram/.../TdLibTelegramMessageRepository.kt`

**现状**：
- `deleteMessages` 软删（`is_deleted = 1, invalidated_at = now`）
- `purgeInvalidatedBefore(cutoff)` 才硬删，`DELETED_VIDEO_RETENTION_MILLIS = 30 天`
- 清理触发条件：`setForeground(true)` 时若 `retentionCleanupPending` 可 CAS 才执行

**问题**：
1. 只在**进入前台**且 flag 可 CAS 时清理 —— 长会话用户可能整月不触发
2. `observeFilteredVideoKeyRows` 的 WHERE 含 `is_deleted = 0`，但索引是 `(is_deleted, invalidated_at)`；软删记录越多，该索引的扫描比例越高
3. `deleteOrphanTags()` 只在 `completed || paginationStalled` 时执行 —— 增量同步路径不会清理孤儿标签

**候选方案对比**：

| 方案 | 机制 | 收益 | 成本/风险 |
|---|---|---|---|
| C1 保持现状 | 前台触发 + 30 天保留 | — | 长期膨胀 |
| C2 启动时强制清理 | `Application` 启动即 purge | 保证最终一致 | 阻塞启动路径 |
| **C3 后台化 + 有界批量（推荐）** | 清理移入独立低优先级协程：每次最多删 N=500 条、`yield()` 让出、失败不重试；触发点扩为「前台进入 ∨ 每 6 小时 ∨ 累计软删 > 1000」 | 无窗口依赖，有界不阻塞 | 需新增一个定时器与批量 DAO 方法 |
| C4 改硬删 | 删除即物理删 | 索引永不膨胀 | **破坏「删除后可恢复」语义**，且 `MessagesDeleted(!fromCache)` 的时序不确定性会导致误删 |

**推荐：C3。**

**理由**：C4 不可行（TDLib 的 `UpdateDeleteMessages` 存在 `fromCache=true` 的乱序可能，硬删会误伤），C2 会恶化启动。C3 是唯一同时满足「不阻塞」「最终一致」「不误删」的方案。

**额外建议**：给 `deleteOrphanTags()` 增加「增量同步累计 N 次后触发」的路径，而不只是扫描完成时触发。当前 `commitPage` 的 `completed || paginationStalled` 条件让增量路径永不清理孤儿标签。

**必须验证**：`VideoIndexDaoTest` 需补充「软删 5000 条 + 触发清理 → 查询计划与结果正确」用例；**当前尚未验证**。

---

### 3.3 【中价值 · 需先取数】SQL 查询计划与实际索引验证

**位置**：`core/database/.../VideoIndexDao.kt`

**现状**：两条主查询的 SQL 结构一致：

```sql
chat_id IN (:channelIds) AND is_deleted = 0
AND EXISTS(selected & accessible channel)
AND <标签条件>
ORDER BY publish_time DESC, chat_id DESC, message_id DESC
```

标签条件：
- OR：`EXISTS(... vt.normalized_tag_name IN (:normalizedTags))`
- AND：`SELECT COUNT(DISTINCT vt.normalized_tag_name) ... = :tagCount`

**问题（未经证实）**：
1. `video_tags` 的索引是 `(chat_id, message_id)` 与 `(normalized_tag_name)`。AND 分支的 `COUNT(DISTINCT)` 子查询以 `normalized_tag_name` 为驱动列可能需要回表取 `chat_id/message_id`；复合索引 `(normalized_tag_name, chat_id, message_id)` 可覆盖
2. `observeSelectedChannelScans` 的相关子查询 `(SELECT COUNT(*) FROM videos WHERE v.chat_id = c.chat_id AND v.is_deleted = 0)` 在频道数 × 视频数上是 O(N) —— 若频道多且视频多，会逐频道扫
3. `videos` 表索引 `(publish_time, chat_id, message_id)` 与 ORDER BY 匹配；但 `WHERE chat_id IN (...)` 可能让 SQLite 选择 `index_videos_chat_id` 再做排序

**关键约束（来自融合提案的明确记录）**：**不得按列顺序猜测索引，必须先用 `EXPLAIN QUERY PLAN` 在真实数据量下验证。**

**推荐：执行一次只读取数，然后按证据决定。** 具体：

```sql
-- 在 0 / 1,000 / 100,000 条三类数据集上分别运行
EXPLAIN QUERY PLAN <两条主查询的完整 SQL>;
-- 关注：是否出现 SCAN videos（全表）、是否出现 USE TEMP B-TREE FOR ORDER BY
```

**候选方案（取数后再定）**：

| 方案 | 触发条件 | 动作 |
|---|---|---|
| D1 加复合索引 | 若出现 `USE TEMP B-TREE FOR ORDER BY` | 评估 `(is_deleted, publish_time, chat_id, message_id)` |
| D2 加覆盖索引 | AND 分支出现回表 | `video_tags(normalized_tag_name, chat_id, message_id)` |
| D3 改查询形态 | `IN (:channelIds)` 参数过多导致计划退化 | 改为 JOIN 频道子查询 |
| D4 物化计数 | `observeSelectedChannelScans` 的 `COUNT(*)` 成为热点 | 在 `channels` 表维护 `indexed_video_count` 计数列，随 `commitPage` 增量更新 |

**推荐：D4 的独立评估优先级最高**，因为它是纯 O(N)→O(1) 的改进，且 `commitPage` 已经是事务边界，增量维护计数几乎零成本。

**当前状态：三项均尚未验证。** 不得在验证前添加索引（每加一个索引都会拖慢写入，而本项目的写入路径恰好是高频的）。

---

### 3.4 【中价值 · 低风险】`TelegramFileManager` 的扇出与重复快照查询

**位置**：`telegram/.../TelegramFileManager.kt`

**发现的确定性浪费**：

1. **`ensureRequestLocked` 的全局扇出**：`release()` / `updatePriority()` 中
   ```kotlin
   entries.forEach { (candidateFileId, candidate) ->
       ensureRequestLocked(candidateFileId, candidate)
   }
   ```
   每次 owner 释放都会遍历**全部** file entry。在同时缓存几十个文件的会话中，这是 O(files) 的重复调用。

2. **`planRequest` 的 O(n²) 合并循环**：`do { ... unsatisfied.forEach { ... } } while (changed)` —— owner 数通常 1–3，但这是可预期的 O(n²)。

3. **每区间的多次 `currentSnapshot`**：`TelegramMediaDataSource.acquireAndOpen()` 与 `reusableRangeEnd` 计算各自调用，同一区间至少 2 次。

**候选方案对比**：

| 方案 | 机制 | 收益 | 成本 |
|---|---|---|---|
| E1 保持 | — | — | — |
| **E2 短路 + 缓存（推荐）** | ① `ensureRequestLocked` 入口加 `if (entry.owners.isEmpty()) return`（已存在）并把扇出改为「只处理有 unsatisfied owner 的 entry」；② `acquireAndOpen` 内缓存一次 snapshot 传给后续计算 | 减少无效遍历与重复查询 | 约 20 行改动，语义不变 |
| E3 增量维护「有未满足 owner 的 fileId 集合」 | 用 Set 替代全表遍历 | O(1) 定位 | 引入新的不变量维护负担，与现有 `hasForegroundBlockerLocked()` 的遍历式风格不一致 |
| E4 重写为事件驱动 | 彻底移除扇出 | 最优 | 高风险，无收益证明 |

**推荐：E2。** E3 引入的新不变量与项目现有「小规模直接遍历、以简单性换取正确性」的风格相悖，且 owner 数上界很小。E4 不值得。

**注意**：`ensureRequestLocked` 入口已有 `if (entry.owners.isEmpty()) return`，所以扇出的实际成本主要在 `hasForegroundBlockerLocked()` 的 O(files) 遍历上。E2 应聚焦于此。

---

### 3.5 【中价值 · 高风险 · 需分期】超大文件拆分

**位置**：
- `app/.../feature/video/VideoPlaybackScreen.kt` — 2893 行
- `app/.../feature/video/VideoPlaybackViewModel.kt` — 1999 行
- `player/.../VideoPlayerManager.kt` — 1986 行

**问题**：
1. 回归定位困难 —— Stage 26 的多次修复（seek 首帧、起播缓冲、内存阈值）都需要在近 3000 行文件中定位
2. 状态竞态无法静态证明 —— ViewModel 持有 **11 个 generation 计数器**（`hydrationRequestGeneration`、`uiHydrationGeneration`、`accountGeneration`、`qualitySelectionGeneration`、`queueGeneration`、`playbackQueueGeneration`、`stablePageGeneration`、`planPreparationGeneration`、`retryGeneration` + 池内 `poolQueueGeneration`、`roundGeneration`），全部为 `var Long` 散落在类字段中
3. `VideoPlayerManager.bindInternal` 单函数逾 200 行

**候选方案对比**：

| 方案 | 机制 | 收益 | 成本/风险 | 适用性 |
|---|---|---|---|---|
| F1 机械拆分文件 | 把 Screen 的 Canvas 绘图/控制层/元数据层拆成独立文件 | 定位更快 | 低风险但收益有限（状态仍在 ViewModel） | 渐进起点 |
| **F2 抽取纯状态机（推荐）** | 把 11 个 generation 收敛为单一 `PlaybackGeneration` 值类，把状态转移抽为可主机测试的纯类（参照已有的 `PlaybackTargetCoordinator` / `PlaybackPoolCoordinator` 成功范式） | 竞态可测试；无关重组可度量 | 中高，需保证迁移期无双控制 | **最高价值** |
| F3 分层重构 ViewModel | 抽 UseCase 层 | 符合架构图 | 高，且当前已有 Repository 抽象，UseCase 层收益边际 | 不建议 |
| F4 全面重写 | — | — | 不可接受 | 拒绝 |

**推荐：F1 + F2 组合，且必须证明必要性。**

**重要约束**：融合提案已明确「**不机械拆分全部 Screen**，不引入平行架构」。因此：

1. **F1 可以立即做**（纯文件移动，`internal` 可见性保持，测试不变）
2. **F2 必须先证明**。参照项目自己的标准（如 `PlaybackPoolAbEvaluator` 要求 P95 改善 ≥15%），重构也应先给出可量化目标。建议的证明方式：
   - 开启 `-PcvfComposeCompilerReports`，取当前 `VideoPlaybackScreen` 的重组计数基线
   - 如果 `PlaybackContent` / `FeedPager` 等高层 composable 在 4Hz 进度更新下仍重组，则 F2 有价值
   - **当前尚无此基线，因此 F2 不得先于取数开始**

**关于 generation 收敛的具体建议**：11 个独立 `var Long` 最容易出错的是「忘记在某条路径上递增」。可引入一个 `PlaybackGenerationSnapshot` 值类，让「建立播放上下文」成为一次原子赋值：

```kotlin
// 示意，不修改代码
data class PlaybackGenerationSnapshot(
    val account: Long, val network: Long, val queue: Long,
    val round: Long?, val quality: Long, val hydration: Long,
)
```

这与已有的 `PlaybackPreparationContext` 高度同构 —— 事实上后者已经包含了其中 5 个字段。**这说明收敛是顺应现有设计的，不是新增抽象。**

---

### 3.6 【中价值 · 低风险】常量单一真源

**发现的两处重复定义**：

| 常量 | 位置 1 | 位置 2 | 值 |
|---|---|---|---|
| 10MiB 上限 | `PlaybackPoolCoordinator.ALLOWED_NEXT_BYTE_BUDGETS` | `NextPreloadBudgetController.ABSOLUTE_MAX_BYTES` | 10MiB |
| 512KiB chunk | `NextPreloadBudgetController.RANGE_CHUNK_BYTES` | `NextPreloadBudgetControllerBridge.CHUNK_BYTES` | 512KiB |
| 256KiB chunk | `TelegramMediaDataSource.DEFAULT_CHUNK_SIZE_BYTES` | `TelegramFileManager.FOREGROUND_READ_AHEAD_TRIGGER_BYTES` | 256KiB |

**问题**：三处语义相同的数值分散在不同模块，未来任一处调整都可能造成静默不一致。

**推荐**：在 `core:domain` 建一个 `PlaybackByteLimits` 对象作为单一真源，其余位置引用它。

**注意**：`player` 与 `telegram` 都依赖 `core:domain`，依赖方向合法。**改动极小，收益是防止未来的静默 bug。**

---

### 3.7 【中价值】`VideoPreloadManager` 的死代码与高频日志

**位置**：`player/.../VideoPreloadManager.kt`

**发现**：

1. **owner promotion 死分支**：`ownerPromotionEnabled` 生产值恒为 `false`（`PRODUCTION_OWNER_PROMOTION_ENABLED = false`），导致以下方法全部提前返回：`beginTargetPromotion()` / `commitTargetPromotion()` / `abandonTargetPromotion()` / `onCurrentPlaybackStarting()` / `onCurrentPlaybackRangeAcquired()` / `onCurrentPlaybackRangeAcquireFailed()`

2. **`restartDynamicLocked` 的循环内日志**：`while(true)` 每次迭代在 `synchronized(lock)` 内调用 `traceDynamicDecision`，日志字段多达 14 个。虽然 `trace()` 有 `BuildConfig.DEBUG` 门控，但**字符串拼接参数在调用前就已求值**——如果参数构造包含计算，则在 release 也有成本。

**候选方案对比**：

| 方案 | 机制 | 收益 | 风险 |
|---|---|---|---|
| G1 保持死代码 | 保留未来实验入口 | 未来可重启实验 | 可读性与测试面成本 |
| **G2 保留但标注 + 加防护（推荐）** | ① 在死分支加 `@Suppress` 与注释说明为何保留；② `trace()` 改为 lambda 延迟求值 `inline fun trace(block: () -> String)` | 消除 release 下的参数计算成本；保留实验能力 | 极低 |
| G3 删除死代码 | — | 最干净 | 与「历史否决可重新评估」的文档精神冲突；且 Stage 13B 已有负向证据，删除会丢失重启路径 |
| G4 改为 `-P` 编译期常量折叠 | 让 `ownerPromotionEnabled` 成为 `const` | 编译器消除分支 | 会破坏测试对两种模式的覆盖 |

**推荐：G2。** 特别是 `trace()` 的 lambda 化 —— 这是一个可推广到全项目的低风险改进（`TelegramFileManager`、`TdLibMediaCacheManager`、`AdaptivePreloadPolicyManager` 都有相同的 `if (BuildConfig.DEBUG) Log.i(...)` 模式）。

**关于 `trace()` 的实际影响**：需要澄清一个常见误解——`if (BuildConfig.DEBUG) runCatching { Log.i(LOG_TAG, message) }` 中的 `message` 是**调用点的字符串拼接**，在 `if` 之前就已构造。所以 release 构建下这些字符串仍会被创建并丢弃。**这是一个全项目范围的真实（虽小）浪费，且修复成本极低。**

---

### 3.8 【中价值】`TelegramMediaDataSource.acquireAndOpen` 的可读性重构

**位置**：`player/.../TelegramMediaDataSource.kt`

**现状**：单函数 >140 行，6 层嵌套 try/catch，所有失败分支都成对 close lease/registration。

**这个设计的正确性是无可指摘的**（每个分支都对称清理），但：

- 140 行的单一出口函数无法被单元测试覆盖全部分支
- 「忘记在某个新分支 close」是未来最容易引入的泄漏

**候选方案对比**：

| 方案 | 机制 | 收益 | 成本 |
|---|---|---|---|
| H1 保持 | — | — | 未来泄漏风险 |
| **H2 引入 RAII 式 helper（推荐）** | 把「获取资源 → 校验 → 失败则全部释放」抽为一个 `use`-style 内联函数，成功时转移所有权给调用方 | 结构上消除「忘记 close」的可能性 | 中等重构，需保持现有测试全绿 |
| H3 拆分小函数 | 纯提取方法 | 可读性提升 | 不消除泄漏风险 |
| H4 重写为状态机 | — | — | 过度设计 |

**推荐：H2，但优先级低于 §3.1–§3.4。**

**理由**：当前代码是**正确的**，且已有 `TelegramMediaDataSourceTest` 覆盖。H2 的收益是「防止未来的 bug」，而非修复现有的 bug。在 Proof 体系已经建立的前提下，可以做，但不应抢占性能方向的资源。

---

### 3.9 【低价值 · 记录】`PlaybackRangeRequestSession` 的优先级扇出

**位置**：`player/.../TelegramMediaDataSource.kt`

**现状**：每次优先级变化，遍历 `registrations.values.map(...)` 后逐个 `runCatching { updatePriority }`。

**评估**：活跃注册项通常 1–3 个。**实际影响可忽略。**

**处置建议：明确不优化。** 记录在此以免后续重复提出。这是一个典型的「看起来可以优化但收益为零」的项。

---

### 3.10 【低价值 · 记录】`RANDOM_PAGER_PAGE_COUNT = 2_000_000`

**现状**：随机模式用 2,000,000 页 Pager 实现「近似无限」轮播，配合 `PlaybackFeedSession` 的双轮次随机（`upcoming`）。

**评估**：
- 2,000,000 页在 `VerticalPager` 中不产生实际内存成本（Pager 只组合可见页）
- 但 `randomPagerStart` 的计算与 `logicalPage` 的取模在每次 settle 时都执行

**处置建议：不优化。** 这是用「大常数」换取「无需处理边界回绕」的合理工程取舍。改为真正的循环会引入边界回绕的复杂状态，收益不成立。

---

### 3.11 【需注意的已知局限】`playbackPoolCoordinator` 的 owner-token 上限

**Stage 25 已记录**：池协调器保存有界 owner-token 防重记录，**单次长会话达到 512 个不同准备 token 后会安全退单播放器**。

**这是已知的正确降级行为，不是 bug。** 但需要：

1. 在长期浏览验收中明确计入 —— 若正常使用 512 次滑动就会触发降级，则双池的长期稳定性存疑
2. 建议在 `PlaybackTransitionMetrics` 中增加一个「池因 token 耗尽而退役」的计数器，让该路径可观测

**当前状态：该计数尚未实现，也尚未验证实际触发概率。**

---

## 四、UI / 渲染 / 交互方向

### 4.1 已有的良好实践（保留）

- `enableEdgeToEdge()` 单入口
- `CONTROL_AUTO_HIDE_MILLIS = 3_000L` 控制层自动隐藏
- `FullscreenSystemUiEffect` 集中管理系统栏
- 渐变海报调色板（`VIDEO_POSTER_PALETTES` 6 组）避免网络图片
- `FLAG_SECURE` 内容保护（`WindowSecurityController` 仅 22 行，职责单一）

### 4.2 建议优化

| # | 优化点 | 现状 | 建议 | 优先级 |
|---|---|---|---|---|
| U1 | 进度条与播放状态的视觉语义 | 加载/缓冲/可播放三态区分依赖 `FeedStateGraphic` | 明确「正在读取网络」与「解码准备中」的差异化文案 | 中 |
| U2 | 无障碍与响应式验收 | Stage 25 已通过 320dp/200%/横屏/平板各 40/40 | **保持**，作为后续回归门槛 | 保持 |
| U3 | 4Hz 快照的重组范围 | 已用 `toPresentationSnapshot()` 隔离 | 需 Compose compiler metrics 验证是否真正隔离 | 中（需取数） |
| U4 | 硬编码中文文案 | 融合提案 P0-8 已提出 | 已迁至 `strings.xml`（`app/src/main/res/values/strings.xml` 在本轮 diff 中） | 已完成 |

**U1 的补充说明**：Stage 26 的 P95 12s 长尾是真实用户体验问题。当前 UI 在这种情况下与「正常缓冲」无法区分。**在无法消除长尾的前提下，改善等待语义的体验收益可能大于继续压榨首帧。** 建议 Stage 27 把它与方向 A 并列考虑。

**U3 的取数方法**：
```powershell
.\gradlew.bat :app:assembleRelease -PcvfComposeCompilerReports=true
# 输出：app/build/compose-compiler/reports
# 关注 VideoPlaybackScreen 中 composable 的 "restartable skippable" 比例与不稳定参数
```
**当前尚未执行此取数。**

---

## 五、安全与稳定性审查结果

### 5.1 已核实为正确（无需改动）

| 项 | 证据 |
|---|---|
| 凭证加密 | `SecureTelegramCredentialsProvider` + Android Keystore AES-GCM，存 `noBackupFilesDir` |
| TDLib 数据库密钥失败关闭 | `SecureTdLibDatabaseKeyProvider` 的 `MigrationRequired` / `Unavailable` 路径；`INVALID_DATABASE_KEY_CODE = 401` |
| 非 debug 强制空凭证 | `app/build.gradle.kts` 的 `credentialValue` 按 buildType 分支 |
| 权限最小化 | 仅 `INTERNET` + `ACCESS_NETWORK_STATE` |
| 备份排除 | `dataExtractionRules` / `fullBackupContent` |
| 媒体字节不进公共目录 | `TdLibDirectories` 强制 `cacheDir/tdlib/files` + canonical path 校验 |
| 日志脱敏 | `trace()` 只记状态名/字节数/枚举，无路径与凭证 |
| 内部资源 TTL | `DEFAULT_INTERNAL_RESOURCE_TTL_MILLIS = 5min`，`MAX = 15min` |
| HLS URI 严格解析 | `TelegramHlsUriCodec.parse` 校验 scheme/authority/userInfo/port/query/fragment + 正则白名单 |

### 5.2 需要关注的风险点

| # | 风险 | 现状 | 建议 |
|---|---|---|---|
| S1 | **Room 未加密** | Stage 25 明确记录「Room 未加密」 | 需评估：视频元数据（标题/标签/频道名）是否属敏感。若用户频道标题敏感，应考虑 SQLCipher 或字段级加密 |
| S2 | TDLib 旧库加密迁移未实现 | 只支持新空库 | 需两阶段迁移演练；**当前是已知未完成项** |
| S3 | `awaitAvailable(15s)` 同步阻塞加载线程 | `DEFAULT_TIMEOUT_MILLIS = 15_000L` | 设计正确（`TelegramFileRangeLease` 文档注明"Blocks only the Media3 loading thread"），但需确认超时不会导致播放器卡死。Stage 26 已观察到 7 次超时 |
| S4 | 堆上限 256MiB + 样本上限 32MiB | 已发生过 1 次 OOM | 需持续观察；`PoolLoadControl` 的阈值计数**不含容器解析与解码器对象**（文档已诚实标注） |
| S5 | `MediaCacheMetadataWriter` 的 `MAX_PENDING_KEYS = 256` 满载时会 `clearPending = true` | 从「逐条删除」退化为「全清」 | `delete()` 中 `deletions.size >= MAX_PENDING_KEYS` 时清空并置 `clearPending` —— 这是**保守正确**的（只清元数据不清媒体），但意味着删除洪峰后 LRU 完全重建。需确认不会导致缓存容量失控 |

**S5 的深入分析**：`clearPending = true` 会触发 `cacheEntryDao.clear()`，即**清空全部 LRU 元数据**。此时 `gateway.protectedFileIds()` 仍保护当前播放与下一条，`client.optimizeVideoStorage()` 仍受 TDLib 统计约束。所以**不会误删，但会暂时失去 LRU 排序能力**，直到元数据重新累积。这是可接受的降级，但应在文档中明确。

---

## 六、执行建议（分优先级）

### 立即执行（改动小、收益确定、风险低）

| 序 | 项 | 预期改动量 | 验证方式 |
|---|---|---|---|
| 1 | §3.7 `trace()` 全面 lambda 化 | 全项目约 8 处 | 编译通过 + 现有测试全绿 |
| 2 | §3.1 ABR 节流（B4 方案） | `VideoPlayerManager` 约 5 行 | `PlaybackRiskControllerTest` + 真实回退测试 |
| 3 | §3.6 常量单一真源 | `core:domain` 新增 1 文件，3 处引用 | 编译通过 |
| 4 | §3.4 E2 扇出短路 | `TelegramFileManager` 约 20 行 | `TelegramFileManagerTest` 全绿 |

### 需要先取数（不得凭猜测动手）

| 序 | 项 | 取数方式 |
|---|---|---|
| 5 | §3.3 SQL 查询计划 | `EXPLAIN QUERY PLAN` × 0/1k/100k 三档数据集 |
| 6 | §3.5 F2 generation 收敛 | `-PcvfComposeCompilerReports=true` 取重组基线 |
| 7 | §4.2 U3 重组隔离验证 | 同上 |
| 8 | §2.2 方向 A2 的真实收益 | 抽样统计 `fileSize` / `durationSeconds` / 二次区间请求发生率 |

### 需要独立阶段授权

| 序 | 项 | 前置条件 |
|---|---|---|
| 9 | §3.2 C3 软删后台清理 | 需新增 DAO 批量方法与定时器；需 Phase 合同 |
| 10 | §3.5 F1 文件拆分 | 纯机械移动，可作为独立低风险阶段 |
| 11 | §3.8 H2 RAII 重构 | 需 `TelegramMediaDataSourceTest` 覆盖全部失败分支 |
| 12 | §2.2 方向 A2 首段+尾段并发 | 需真机移动数据 A/B，且必须同条件对照 |

### 明确不做

| 项 | 理由 |
|---|---|
| §3.9 优先级扇出 | 收益为零 |
| §3.10 Pager 页数 | 合理取舍 |
| Owner Promotion 重启 | 已有负向 A/B |
| 引入 Coil/Glide/Navigation Compose/KSP | 违反依赖合同或无收益 |
| 提高移动数据预算到 20MiB | 线性投入对指数需求 |
| 硬删替代软删 | 破坏恢复语义 + 误删风险 |
| 全面重写 ViewModel/Screen | 风险不可接受 |

---

## 七、必须诚实标注的未验证项

按 AGENTS.md 要求，以下内容**均尚未实际验证**：

1. §2.2 方向 A2 的收益假设 —— 无真实 moov 位置分布统计
2. §3.1 B4 ABR 节流 —— 未构建、未测试、未度量
3. §3.2 C3 清理策略 —— 未实现、未验证查询计划
4. §3.3 全部索引变更 —— `EXPLAIN QUERY PLAN` 未执行
5. §3.4 E2 扇出短路 —— 未度量实际收益
6. §3.5 F2 generation 收敛 —— 无 Compose compiler 基线
7. §3.7 `trace()` lambda 化 —— 未度量 release 下的实际成本
8. §3.11 池 token 耗尽的实际触发概率 —— 未统计
9. §4.2 U1 等待语义改良的效果 —— 未做用户可感知度评估
10. §5.2 S5 `clearPending` 降级的实际影响 —— 未在删除洪峰下观测
11. 本审查未运行任何 `gradlew` 命令，所有既有测试结论均引用文档记载，**未重新验证**

---

## 八、结论

**VELORA 的架构是健康的。** 三层生成门控、owner 租约、单一媒体缓存、失败关闭凭证、区间合并调度 —— 这些设计的正确性在 Stage 26 的多轮真机验证中反复得到确认。审查没有找到需要推翻的核心方案。

**当前的主要矛盾不是架构，而是「TDLib 单文件游标的物理约束 vs 首帧关键路径的 RTT 次数」。** 双播放器把「准备」移到用户观看当前条的时间内，方向正确，但移动数据 2MiB 预算对 >100MB 的高码率素材覆盖不足，导致命中率只有 15%。

**因此建议：**

1. **不要再向双池追加投入** —— 机制已验证有效（命中时 100ms），瓶颈在预算与素材体积的结构性失配
2. **把下一阶段重心放在减少首帧关键路径的串行 RTT 次数**（方向 A2），并用真实分布数据决定是否值得
3. **并行推进 4 项低风险清理**（§3.1 / §3.4 / §3.6 / §3.7），它们不改变架构但消除确定性浪费
4. **在动结构重构前先取数**（§3.3 / §3.5），这是项目自身已经建立的良好纪律，应继续保持
5. **把「等待语义」当作一等公民** —— 在物理约束下，让用户理解等待原因的价值可能大于再压 500ms

**本审查未修改任何代码。** 所有建议均需用户明确批准后才能进入实现阶段。

---

## 附录：审查覆盖范围

| 模块 | 已读文件数 | 关键文件 |
|---|---|---|
| `app` | 30+ | `VideoPlaybackScreen.kt`(2893)、`VideoPlaybackViewModel.kt`(1999)、`PlaybackTargetCoordinator.kt`(202)、`ChannelSelectionScreen.kt`(976)、`GlossComponents.kt`(770)、`LoginScreen.kt`(609)、`TagFilterScreen.kt`(444)、`CacheSettingsScreen.kt`(385) |
| `player` | 21（全部） | `VideoPlayerManager.kt`(1986)、`VideoPreloadManager.kt`(936)、`TelegramMediaDataSource.kt`(690)、`PlaybackPoolCoordinator.kt`(461)、`Media3SamplePreloadController.kt`(434)、`ReusablePlayerLifecycle.kt`(395)、`StandbyVideoSurface.kt`(89)、`TdLibBandwidthMeter.kt`(74)、`PlaybackBufferPolicy.kt`、`SamplePreloadHandoffGate.kt`、`TelegramHlsPlaybackSession.kt` |
| `telegram` | 10（全部） | `TelegramFileManager.kt`(1351)、`TdLibTelegramMessageRepository.kt`(981)、`TelegramClientManager.kt`(695)、`TdLibMediaCacheManager.kt`(258)、`SecureTdLibDatabaseKeyProvider.kt`(207)、`MessageIndexStore.kt`(194)、`MediaCacheMetadataWriter.kt`(158)、`PrivateMediaCacheSizer.kt`、`TdLibDirectories.kt` |
| `core:model` | 7（全部） | `VideoModels.kt`(199)、`TelegramAuthState.kt`、`TelegramChannel.kt` |
| `core:domain` | 20（全部） | `VideoPreloadPolicy.kt`(405)、`StreamingNetworkMetrics.kt`(298)、`PlaybackFeedSession.kt`(234)、`PlaybackRiskController.kt`(231)、`NextPreloadBudgetController.kt`(214)、`TelegramFileGateway.kt`(175)、`VideoQualitySelector.kt`(170)、`MediaCache.kt`(133)、`HashtagParser.kt`(109) |
| `core:database` | 10（全部） | `VideoIndexDao.kt`(668)、`DatabaseMigrations.kt`(172)、`ChannelDao.kt`(127)、`MediaCacheEntryDao.kt`(82)、`VideoEntity.kt`、`VideoTagCrossRef.kt` |
| Gradle | 8 | 根/`app`/`player`/`telegram` `build.gradle.kts`、`libs.versions.toml`、`settings.gradle.kts`、`gradle.properties` |
| docs | 12 | `ARCHITECTURE.md`、`STAGE26_MOBILE_DUAL_PLAYER.md`、`STAGE26_GITHUB_LOADING_RESEARCH.md`、`STAGE25_OPTIMIZATION_RESULTS.md`、`VELORA_OPTIMIZATION_HANDOFF.md`、`OPTIMIZATION_PROPOSAL_COMBINED.md`、`OPTIMIZATION_PROPOSAL.md`、`ACCEPTANCE_TESTS.md`、`SECURITY.md`、`PRODUCT_SPEC.md`、`DEVELOPMENT_PLAN.md`、`README.md`、`AGENTS.md` |
