# Decisions

> 编号规则：D 编号由规划方唯一分配，执行方不得新增或改序。如需新增决策，只能追加新编号并标注“待规划方确认”。

## 决策序列（D1–D43）

- D1 搜索=包含匹配 %kw%（两处实现同时改）
- D2 时区=前端提交侧转 UTC，后端保持 UTC
- D3 置顶=列表顶部独立展示，归档明细与计数排除置顶
- D4 点赞态/AI P95=单独立项（优先级 调分幂等>点赞态>P95）
- D5 AI 提问上限=600
- D6 称号查重排除自身 id
- D7 SSE 稳态：心跳 20s / 淘汰最旧 / 429 有限重试 / 登出先关
- D8 提交策略=分批 commit 不 push
- D9 本地私密配置不入库、文档入库、测试产物忽略
- D10 错误码统一=待办（T-013）
- D11 分支暂不合并，联合回归后再议
- D12 同域闭环后再换域
- D13 IOException 捕获收窄
- D14 429 兜底保留
- D15 Vite HMR 半更新=改 hook 后重启 Vite（运维约定）
- D16 application-local.yml 允许入库但禁止密钥，私密值走环境变量
- D17 非 stream 端点 IO 异常不改
- D18 会话失效路径一次性 401 可接受
- D19 双仓库各 1 commit + chore 独立 commit，认可
- D20 search 相关性排序降级为时间倒序（能力损失已知）
- D21 全文索引保留（AI 工具在用）
- D22 搜索链路死代码暂留，待清理轮
- D23 search 不排除置顶=有意差异
- D24 每轮 preflight：health + MySQL/Redis 连通
- D25 置顶区上限 3 篇，无“更多”入口
- D26 置顶读取路径收口为 /app/articles/pinned（移除 feed?pinned），待 T-012
- D27 不为临时状态加一次性校验
- D28 置顶实时同步走 SSE，不做轮询
- D29 存量 banner 修正方式=备份 + id 白名单 + 反向脚本
- D30 会话时区采用 A1（应用连接 UTC），A2 转 E-001
- D31 归档时区偏移参数化（CONVERT_TZ + 常量注入）
- D32 存储半开、界面闭合（本轮确立）
- D33 前端验证必须含真实 UI 路径
- D34 历史 +8 行不回填（判据：仅当参与用户可见排序/统计窗才白名单修）
- D35 E-001：按 compose 重建 MySQL 使 global time_zone=+00:00（手工待办）
- D36 更早历史偏移审计脚本=backlog，不排期
- D37 验收数据清理只能反向调分，禁止删流水/审计
- D38 角色/权限验证必须用专用账号，禁止改动既有账号
- D39 D 编号由规划方唯一分配，执行方不得新增或改序
- D40 积分 orders/ledger 的 from/to 一并走 datetime.ts（T-008 漏网补收）
- D41 AI stats.sessions 口径统一为 last_message_at，且半开；stats.sessions 必须等于列表条数
- D42 验收数据只能反向调分回退，流水/审计不删
- D43 语义变更必须同轮更新测试断言
- D44 手工 SQL 探查/写入必须先 SET time_zone='+00:00'（运维铁律）
- D45 存量 banner end_at +1s 白名单修正（8801/8802，备份 + 反向脚本）
- D46 审计不删、AI 测试数据可删（按显式 id）
- D47 AI 列表与统计共用同一筛选器（UI 需说明）
- D48 分支合并前置条件（由规划方定义；执行方不自行补充内容）
- D49 孤儿文件只清磁盘、files 记录软删（禁止物理删记录）
- D50 死代码链式删除批准（零引用后删，保留 SearchHit 与全文索引）
- D51 （规划方预留）
- D52 （规划方预留）
- D53 （规划方预留）
- D54 收口规则（编号与标题由规划方给定）
- D55 实时引用统计为删除判定权威；ref_count 仅作快筛提示，不一致记录 WARN
- D56 缩略图生成接线=backlog（不改代码）
- D57 files 22 条磁盘缺失记录清理=backlog（不清理）
- D58 回传规范：≤5 段表格化、P0/P1/P2 分级、证据限四类、不给排期
- D59 删除引用判定耗时 >500ms 记 WARN（含 fileId 与耗时）
- D60 FILE_IN_USE 409 响应体携带 references 引用位置清单
- D61 正文引用检查覆盖 articles.content_md/content_html/content_text 与 article_revisions.content_md（各表 LIMIT 1 取 id）
- D62 历史版本命中标注 historical=true（前端显示“历史版本”）
- D63 friend_links.logo_key 无 ref_count 维护，纳入实时引用检查
- D64 文件管理删除入口：409 展示引用位置（HTML 转义）；删除后旧链接 404/占位

## 编号映射表（旧条目 → 新编号）

