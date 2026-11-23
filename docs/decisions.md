# Decisions

> 编号规则：D 编号由规划方唯一分配，执行方不得新增或改序。如需新增决策，只能追加新编号并标注“待规划方确认”。

## 决策序列（D1–D98）

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
- D63 行为变更影响面预演：任何改变既有行为的修复（删除判定/校验口径/统计口径等），改造前必须用现存数据跑一次只读预演，列出"新逻辑会拦·旧逻辑放行"与"旧逻辑拦·新逻辑放行"的差异清单，逐条解释原因；出现无法解释的误拦即停止。
- D64 既有约束不可破：新增判定的同时不得削弱既有保护（如 ref_count 快筛、ownership 校验、软删过滤），必须取并集，并在回归中给出旧保护仍生效的证据。
- D65 （规划方预留）
- D66 （规划方预留）
- D67 校验权威在业务层：凡已有业务错误码的字段，业务校验由领域层抛出业务码
- D68 Bean Validation 只做必填与防爆硬上限（业务上限 ×10）
- D69 前端必须按 code 分支，禁止依赖 message 文案
- D70 三字段口径：AI 600/6000、留言 1000/10000、评论 2000/20000（业务/硬上限）
- D71 新增字段业务码：GUESTBOOK_CONTENT_TOO_LONG、COMMENT_CONTENT_TOO_LONG
- D72 昵称/称号/reason 不新增业务码：DTO 上界=业务上界，超界 COMMON + field 明细
- D73 BindException / ConstraintViolationException 统一 400 + data.errors
- D74 三条校验异常路径与主 envelope 同构；legacy 路由不动
- D75 前端 SSE Accept 兼容 JSON 错误壳（text/event-stream, application/json）
- D76 错误码清单表落位 docs/api/error-codes.md
- D77 SSE 错误路径：校验/业务失败返回 400/401 + JSON 错误壳，不得 500
- D78 （规划方预留）
- D79 （规划方预留）
- D80 AI 统计口径统一排除软删会话：sessions 直接过滤；messages/tokens/latency/tool 经 EXISTS；feedback 经消息→会话关联
- D81 统计=列表一致性作为回归固定项（窄/宽/覆盖软删三区间对拍）
- D82 例外轮范围：仅允许修复 AI 统计口径（其余 backlog 不动）
- D83 （规划方预留）
- D84 （规划方预留）
- D85 （规划方预留）
- D86 （规划方预留）
- D87 （规划方预留）
- D88 （规划方预留）
- D89 （规划方预留）
- D90 （规划方预留）
- D91 （规划方预留）
- D92 （规划方预留）
- D93 （规划方预留）
- D94 （规划方预留）
- D95 （规划方预留）
- D96 文档权威性判定规则：以 docs/README.md「权威文档」表为唯一权威清单（现 12 份）；未登记 = 非权威；文档内显示名必须与链接目标一致

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

新增补录（旧文件无对应编号）：D16–D19、D27、D29、D31、D33、D36–D47、D49、D50、D54–D64、D67–D82。
全部条目已确认；编号由规划方唯一分配。
D63/D64 已按规划方原文逐字写入（行为变更预演 / 既有约束不可破）。

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

### T-014 错误码统一

| 项目 | 实施与证据 | 关联 |
| --- | --- | --- |
| 三字段硬上限 | AI 6000 / 留言 10000 / 评论 20000，超限 400 COMMON + errors | D68、D70 |
| 业务码 | 601→AI_MESSAGE_TOO_LONG；1001→GUESTBOOK_CONTENT_TOO_LONG；2001→COMMENT_CONTENT_TOO_LONG | D67、D71 |
| 昵称/称号/reason | DTO 上界=业务上界，31/51/256 均 400 COMMON + field | D72 |
| 异常同构 | BindException/ConstraintViolation 补 400 + data.errors | D73、D74 |
| 前端 | SSE Accept 兼容 JSON 错误壳，AI 超长显示业务文案（500→400） | D69、D75 |
| 文档 | docs/api/error-codes.md 清单表 | D76 |

