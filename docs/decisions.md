# Decisions

- D1 搜索=包含匹配 %kw%（两处实现同时改）
- D2 时区=前端提交侧转 UTC
- D3 置顶=列表顶部独立展示、归档明细与计数排除置顶
- D4 点赞态/AI P95=单独立项，优先级 调分幂等>点赞态>P95
- D5 AI 提问上限=600
- D6 称号查重排除自身 id
- D7 SSE 稳态=T-004 已实施（心跳 20s/淘汰最旧/429 有限重试/登出先关）
- D8 提交策略=分批 commit 不 push
- D9 本地配置不入库、文档入库、测试产物忽略
- D10 错误码统一=待 T-009
- D11 分支暂不合并
- D12 同域闭环后再换域
- D13 IOException 捕获收窄
- D14 429 兜底保留
- D15 Vite HMR 半更新=改 hook 后重启 Vite（运维约定，不改代码）
- D21 search 排序=时间倒序（相关性排序已降级，能力损失已知；恢复需放宽"id 序列一致"验收口径）
- D22 全文索引保留，AI 工具仍在使用
- D23 搜索链路死代码暂留，待联合回归后统一清理
- D24 search 不排除置顶=有意差异
- D25 每轮 preflight：health + MySQL/Redis 连通
- D26 置顶上限=3（列表页顶部置顶区最多展示 3 篇）
- D27 置顶双路径（/articles/pinned 与 feed?pinned）收口归 T-010，期间禁止新增调用 feed?pinned
- D28 置顶实时化走 SSE，不做轮询
- D29 时区口径=D2-A：前端提交侧转 UTC，后端全程 UTC，不做后端时区解释
- D30 手工 SQL 必须 SET time_zone='+00:00'（T-009 banners.updated_at 修正已按此执行）
- D31 A2（MySQL 全局时区收口/重建容器）记为 E-001 待办，本轮不执行
- D32 end_at 1 秒缝（end_at > now 与 23:59:59 展示）待 T-011
- D33 DDL 默认值历史行不回填；49 个默认时间列中 8 列/12 行呈近期 +8 疑似（清单见 backup/T-009-20261030-222303/timestamp_suspect_sample.txt）

## T-010 管理员调分幂等（2026-10-30）

| 验收 | 实施与证据 | 关联 |
| --- | --- | --- |
| I1 同 key 连发 2 次 | 两次均 200 且返回同一条快照（id 5612），余额只 +10；流水 1、审计 1；SUCCESS TTL 86400s | D4 |
| I2 缺失 Idempotency-Key | 400 COMMON_VALIDATION_ERROR「缺少幂等键」 | D4 |
| I3 并发 5 个同 key | 1×200 + 4×409 POINTS_ADJUST_IN_PROGRESS；余额仅 +11，流水/审计各 1 | D4 |
| I4 业务失败释放 | amount=0 → 400，同 key Redis EXISTS=0；同 key 重试 200 成功 | D4 |
| I5 操作员隔离 | 键=points:adjust:idem:{operatorId}:{clientKey}；两名管理员同 key 各入账 1 次 | D4 |
| I6 TTL | PROCESSING 60s；SUCCESS 24h（86400s） | D4 |
| I7 真实 UI 双击 | 30ms 内按钮进入 loading+disabled，仅 1 次 POST；409 提示「调分处理中，请稍候」且弹窗保留 | D4 |
| I8 回归 | mvn test 138/138；PointsIntegrationTest 22/22；下单/兑换路径未改 | D8 |
| I9 前端检查 | vue-tsc --noEmit 0；vite build 0 | D8 |
| I10 文档 | 本节 | — |

> D 编号由规划方唯一分配，执行方不得自行新增或改序。本节仅映射既有编号；如需为“真实 UI 证据”等新增编号，由规划方分配后补录。