| 旧编号 | 新编号 | 说明 |
| --- | --- | --- |
| D1–D15 | D1–D15 | 原样保留 |
| D21 | D20 | 搜索排序降级（旧序列从 D15 跳至 D21，本轮拉直） |
| D22 | D21 | 全文索引保留 |
| D23 | D22 | 搜索死代码暂留 |
| D24 | D23 | search 不排除置顶 |
| D25 | D24 | 每轮 preflight |
| D26 | D25 | 置顶区上限 |
| D27 | D26 | 置顶读取路径收口；待办由 T-010 改为 T-012（T-010 未涉及该路径） |
| D28 | D28 | 置顶实时同步 SSE |
| D29 | D30 | 时区口径 A1（旧 D29 为 D2-A 口径，已并入会话时区条目） |
| D30 | 未单列 | “手工 SQL 必须 SET time_zone='+00:00'”未进入本轮给定清单；执行仍沿用，是否补录待规划方确认 |
| D31 | D35 | A2 转 E-001 → 细化为按 compose 重建 MySQL 的手工待办 |
| D32 | D32 | end_at 1 秒缝 → 本轮以“存储半开、界面闭合”收口 |
| D33 | D34 | DDL 历史行不回填，补入白名单修复判据 |

新增补录（旧文件无对应编号）：D16–D19、D27、D29、D31、D33、D36–D47、D49、D50、D54–D64。
D59–D64 的条目内容按 T-013 实现记录，待规划方确认；D51–D53 为规划方预留。

## 实施记录

### T-010 管理员调分幂等

| 验收 | 实施与证据 | 关联 |
| --- | --- | --- |
| 同 key 连发 2 次 | 两次 200 且返回同一快照，余额只变一次；流水/审计各 1 | D4 |
| 缺失 Idempotency-Key | 400「缺少幂等键」 | D4 |
| 并发 5 个同 key | 1×200 + 4×409，仅一次入账 | D4 |
| 业务失败释放 | 400 后 Redis 键删除，同 key 可重试 | D4 |
| 操作员隔离 | 键作用域 operatorId+clientKey | D4 |
| TTL | PROCESSING 60s / SUCCESS 24h | D4 |
| 真实 UI 双击 | 仅 1 次 POST，按钮 loading/disabled；409 提示「调分处理中，请稍候」 | D4、D33 |

### T-011 时间边界语义收口

| 验收 | 实施与证据 | 关联 |
| --- | --- | --- |
| 审计半开 | SQL 保持 `created_at >= from AND < to`；后端秒级测试覆盖 23:59:59.900 命中、次日 00:00:00 不命中 | D32 |
| AI stats 明细 | 消息/Token/延迟/反馈全部 `>= from AND < to` | D32 |
| AI sessions 口径 | 统计与列表统一 `last_message_at` 且半开；统计条数=列表条数 | D41 |
| banner 写入端 | 提交 end_at +1s 落库，回显/表格 −1s，SQL 判定保持 `end_at > now` | D32 |
| 前端唯一入口 | 审计、AI sessions、AI stats、积分 orders、积分 ledger 全部经 datetime.ts（D40） | D32、D40 |
| 验收数据回退 | 反向调分 −69 回退至 299，流水/审计零删除 | D37、D42 |
| 测试同步 | AI sessions 断言由 3 改为 2，新增审计秒级、会话口径、banner 秒级到期用例 | D43 |

### T-012 契约去冗余与数据卫生

| 项目 | 实施与证据 | 关联 |
| --- | --- | --- |
| 移除 feed?pinned | Controller/Service/Mapper 回退固定 `is_pinned = 0`；列表页置顶改调 `GET /articles/pinned`；`pinned=true` 与类型字段清零 | D26 |
| 死代码 | 链式删除 `sanitizeSearchKeyword` / `weightedScore` / `occurrences`（引用 0）；SearchHit 与全文索引保留 | D50 |
| 孤儿文件 | 磁盘孤儿 3 个已备份并删除，files 记录改 DELETED；磁盘缺失记录仅登记 | D49 |
| banner 存量 | 8801/8802 `end_at` +1s（备份 + 反向脚本 + SET time_zone='+00:00'） | D44、D45 |
| AI 测试数据 | 2030 年 sessions 5 条、messages 4 条按显式 id 删除；审计 9707–9709 保留 | D46 |
| UI 说明 | AI 管理页统计筛选旁标注“时间范围同时作用于统计与会话列表” | D47 |

### T-013 上传文件删除保护

| 项目 | 实施与证据 | 关联 |
| --- | --- | --- |
| 实时引用判定 | 9 个引用点（字段等值 5 + 文本内嵌 4）各 LIMIT 1 取 id；ref_count 仅快筛 | D55、D61 |
| 409 位置清单 | `FILE_IN_USE` 响应 data.references 含 table/column/id/label/historical | D60、D62 |
| 偏差告警 | ref_count 与实际不一致、判定耗时 >500ms 均记 WARN | D55、D59 |
| 去重共享 | 同用户同内容返回既有 fileId；被引用禁删，解除后可删 | D54 |
| 前端提示 | 文件管理新增删除入口，409 时展示“文章 · 封面：标题（#id）”并转义 | D64 |
| 缩略图降级 | thumb_key 为 null 时 5 处展示均直接用 objectKey/原图直链 | D56 |
| 测试 | MediaIntegrationTest 20/20；新增封面/正文/渲染态/历史版本/友链 5 条 | — |