### T-015 联合回归与收口

| 项目 | 结果 | 关联 |
| --- | --- | --- |
| 基线快照 | docs/baseline.md（分支拓扑 + 表行数 + 清理记录） | — |
| 数据清理 | 测试评论 1、留言 2 删除；流水/审计零删除；余额 299 | D42、D46 |
| 回归 A/B/C | A1/A3/A4/A5/A6、B1/B2、C1/C2/C3/C4 PASS | — |
| 回归 C5 | **FAIL**：stats.sessions=18，列表 total=17（软删会话未排除） | D41 |
| 回归 D–J | 未执行（P1 停止） | D48 |
| 归档 | docs/backlog.md（B01–B16）、docs/milestone-close.md | D77 |
| 收口结论 | 调试阶段保持开启，待 B16 修复后重跑 | D48、D77 |

### T-016 B16 修复与全量回归

| 项目 | 结果 | 关联 |
| --- | --- | --- |
| 统计口径 | sessions/messages/tokens/latency/tool/feedback 统一排除软删会话 | D80 |
| 一致性 | 窄/宽/覆盖软删 3 区间 stats=列表（7/7、17/17、2/2） | D81 |
| 构造验证 | 1 软删 + 1 正常 → 统计/列表均 +1（非 +2） | D81 |
| 回归 | A–J 全 PASS（D 通知/E 积分/F 上传/G 后台/H 权限/I 时区/J 构建） | — |
| 门禁 | 153/153；vue-tsc 0；vite build 0；控制台 0 error | — |
| 收口 | 调试阶段可关闭，合并待批 | D48、D82 |

- D97 历史文档一律移出仓库（2026-10-31 执行）：docs/archive 下 52 个历史文档打包到 backup/docs-archive-20261031.zip 并 git rm，仓库内只保留 docs/archive/README.md 说明页；找回方式 = `git checkout docs-archive-ref -- <path>`（tag 指向 e0a0c00）或解 zip。理由：过期文档留在工作区会被 grep 命中，导致按旧口径改代码。
- D98 线上在用的兼容协议文档例外：`modules/wechat` 的 13 条旧协议路由有真实小程序在跑，原 13-微信扫码复原计划已提升为权威文档 docs/16-小程序旧协议兼容.md，不得归档。
- 【待分配编号·待规划方确认】AI 最终回答真流式升级（2026-11-11）：OpenAiCompatChatClient 以 stream=true 调 OpenAI 兼容上游并逐行解析 SSE data 帧，ChatClient.stream 由「返回全部分片」改为增量回调（Consumer<String>）；mock 实现保持确定性分片，前端 SSE 协议（session→tool*→delta*→done|error）不变；done.usage 仍为估算值（真流式 usage 不随增量下发，保持既有行为）。消除 docs/archive/reports/11 登记的「伪流式」偏差。
- 【待分配编号·待规划方确认】前台体验口径（2026-11-11）：① AI 悬浮球常驻显示（滚动显隐判定为 bug）；② AI 配额徽标在 done 事件后静默刷新 GET /ai/quota；③ 设备会话列表超 5 台默认折叠（refresh token TTL 14 天，过期会话已由后端自动滤除，不显示已下线设备）；④ 首页与文章列表滚动到底自动加载下一页（IntersectionObserver，不支持时保留手动按钮）；⑤ 移动端 header 主导航与搜索折叠进抽屉（≤768px）。
- 【待分配编号·待规划方确认】AI 对话阶段时间线与防幻觉（2026-11-11，v1.6.10）：① SSE 新增 phase 事件（understanding/searching/composing，start 语义，序列 session→phase*→(tool)*→phase(composing)→delta*→done|error）；② done 新增可选 articles 载荷（本轮工具命中的 {slug,title}≤5，前端渲染可点击相关文章链接——slug 来自工具结果不靠模型生成，规避编造）；③ system prompt v2 禁止编造文章名/数字（修复「后端开发分类 2 篇文章」幻觉：list_categories 只返回计数，文章名系模型编造）。
- 【待分配编号·待规划方确认】AI 时间线呈现与历史回看（2026-11-11 第二批，v1.6.10）：① 步骤条改横向排布（flex row wrap，节点左→右）并与气泡留呼吸距；② R109 历史消息接口新增可选 includeTool（默认 false 向后兼容，测试断言同轮更新），前端恢复最近 5 条工具步骤挂到其后助手消息（默认折叠）；phase 阶段事件不落库，历史不恢复三阶段；③ 助手正文接入 MdPreview（md-editor-v3 既有依赖）markdown 渲染，站内相对链接 SPA 跳转（域名无关）、外链新窗口；④ 审核中（PENDING）评论不渲染点赞/回复/删除操作（后端本就拒绝，前端隐藏为最简方案），「审核中」标签保留。
- 【待分配编号·待规划方确认】后台待办角标（2026-11-11 第三批，v1.6.10）：① R119 overview 新增 pendingComments/pendingGuestbooks/pendingOrders 三字段（非破坏；评论/留言排除软删，订单按 status=PENDING 无软删列）；② 侧栏「评论管理/留言审核/订单管理」菜单 $danger 红色角标（>99 折叠 99+，菜单折叠态缩为小红点）；③ 角标与仪表盘「待办处理」统计卡共用同一 overview 响应（前端 todoBadges store：AdminLayout 进入后台刷新 30s 新鲜窗 + DashboardView 回写，不为角标单独请求）；④ 仅 ADMIN 发起角标请求（overview 为 ADMIN 权限，EDITOR 侧栏本无留言/订单菜单，评论角标在 EDITOR 视角暂缺——随权限现状，未放宽鉴权）。
- 【待分配编号·待规划方确认】列表封面缩略图展示接线（2026-11-16）：① `ArticleListItemVO`（feed/搜索/置顶/热门/我的/后台列表共用）新增可选字段 `coverThumbKey`（非破坏，加在 coverKey 之后），值 = `files.thumb_key`（仅 status=ACTIVE 且已生成；`FileAppService` 新增按 object_key 批量 IN 查询，content→media 走 app 层符合架构规则「模块间仅依赖对方 app 层/domain 事件」）；② 前端卡片封面优先取 `coverThumbKey`，null 回退原图 `coverKey`（降级链路既有，@error 兜底不变）；③ 文章详情 hero 与正文保持原图不动（banner 1200w 独立规格另行立项）；④ 缩略图规格由代码默认 320 提为环境变量 `FILE_THUMB_LONGEST`（application.yml 映射 `wzblog.file.thumb-longest`，默认仍 320），本地 .env 设 800 覆盖 2x 卡片显示；存量 ACTIVE 图片按新规格重新生成（回填报备见 AI-RULES 数据修正记录表）。
- 【待分配编号·待规划方确认】用户侧称号展示与积分/管理端 UI 优化（2026-11-20）：① **称号徽标数据面**：`UserBrief` 新增 `activeTitle`（佩戴称号名），identity 新增 `TitleNameMapper`（titles 表只读名称解析，identity 不依赖 points 写侧，无模块循环依赖）；content `AuthorVO` 与 interaction `AuthorVO` 各加可空 `activeTitle` 并在装配点透传（ArticleAssembler/CommentAppService/GuestbookAppService/NotificationAppService/ArticleReadService 修订编辑者）；前端评论区作者旁、文章详情作者行显示称号徽标（游客/未佩戴不显示）；**视觉定稿（负责人否决初版 el-tag 换行展示后按 impeccable polish 重构）**：自定义浅青竹底胶囊（`$accent-soft` 底 + `$primary-dark` 字，对比 4.56:1）**与昵称同行**，评论区与详情页同款（G12）；② **积分流水类型中文化**：前端 `orderMeta.pointsTypeLabel`（后端 PointsType 9 枚举映射）用于流水 chip 与筛选下拉（value 仍为枚举），后端 `reverse` 的 remark 由「枚举名+ 反转」改为中文标签+「撤回」（存量 remark 不改，R14）；③ **订单弹窗**：下单/详情弹窗 `append-to-body`（挂 body 避免父容器高度裁剪），用户侧订单详情**移除操作日志区块**（管理动作记录对普通用户无意义，接口字段保留）；④ **管理端文章表格**：置顶开关保留独立列（第一轮并入操作列后被负责人推翻——开关无列头语义不明，定稿为「置顶」列头独立列紧邻操作列），置顶行视觉（行微主色底+标题加粗+「置顶」warning 标签，仅常规态）；⑤ 回归：称号数据面 `feedAuthorCarriesActiveTitle` 用例；契约 AuthorVO/InteractionAuthorVO 双 schema 已含 activeTitle（工具内手工建模段同步）。
- 【待分配编号·待规划方确认】文章治理通知携带文章标题（2026-11-20）：① `ArticleModeratedEvent` 新增 `articleTitle` 字段，4 个发布点（softDelete/restore/purge/takeover）传文章标题；② `NotificationEventListener.onArticleModerated` 通知正文改为 `《文章标题》[ 原因：xxx]`——**标题超 40 字截断加省略号**（notifications.content 上限 500，截断后远低于上限）；③ 通知 title 保持动作文案不变，前端 NotificationList 已渲染 title+content，前端零改动；④ 口径：文章接管通知的标题取接管时点标题（事件发出时的快照）；管理员对自己文章的操作不通知（authorId==moderatorId 既有口径不变）；⑤ 回归：takeover 集成测试新增「通知 content 含文章标题」断言。
- 【待分配编号·待规划方确认】用户侧/管理端 UX 批量优化八项（2026-11-20）：① 积分商城卡片新增「兑换」按钮（点击与整卡同效），实物库存≤0 时按钮禁用显示「无货」+ 图片区半透明遮罩大字标识，卡片点击同步禁用；② 轮播「积分商城上新」跳转失效根因 = banner link_url `/points/goods` 无对应路由（portal 仅 `/points`）——**数据修正**（管理接口改 link_url 为 `/points`，走审计），并给 PointsCenterView 新增 `?tab=goods|orders|ledger` 深链（轮播等入口直达页签）；③ **管理端 11 视图 13 个 el-dialog 统一 append-to-body**（弹窗挂 body，根治「编辑弹窗被表格遮一半」类问题，布局 el-main 本就是 flex 拉伸+独立滚动无需改动）；④ 站点配置面板：表格字号对齐全局 15px（局部类，R27 不动 element.scss）、类型列中文映射（文本/数字/开关/JSON 结构）+ 列宽自适应、新增「保存全部」按钮（循环逐项 PUT 独立请求，校验失败整批中止，成功/失败汇总提示；无契约变化）；⑤ 审计日志动作中文映射（AuditAction 19 枚举全集，tag 展示中文、title 悬停保留原值、筛选仍输英文枚举）；⑥ 称号管理清理 3 个调试称号（9001 站长/9002 调试体验官/9003 先行者，走删除接口软删+审计）并修复 9015 青竹站主 description 双重编码（管道插库未声明 utf8mb4 所致，HEX 复核已恢复）；⑦ 管理端积分流水类型列中文映射（与用户侧 orderMeta 单源化至 `utils/pointsLabel.ts`）；⑧ 管理端文章管理表格列宽语义修正：短列固定 width 一行展示、仅标题 min-width 弹性单行省略（悬停 title 全称）、置顶列恢复独立列紧邻操作列（列头「置顶」语义清晰）、置顶行微主色底+加粗+标签。
- 【待分配编号·待规划方确认】编辑器全屏模式与编辑区扩容（2026-11-20）：① `MarkdownEditor`（md-editor-v3 业务封装）工具栏新增内置 **`fullscreen`** 项（浏览器全屏：fixed 铺满视口、ESC 退出；分屏下左编辑右预览自动拉宽）——注意 md-editor-v3 v5 的 `'='` 是 **Footers 页脚分隔符**而非工具栏全屏项，勿混淆；② 全屏态局部样式贴合设计系统（去圆角/底色 `$bg-container`）；③ 编辑页编辑器高度由默认 60vh 改为 `calc(100vh - 300px)`（视口自适应，消除「编辑区憋屈」）；④ 验证口径：md-editor-v3 全屏态在 IAB 截图上存在视觉伪影（fixed 元素+截图机制），**以 DOM 断言为准**（fullscreenActive/覆盖视口/分屏可见三项断言），非截图目测。
- 【待分配编号·待规划方确认】回收站 purge 根因修复与预览/批量删除（2026-11-19）：① **purge 根因**：InnoDB 对「DELETE articles 级联删 comments 子行 + comments 自引用 FK（parent_id/root_id）ON DELETE SET NULL」的组合抛 1452（Cannot add or update a child row），含楼中楼回复的文章必现 500 且事务回滚（无回复的文章不受影响，故旧测试未覆盖）；修复 = `ArticleMapper.purge` 改**显式分步删除**（comments→article_tags→article_revisions→article_reactions→article_daily_stats→articles，先子后主消除级联歧义），回归用例 `purgeArticleWithNestedCommentsSucceeds` 锁行为；② **回收站预览**：公开详情接口 ADMIN 兜底——`selectBySlug` 查不到且当前用户为 ADMIN 时经 `selectBySlugAny` 读软删文章并**跳过缓存写入**（软删态是过渡态），普通用户/匿名行为不变（仍 404）；前端文章管理列表（常规+回收站全部状态）标题统一渲染为新标签页链接（`/articles/{slug}`），常规态便于快速查看内容；③ **批量删除**：新增 `POST /admin/articles/purge-batch`（body `{ids:[]}`，@NotEmpty @Size(max=1000)），**逐篇独立事务**（Controller 循环调 writeService.purge 走代理，单篇 BusinessException 捕获进 failed 清单，不影响其余），返回 `{succeeded:[], failed:[{id,code}]}`；前端回收站多选 + 批量按钮；④ 无新增错误码（failed.code 复用既有 ARTICLE_NOT_FOUND/ARTICLE_PURGE_NOT_ALLOWED 等）。
- 【待分配编号·待规划方确认】提交前最终回归审计修复（2026-11-20）：对 2026-11-16～11-20 全部未提交改动做 0→1 副作用审计，修复 2 处：① `ArticleCard` 缩略图回退链补全——原实现只对 `coverThumbKey` 为 null 回退原图，**thumb 加载失败（@error）时停留在裂图**；新增 `thumbFailed` 状态，thumb @error → 重试原图 coverKey → 原图也失败才落文字封面，watch 依赖补 `coverThumbKey`；② 回收站置顶开关对软删行禁用——后端 pin 走 `requireExisting`（排除软删，软删行 pin 返回 404），前端开关 `:disabled` 补 `deleted` 条件对齐防护（回收站预览态只读语义）。验证：后端全量 `mvn test` 173/173 绿（1 skipped=回填手动工具）、前端 vue-tsc 0 error + vite build 通过、IAB 走查回收站/商城/详情/审计页。
- 【待分配编号·待规划方确认】后台文章列表默认排序与门户对齐（2026-11-23）：负责人口径「文章管理和首页的文章加载顺序必须一致，方便编辑时快速定位」。**根因（契约缺口）**：门户信息流 R022 固定 `publishedAt DESC, id DESC` 且置顶走独立区块在前（R18/R24），后台列表 R044 契约从未规定排序——mapper 默认（sort 不传）`updated_at DESC` 且无置顶优先，导致 ①编辑旧文后台跳顶而首页不动 ②置顶文章后台混排。**新口径**：`GET /admin/articles` 第一排序键固定 `is_pinned DESC`（置顶优先，与门户置顶区块呼应）；默认排序键 `publishedAt DESC, id DESC`（与门户信息流同序——管理列表非置顶部分 = 首页从上到下的顺序）；显式 `sort∈{createdAt,publishedAt,updatedAt}` 可切换（置顶优先恒为第一键）；`published_at IS NULL`（草稿）沉底、同 NULL 按 `id DESC`（筛选 DRAFT 时即创建倒序）。**变更性质**：破坏性默认值变更（updatedAt→publishedAt），调用方排查 = 前端 api/article.ts 不传 sort、小程序 legacy 层无 admin 文章接口，影响面 0。同步：04-接口契约.md §5.3 排序白名单 + 路由表 R044 行；同源清单：「我的文章」selectMe（门户个人列表，updated_at DESC）维持现状不动——其场景是「找最近编辑」，与首页一致性诉求无关。
- 【待分配编号·待规划方确认】微信绑定为全员可选增强（2026-11-24，B18 落地）：**负责人裁决**——创作者强制绑定小程序暂不实施（原 B18 强制口径搁置），改为全员可选增强；游客双账号并存维持现状（零改动）。**契约四点**（04 路由表 R149/R150 + openapi 同步）：① 轮询响应 `QrStatusVO` CONFIRMED 新增可选 `bindToken`（60s 一次性，Redis 镜像仅会话创建者可见）；② 新增 `POST /auth/wechat/bind-by-token`（USER，消费一次性 bindToken → 复用 `bindWechat`：openid 属扫码自动账号/已删账号时匿名化注销并移交——2026-10-31 数据修正语义的自动化）；③ 新增 `POST /auth/wechat/bind-migrate`（USER=扫码自动账号）：目标账号账密 + 图形验证码 + 复用登录失败锁定（5 次/15min）→ 自动账号匿名化注销、**评论归属与回复目标改挂目标账号（含软删行，一条归属一致性口径）**、撤销临时账号全部刷新令牌（RevokeReason=ADMIN）→ 返回目标账号双 Token；④ `/users/me` `UserSelfVO` 新增可选 `openidBound`（登录后提示绑定用）。**新错误码**：`WECHAT_TARGET_ALREADY_BOUND`(409)、`WECHAT_MIGRATE_SOURCE_INVALID`(409)。**前端判定口径**：扫码登录落在临时账号以「`wx_` 用户名前缀 + USER 角色」识别（自动账号 openidBound 恒 true，不能作判定）；密码登录账号 `openidBound=false` 时每次登录提示绑定一次（可跳过）；个人中心新增「微信绑定」页签（已绑/未绑状态 + 扫码绑定入口；临时账号显示移交入口）。**小程序零改动**（confirm 契约未动）。验证：`WechatBindWebIntegrationTest` 6 用例（A/B 全链路 + 4 失败路径）+ `WechatQrIntegrationTest` 9 用例回归全绿。
- 【待分配编号·待规划方确认】契约工具验证码登录与调试返码（2026-11-24，B19 机制修复）：登录接口启用图形验证码后 `capture_samples.py` 无法自动登录。**新口径**：`CaptchaVO` 新增可选 `code` 字段——仅当 `wzblog.auth.log-verification-code=true`（WZBLOG_LOG_VERIFICATION_CODE，本地调试开关、生产恒 false）时随响应返回明文，供契约工具自动化登录；工具脚本登录前先取 `/auth/captcha` 的 code 随表单上送（不读日志文件——本机日志仅控制台无落盘）。管理员凭据改读 `backend/.env` 的 `WZBLOG_ADMIN_*`（bootstrap 账号随本机而异；不写死不入样例）。**实测**：三账号登录样例全量刷新（editor/reader 密码仍为种子 `Passw0rd1`；debug_admin 密码已非种子值，历史遗留不追溯）；openapi 145 paths（143+R149/R150），`openapi_spec_validator` 通过，diff 仅契约增量无破坏。
