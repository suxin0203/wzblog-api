# AI-RULES｜本项目 AI 协作规范（Skill 入口）

> **触发方式**：任何改代码 / 查问题 / 改文档 / 修数据的请求，先 `@docs/AI-RULES.md` 关联本文件，再描述需求。
> **本文件地位**：工程协作约定。与业务口径冲突 → 以 `docs/decisions.md` 为准；与代码冲突 → 以「代码 + 测试」为准，并回报差异。
> **版本**：v2.2 ｜ 2026-10-31 ｜ 基线 commit `0527ae6` ｜ 维护人：项目负责人
>
> **v2.2 变更**：新增 §0.5 路径地图、§6.5 视觉与 UI 规则（R25–R30）、G9–G12 与 G0 视觉门槛；
> 明确「UI 技能可用，但视觉 token 必须落回 `variables.scss`」。

---

## 目录

- [0. 开工前 30 秒](#0-开工前-30-秒)
- [0.5 路径地图（改文件前先定位）](#05-路径地图改文件前先定位不许猜路径)
- [1. 项目事实](#1-项目事实不许猜以此为准)
- [2. 三条最高原则 + 三档严格度](#2-三条最高原则--三档严格度)
- [3. 任务分类与决策树](#3-任务分类与决策树)
- [4. 各类任务 SOP（A–L）](#4-各类任务-sop-al)
- [5. 契约落点矩阵](#5-契约落点矩阵改动--必须同步)
- [6. 项目红线（分域 30 条）](#6-项目红线踩过的坑)
- [7. 故障模式库（症状 → 原因 → 查法）](#7-故障模式库)
- [8. 证据标准](#8-证据标准什么算-pass)
- [9. 命令速查](#9-命令速查)
- [10. 提交与台账规则](#10-提交与台账规则)
- [11. 标准回传格式](#11-标准回传格式)
- [12. 提交前自检清单](#12-提交前自检清单)
- [13. 需求速查大表](#13-需求速查大表)
- [14. 反模式（AI 常犯错）](#14-反模式ai-常犯错)
- [15. 本文件维护](#15-本文件的维护)

---

## 0. 开工前 30 秒

| 顺序 | 动作 | 目的 |
| --- | --- | --- |
| 1 | 读 `docs/README.md` | 分清权威文档与历史归档（`archive/` 一律不作为依据） |
| 2 | **grep `docs/decisions.md` 关键词** | 口径台账已有 D1–D98 结论，禁止重新发明 |
| 3 | 读 `docs/backlog.md` | B01–B16 是已知未修项，不要当新发现汇报 |
| 4 | 环境自检（见 §9.1） | health 200 才开工；Docker 掉过不止一次 |
| 5 | 判断任务类型（§3） | 类型错了，方法全错 |
| 6 | **改前端时**：读 `src/assets/styles/variables.scss`，并检查会话里有没有 UI/设计类技能 | 视觉基线 + 技能优先级见 §6.5（R25–R30） |

---

## 0.5 路径地图（改文件前先定位，不许猜路径）

### 两个仓库的绝对位置：同级兄弟目录，互不包含

| 仓库 | 绝对路径 | 说明 |
| --- | --- | --- |
| 后端 | `E:\MyCodeProject\Java\文栈博客后端AI重构数据库版本` | Spring Boot，端口 8021 |
| 前端 | `E:\MyCodeProject\Java\wzblog-frontend` | Vue 3 + Vite，端口 5173 |
| 备份/归档 | `E:\MyCodeProject\Java\backup` | 数据库备份、文档归档 zip（**不属于任何仓库**） |

> **前端不在后端目录里。** 改后端时工作目录是 `文栈博客后端AI重构数据库版本`，改前端时是 `wzblog-frontend`，两者不能混用同一路径。

### 后端：改什么 → 去哪个包

| 你要改的东西 | 目录（相对 `src/main/java/com/wzblog/`） | 典型文件 |
| --- | --- | --- |
| 文章 / 列表 / 搜索 / 归档 / 置顶 | `modules/content/` | `api/ArticleController.java`、`app/ArticleReadService.java`、`infra/ArticleMapper.xml` |
| 评论 / 留言 / 通知(SSE) | `modules/interaction/` | `api/CommentController.java`、`api/GuestbookController.java`、`api/NotificationController.java` |
| 积分 / 调分 / 订单 | `modules/points/` | `api/AdminPointsController.java`、`app/PointsQueryService.java` |
| 上传 / 文件 | `modules/media/` | `api/FileController.java`、`app/FileAppService.java` |
| AI 对话 / 统计 | `modules/ai/` | `api/AiChatController.java`、`app/AiChatOrchestrator.java`、`app/AiSessionAppService.java` |
| 后台运营（轮播/友链/称号/站点配置） | `modules/admin/` | `api/OperationsController.java`、`api/AdminTitleController.java`、`app/OperationsAppService.java` |
| 登录 / 用户 / 权限 | `modules/identity/` + `config/` | `config/SecurityConfig.java`、`common/security/` |
| 小程序旧协议（**别动**） | `modules/wechat/` | 兼容层：HTTP 200 + 数字 code |
| 统一响应 / 异常 / 错误码 | `common/api/`、`common/error/` | `Result.java`、`GlobalExceptionHandler.java`、`ErrorCode.java` |
| 请求 DTO（校验注解在这） | 各模块 `api/` | `*Request.java`、`*UpsertRequest.java` |
| SQL | 各模块 `infra/*.xml` | 改查询优先看 XML，不要只改注解 |
| 数据库迁移 | `db/migration/V*.sql` | **只能新增，禁改已执行的历史脚本** |
| 配置 | `src/main/resources/application.yml`、`application-local.yml` | local 里**禁止写密钥/口令** |

### 前端：改什么 → 文件在哪（根目录 `E:\MyCodeProject\Java\wzblog-frontend`）

| 你要改的东西 | 路径 |
| --- | --- |
| 接口调用 | `src/api/*.ts`（按域拆分，清单见下） |
| 页面 | `src/views/`（前台浏览端与后台管理端混放，靠文件名区分） |
| 组件 | `src/components/` |
| 状态 | `src/store/`（Pinia） |
| 路由与守卫 | `src/router/`、`src/router/guards.ts` |
| 时间处理（**唯一入口**） | `src/utils/datetime.ts` |
| 类型定义 | `src/types/` |
| SSE / hooks | `src/hooks/`（`useNotificationStream.ts`） |

`src/api/` 文件清单：`article.ts`、`comment.ts`、`guestbook.ts`、`aiChat.ts`、`auth.ts`、`user.ts`、`notification.ts`、`points.ts`、`adminPoints.ts`、`operation.ts`、`category.ts`、`tag.ts`、`dashboard.ts`、`upload.ts`、`system.ts`、`reaction.ts`

### 关键锚点（高频改动直接去这几处）

| 需求 | 后端 | 前端 |
| --- | --- | --- |
| 列表返回字段变了 | `content/domain/ArticleListItemVO.java` + `ArticleAssembler.java` | `src/types/article.ts` + `src/views/ArticleListView.vue` |
| 校验上限变了 | `*/api/*Request.java` + 对应 `app/*Service.java` | 表单 `maxlength` 与错误提示 |
| 时间筛选 / 投放期 | mapper XML 的 `>= from AND < to` | 调 `toUtcRangeEnd` / `toUtcInstantEnd` |
| 错误码 / 文案 | `common/error/ErrorCode.java` | 按 `code` 分支的地方 |
| 置顶展示 | `/articles/pinned` + `ArticleMapper.selectPinned` | `ArticleListView.vue` 顶部置顶区 |

### 一条铁律

> **不确定文件在哪，先 grep 类名/方法名/接口名，不要凭猜猜路径。**
> `grep -rn "class ArticleController" src/main/java` ／ `grep -rn "getArticleFeed" src/`
> 猜错路径 = 改错文件，这是最难发现的一类错误。

---

## 1. 项目事实（不许猜，以此为准）

### 1.1 后端

| 项 | 值 |
| --- | --- |
| 技术栈 | Spring Boot 3 + Java 21 + Maven；MyBatis（注解 + XML）；MySQL 8；Redis；Spring Security + JWT；SSE |
| 包结构 | `com.wzblog` ｜ 业务模块 `modules/{identity,content,interaction,points,media,ai,admin,wechat,platform}` ｜ 横切 `common/{api,error,lock,media,profile,ratelimit,security,util,web}`、`config` |
| 模块内分层 | `api`（Controller/DTO）→ `app`（应用服务）→ `domain` → `infra`（Mapper/外部） |
| 端口 / profile | **8021** / `local`；本地配置 `application-local.yml`（**禁止写入密钥/口令**，用环境变量） |
| 健康检查 | `GET /actuator/health` → `UP` |
| 迁移 | Flyway 版本化脚本（`db/` 与 `src/main/resources`），`V1__schema.sql` 为基线；**禁止修改已执行的历史脚本** |
| 测试 | `mvn test`（集成测试走 Testcontainers MySQL）；基线 **153 通过**；另有 ArchUnit 架构测试 |
| 契约导出 | springdoc → `/v3/api-docs`；产物 `docs/api/openapi.yaml`（**禁止手改**，改代码后重新导出） |
| 遗留兼容层 | `modules/wechat` = 旧协议兼容（HTTP 200 + 数字 code）；**不要拿它当新接口范例** |

### 1.2 前端

| 项 | 值 |
| --- | --- |
| 技术栈 | Vue 3 + TypeScript + Vite 6 + Element Plus + Pinia + vue-router + dayjs + axios + md-editor-v3 |
| 目录 | `src/{api,components,views,store,router,hooks,utils,layouts,types,directives,assets}` |
| 端口 / 命令 | **5173** / `npm run dev`、`npm run build`（= `vue-tsc --noEmit && vite build`）、`npm run lint` |
| 时间工具 | `src/utils/datetime.ts`：`toUtcIso` / `toUtcRangeEnd` / `toUtcInstantEnd` / `fromUtcInstantEnd` / `toLocalDisplay(utc, precision)` / `toLocalInput` |
| 请求层 | `src/api/*.ts` 按域拆分；统一拦截器在请求层（含 token 刷新单飞 `refreshOnce`） |
| 错误处理 | 按业务 `code` 分支；`COMMON_VALIDATION_ERROR` → 表单字段 errors |
| **样式系统** | `src/assets/styles/variables.scss`（设计系统 v2「纸面·青竹」：主色 `#099268`、暖纸底 `#f7f7f4`；由 vite `additionalData` 全局注入，组件直接用 `$xxx`）。改任何视觉先看这里 |

### 1.3 仓库与分支

| 仓库 | 分支 | 远端 | 注意 |
| --- | --- | --- | --- |
| 后端 `文栈博客后端AI重构数据库版本` | `master`（`feat/integration-q1` 已合并） | 有 `origin` | tag：`v0.1.0-debug-close`、`pre-doc-slim` 等 |
| 前端 `wzblog-frontend` | `master` | **无远端** | **没有异地备份**：禁止 `git clean -fdx`、禁止删目录；重大改动前打本地 tag |

---

## 2. 三条最高原则 + 三档严格度

| # | 原则 | 含义 |
| --- | --- | --- |
| **P1** | 先定位，后改动 | 动手前先给「只读定位结论」：文件:行、数据流、根因、影响面。定位阶段禁止改文件 |
| **P2** | 口径先于实现 | 行为争议先在 `decisions.md` 落一条（编号由负责人分配），再改代码 |
| **P3** | 证据说话 | 只认四类证据：代码 grep / SQL 结果 / Network 抓包 / DB 实际值 |

| 严格度 | 适用 | 必做 | 可省 |
| --- | --- | --- | --- |
| **L1 速查** | 只问"为什么这样/在哪" | 只读、给证据、给方案 | 不改代码、不跑测试 |
| **L2 单点修复** | 1–2 文件、无契约变化 | 定位 + 最小修复 + 双向证据 + 回归用例 | 契约文档同步（无字段变化时） |
| **L3 完整变更** | 跨端 / 有契约变化 / 涉及数据 | A 类全套 SOP + 契约文档 + 台账 + 字段对照表 | 无 |

> 拿不准用哪档 → **按高的走**。

---

## 3. 任务分类与决策树

| 类型 | 名称 | 典型触发 |
| --- | --- | --- |
| **A** | 新增接口 / 新增字段 | "加个接口""返回里再加个字段" |
| **B** | 修改既有接口 | "字段改名""状态码改一下""加个参数" |
| **C** | 修 bug | "这里报错""数据不对""页面崩了" |
| **D** | 数据修正 | "这条数据错了""余额对不上" |
| **E** | 性能优化 | "太慢""卡" |
| **F** | 安全 / 权限 | "越权""谁能访问""密码相关" |
| **G** | 前端专项 | 渲染、状态、路由、样式、构建、打包 |
| **H** | 文档 / 契约治理 | 文档同步、契约对齐、归档 |
| **I** | 重构 / 技术债 | "这段太乱""抽一下""删死代码" |
| **J** | 环境 / 部署 / 运维 | 起不来、端口占用、Docker、日志 |
| **K** | 诊断 / 答疑（只读） | "为什么这里 8 小时""这个字段干啥的" |
| **L** | 回滚 / 事故处理 | "上线出问题了""把这次改动退回去" |

### 决策树

```
需求进来
├─ 不改代码，只问原因/位置？ ───────────────→ K（只读诊断）
├─ 要改返回字段 / 路由 / 参数？ ─────────────→ A 或 B（看是新增还是修改）
├─ 只是数据本身错（代码逻辑没错）？ ─────────→ D（数据修正，禁 UPDATE 余额/删审计）
├─ 行为有争议（"到底该不该包含置顶"）？ ────→ 先落 decisions 再定 A/B/C
├─ 功能正常但慢/占资源？ ───────────────────→ E
├─ 跟"谁能做什么"有关？ ───────────────────→ F
├─ 只动前端（渲染/状态/路由/样式）？ ───────→ G（若涉字段，先确认后端已对齐）
├─ 只动文档/契约？ ─────────────────────────→ H
├─ 不改行为只改结构？ ──────────────────────→ I（必须先证明测试覆盖够）
├─ 环境/起不来/端口/Docker？ ───────────────→ J
└─ 要退回某次改动？ ────────────────────────→ L
```

### 必须停下来回报的情况（阻断清单）

```
1) 发现 P0：功能中断 / 数据丢失 / 资金错账 / 权限绕过
2) 需要改表结构 / 加索引 / 改历史迁移脚本
3) 需要删除或批量修改生产数据
4) 需要新增依赖（后端 pom / 前端 package.json）
5) 需要改鉴权、白名单、token 策略
6) 发现契约冲突（文档说 A，代码做 B），且无法判断哪个对
7) 需要动前端 git（无远端，任何 reset/clean/rebase 都高风险）
8) 任务描述与 decisions.md 已有结论冲突
```

---

## 4. 各类任务 SOP（A–L）

### A｜新增接口 / 新增字段（L3）

```
A1 契约先行（禁止跳过）
   - 产出「契约变更清单」：路由 / 方法 / 请求字段（名·类型·必填·上限）/ 响应字段 /
     状态码 / 业务错误码 / 分页·排序 / 幂等·SSE 行为
   - 等负责人确认后再写后端
A2 同步文档（缺一不算完成）
   - 接口 → docs/api/接口对接文档.md
   - 上限与业务码 → docs/api/error-codes.md
   - 行为口径 → docs/decisions.md（编号由负责人分配）
   - 表变更 → docs/03-数据库设计.md + 新迁移脚本
A3 后端：Controller(DTO 校验) → App(业务校验+业务码) → Domain → Infra
   测试：正常路径 + ≥3 条失败/边界（缺参、超上限、超硬上限、无权限、不存在）
A4 前端：types 定义 + api 函数 + 页面三态；错误走 code 分支
A5 交付「字段对照表」：后端字段 → 前端字段 → 是否一致（不一致=未完成）
A6 权限：确认注解/角色正确；未登录 401、越权 403 各验一次
```

### B｜修改既有接口（L3）

```
B1 先分类：非破坏性（加可选字段/加新接口）还是破坏性（改名/改语义/改状态码）
B2 破坏性变更必须：
   - 列出所有调用方（前端 api 文件、其他后端模块、外部/小程序）
   - 给出兼容期方案（保留旧字段一个版本 / 双写）
   - 明确告知负责人影响面
B3 同步 api/接口对接文档.md 的「变更记录」与「版本号」
B4 前端同轮改；旧字段若保留，标注 @deprecated 与移除条件
B5 回归：旧调用方路径必须仍可用（或明确已下线）
```

### C｜修 bug（L2/L3）

```
C1 先定域：前端崩 / 接口 5xx / 接口 4xx 语义错 / 数据不对 / 时序并发 / 只在生产出现
C2 根因链：触发条件 → 代码路径（文件:行）→ 失效原因。找不到根因不许改
C3 最小修复：只改那一处，禁止顺手调整同文件其他逻辑
C4 双向证据：改前复现（报错/断言/截图）+ 改后同路径成功
C5 回归用例：原始复现路径 + 相邻边界（±1 单位）
C6 根因是两端契约不一致 → 转 A2/A5，禁止只在一侧打补丁
C7 输出「同源风险清单」：同类问题还可能在哪，逐条说明有无
```

**修 bug 的四个必查方向**（按命中率排序）

| 方向 | 检查点 |
| --- | --- |
| 契约 | 字段名/类型/可空性是否两端一致；VO 是否缺字段；列表与详情返回结构是否不同 |
| 口径 | 统计与列表是否同源；软删/置顶/状态过滤是否一致；时间区间是闭还是半开 |
| 时序 | 并发请求序号是否互相覆盖；`finally` 是否被守卫条件挡住；SSE 连接是否泄漏 |
| 数据 | 存量数据是否是旧语义写入（时区/口径切换前后的历史行） |

### D｜数据修正（L3，最危险）

```
D1 先备份：mysqldump 单表 / 导出 INSERT 语句，校验条数一致
D2 只允许「显式 id 白名单」修正；禁止 WHERE 1、禁止按时间范围批量改、禁止 UPDATE 余额
D3 生成反向脚本（−1s / +8h 之类对称操作），语法自检
D4 执行 → 回查 before/after 对照表（逐字段）→ 任一不符立即反向回滚
D5 资金类只能用「反向业务操作」回退（走同一接口 + 新幂等键），审计与流水永不删除
D6 报备：写进 docs/decisions.md 的「数据修正记录」，注明原因、范围、回滚方式
```

### E｜性能优化（L3，先量化）

```
E1 先量化：接口耗时（毫秒）、SQL 执行计划、行数、QPS 假设。没有数字不优化
E2 定位顺序：N+1 查询 → 缺索引 → 全表扫描（LIKE '%x%'）→ 大对象序列化 → 前端重复请求
E3 加索引必须：说明选择性、给出 EXPLAIN 前后对比、写进 03-数据库设计.md
E4 禁止：为了性能改业务语义、加缓存掩盖数据不一致、提前优化（无数据支撑）
E5 交付：优化前后对照表（耗时/扫描行数），并说明天花板在哪
```

### F｜安全 / 权限（L3）

```
F1 越权三查：接口是否校验资源归属（IDOR）、角色注解是否正确、前端隐藏是否只是"看不见"
F2 认证：token 过期/刷新/登出/多端登录策略；401 与 403 语义不得混用
F3 输入：SQL 一律参数绑定；LIKE 必须转义 % _ \；富文本/markdown 输出需转义
F4 敏感数据：日志不得打印 token/密码/手机号；接口不返回 password 字段
F5 幂等与限流：写操作幂等键；公开接口限流（已有 sliding window 实现可复用）
F6 任何放宽鉴权/白名单的改动 = 阻断项，先回报
```

### G｜前端专项（L2/L3）

```
G1 渲染崩溃：先查数据来源 —— VO 字段是否可能缺失/null？模板是否直接读深层属性？
   （历史坑：order.logs.length、数组未判空、可选链缺失）
G2 状态不同步：分清"单一数据源" —— 同一状态是否被两处各自维护（顶栏铃铛 vs 消息中心）
G3 加载态卡死：并发请求序号 + finally 必须无条件复位 loading
G4 路由/守卫：redirect 参数必须排除 /login /register，防自循环
G5 时间显示：只用 utils/datetime.ts，禁止页面内手写格式化或 +8
G6 错误提示：按 code 分支；必须有失败兜底（catch），不留 unhandled rejection
G7 资源回收：blob URL、EventSource、定时器在替换/卸载时释放
G8 构建：`npm run build` 必须过（含 vue-tsc）；类型断言 `as any` 不允许新增
G9 视觉改动先探测 UI 技能：会话里若存在 UI / 设计 / 样式类技能（命名可能是 ui、design、
   taste、frontend-style 等），先加载它再动手；技能只提供"怎么做"的方法，不提供"用哪套配色"。
G10 视觉基线唯一来源 = src/assets/styles/variables.scss（设计系统 v2「纸面·青竹」）：
   颜色/字号/间距/圆角/阴影/动效一律用该文件里的 $xxx 变量（已由 vite additionalData 全局注入），
   组件内禁止写死色值、禁止自定义一套间距/圆角。
G11 .taste-skill/direction.json 属历史技能产出（accent-ladder / 覆盆子 #b8336a / DM Sans），
   与现行青竹绿基线 #099268 不一致 —— 只能当参考，禁止据此改主题或换配色。
G12 视觉一致性验收：改完任选 3 个同类页面比对同一元素（按钮/表格/标签/卡片）是否同款；
   给出截图或 DOM 结构对比，不接受"看起来一致"。
```

**G0 视觉变更的额外门槛（改样式/布局/组件外观时强制）**

```
1) 先读 src/assets/styles/variables.scss + element.scss，确认要用的 token 已存在
2) 技能若给出新方向（新配色/新字体/新密度）→ 先输出「差异清单」（色值/字号/圆角/间距/字体），
   说明影响哪些页面，等确认后再动；不得整页重写、不得改全局主题变量
3) 新增样式只能新增"局部类"，不得改 element.scss 的全局覆盖
4) 三条现行禁令（来自设计系统 v2 房规）：
   - 禁渐变背景 / 渐变按钮
   - 静止元素不加阴影（阴影只给菜单/弹层/hover 抬升）
   - 动效只动 transform / opacity
5) 禁止引入新 UI 库、新字体 CDN、新图标库；Element Plus 图标已够用
```

### H｜文档 / 契约治理（L2）

```
H1 只改指向，不改正文；被归档文件正文一字不动（仅顶部加 ARCHIVED 注释）
H2 判定三级：KEEP（权威）/ ARCHIVE（历史快照）/ DELETE（过程产物，先打包再删）
H3 任何新文档必须在 docs/README.md 登记，未登记 = 非权威
H4 删除前打 tag（如 pre-doc-slim），保证可 `git checkout <tag> -- <path>` 恢复
H5 文档里的数字（D 编号范围、测试数、commit）必须与实际一致
```

### I｜重构 / 技术债（L3，必须分批）

```
I1 前置：目标代码的测试覆盖是否够（覆盖不足先补测试，再重构）
I2 一次只重构一个概念（一个类/一个函数族），不跨模块
I3 外部行为零变化：重构前后同一组接口响应必须逐字节一致（对拍）
I4 删死代码前必须证明引用 = 0（grep + 编译 + 测试三重）
I5 禁止：重命名公共 DTO 字段、改路由、顺手升级依赖
```

### J｜环境 / 部署 / 运维（L2）

```
J1 preflight：health 200 / MySQL Up / Redis PONG / 端口未被占用
J2 端口占用排查：netstat -ano | findstr :8021 → 定位 PID → 确认归属进程
J3 重启：优先正常 shutdown（触发 shutdown hook）；禁止 kill -9；若进程属于 IDE 手动运行 → 回报让负责人处理
J4 Docker 掉线：先 `docker ps`，恢复容器后应用会自动重连；数据以恢复后实测为准
J5 日志：启动日志确认 "Started ... in X seconds"；运行期 grep ERROR / WARN 分类
J6 部署产物：后端 `mvn -DskipTests package` → jar；前端 `npm run build` → `dist/`
J7 变更环境配置 = 阻断项（除明确授权），尤其时区、连接串、profile
```

### K｜诊断 / 答疑（L1，只读）

```
K1 全程只读：不改文件、不写库、不重启服务
K2 输出结构：现象 → 证据（文件:行 / SQL / 请求响应原文）→ 原因（按概率排序，每条给验证方法）
             → 修复方案（含影响面与风险）
K3 无证据的推断必须标「待验证」
K4 结论给完就停，不要顺手改代码
```

### L｜回滚 / 事故处理（L3）

```
L1 先止血：确认影响面（哪些用户/接口/数据），必要时关闭入口（开关/下线）
L2 优先代码回滚：git revert <commit>（禁止 reset 已推送提交）；有 tag 则回到 tag
L3 数据回滚只在有反向脚本时执行；无脚本 → 先导出当前状态再动手
L4 回滚后必须验证：health 200 + 关键链路 3 条 + 全量测试
L5 事后：写一条 decisions 记录（事故原因 / 为什么发生 / 加了哪条红线）
```

---

## 5. 契约落点矩阵（改动 → 必须同步）

| 你改了什么 | 必须同步的文件 |
| --- | --- |
| 路由 / 请求字段 / 响应字段 | `docs/api/接口对接文档.md` |
| 字段校验上限 / 业务错误码 | `docs/api/error-codes.md` |
| 行为语义（时间、幂等、排序、置顶、软删、过滤口径） | `docs/decisions.md` |
| 表 / 索引 / 约束 | `docs/03-数据库设计.md` + 新迁移脚本（禁改历史脚本） |
| 角色 / 权限 | `docs/14-业务逻辑与角色定义.md` |
| 前端对接方式（新增 api 模块、字段约定） | `docs/15-前端对接指南.md` |
| **视觉 / 样式（配色、间距、圆角、字号、阴影）** | 代码层：`src/assets/styles/variables.scss`（token 唯一来源）；要换设计方向 → 先落 `docs/decisions.md` |
| 新增 / 归档任何文档 | `docs/README.md` 文档地图 |
| 以上任意一项 | 更新 `docs/README.md` 的「最后核对日期 / 对应 commit」 |

**改完不更新对应文档 = 任务未完成**，不管测试是否绿。

---

## 6. 项目红线（踩过的坑）

### 6.1 时间与时区

| # | 规则 | 为什么 | 怎么验 |
| --- | --- | --- | --- |
| R1 | DB 一律存 **UTC**；前端提交前转 UTC（只经 `utils/datetime.ts`）；后端不做时区解释 | 后端与 DB 都是 UTC 语义 | 请求体是 UTC 墙钟；DB 值 = 请求值 |
| R2 | **存储半开、界面闭合**：SQL `>= from AND < to`；界面选 23:59:59 → 提交 `+1s`；投放期回显 `-1s` | 否则最后一秒查不到 / 轮播提前失效 | 造 `23:59:59.900` 记录必须被当天命中 |
| R3 | 禁硬编码 `+8` / `28800` / `INTERVAL 8 HOUR`；偏移只在常量 `BUSINESS_ZONE_OFFSET` | 换环境错 8 小时 | `grep -rn "\+8\|28800\|INTERVAL 8 HOUR"` |
| R4 | 手工 SQL 先 `SET time_zone='+00:00'` | 实例 `global time_zone` 仍 SYSTEM（E-001 未收口） | 执行前 `SELECT @@session.time_zone` |

### 6.2 契约与校验

| # | 规则 | 为什么 |
| --- | --- | --- |
| R5 | 字段名不一致 = 必崩类 bug；改名两端同改 + 字段对照表 | 曾出现 `existing`/`exists`、`logs.length`、缺 `status/visibility` |
| R6 | 校验权威在业务层：已有业务码的字段（AI 提问 600 / 留言 1000 / 评论 2000）由领域层抛业务码；DTO 只做必填 + **10× 防爆硬上限** | Bean Validation 先执行会把业务码吞成 `COMMON_VALIDATION_ERROR` |
| R7 | 其余字段（昵称 30 / 称号名 50 / 调分 reason 255）DTO 上界 = 业务上界，返回 `COMMON_VALIDATION_ERROR` + `data.errors` | 前端要能定位到字段 |
| R8 | 前端错误只按 `code` 分支，禁止依赖 `message` 文案 | 文案会改，code 是契约 |
| R9 | 新业务码必须登记 `error-codes.md`；命名 `域_字段_原因` | 否则前端无法分支 |
| R10 | 列表页与详情页的 VO 允许不同，但**字段子集关系必须显式**；前端不得假设详情字段一定存在于列表 | `logs` 只在详情返回 |

### 6.3 幂等 / 并发 / 数据安全

| # | 规则 | 为什么 |
| --- | --- | --- |
| R11 | 资金/写操作带 `Idempotency-Key`：作用域 = 操作人 + 接口 + key；三态 `PROCESSING(60s) → SUCCESS(24h) → 失败 DEL` | 曾重复点击重复加减分 |
| R12 | 失败必须释放幂等键 | 否则用户被锁死无法重试 |
| R13 | 软删优先：查询默认排除 `deleted_at IS NOT NULL`；**统计与列表口径必须一致** | 曾"统计 18 / 列表 17" |
| R14 | 审计与积分流水永不物理删除；测试数据只能反向操作回退 | 审计是追溯依据 |
| R15 | 数据修正：备份 → id 白名单 → 反向脚本 → before/after 回查 | 曾靠白名单修正 banner 时间 |
| R16 | 并发写同一资源：用条件更新/乐观锁（`SET x = x + ? WHERE version = ?`），禁止先读后写 | 并发扣减 |

### 6.4 内容 / 文件 / 通知

| # | 规则 | 为什么 |
| --- | --- | --- |
| R17 | 搜索统一**包含匹配** `%kw%`，必须转义 `% _ \`；信息流与 `/articles/search` 语义一致 | 前缀匹配搜不到"深入 Spring" |
| R18 | 置顶：列表顶部独立区块（上限 3 篇）；主列表强制 `is_pinned=0`；归档明细与计数**都排除**置顶；搜索**不排除**（有意） | 否则出现"X 篇 > 实际条数" |
| R19 | 文件删除前做**实时引用检查**（字段 + 正文内嵌 + 历史版本），被引用返回 `FILE_IN_USE` + 位置清单；`ref_count` 只作快筛 | 去重会让同一 fileId 被共享，误删即丢图 |
| R20 | SSE：心跳 20s；每用户最多 3 条连接；超限**淘汰最旧**；429 只允许有限退避（1s/2s/4s，最多 3 次）；登出/401 先 `close()` | 刷新 3 次曾导致推送失效 30 分钟 |
| R21 | SSE/长连接错误路径必须返回 JSON 错误壳（`Accept` 不能强制 event-stream） | 曾因 Accept 严格导致 500 |
| R22 | 前端 SSE 用 fetch 时也必须复用统一刷新（`refreshOnce`），401 只允许刷新+重连 1 次 | 否则 401 刷屏 |
| R23 | 上传/预览产生的 blob URL 在替换与卸载时 `revokeObjectURL` | 内存泄漏 |
| R24 | 后台列表默认 `deleted=false`；显式传参才看回收站 | 避免误把已删数据混入 |

### 6.5 视觉与 UI（前端）

| # | 规则 | 为什么 |
| --- | --- | --- |
| R25 | **视觉基线唯一来源 = `src/assets/styles/variables.scss`**（设计系统 v2「纸面·青竹」，主色 `#099268`，暖纸底 `#f7f7f4`）。颜色/字号/间距/圆角/阴影一律用其中的 `$xxx`（vite 已全局注入），组件内禁止写死色值 | 一旦各处自拟色值，半年后就是一盘散沙 |
| R26 | 会话里若存在 UI / 设计 / 样式类技能（ui、design、taste、frontend-style 等命名），**先加载再动手**；技能只给方法与取舍，**配色与 token 必须落回 R25 的基线** | 技能会生成"新方向"，直接用就会换掉整套视觉 |
| R27 | `src/assets/styles/element.scss` 是 Element Plus 的全局覆盖，**只允许改 token 映射，不允许加组件特例**；新样式一律写成局部类 | 全局覆盖改一次，全站所有表格/按钮跟着变 |
| R28 | 三条现行禁令（来自设计系统房规）：**禁渐变背景/按钮**、**静止元素不加阴影**（阴影只给菜单/弹层/hover 抬升）、**动效只动 transform / opacity** | 这三条是"纸面·青竹"的辨识度所在 |
| R29 | 视觉改动必须做**同款比对**：改完抽 3 个同类页面比同一元素（按钮/表格/标签/卡片）是否同款，给截图或 DOM 对比 | 单个页面"好看"不等于全站"一致" |
| R30 | 禁止引入新 UI 库、新字体 CDN、新图标库；换主题/换主色属**产品级变更**，要单独立项并在 `decisions.md` 落一条 | 换一次主色等于重做视觉验收 |

---

## 7. 故障模式库

> 用法：拿到症状 → 先查本表 → 按"查法"验证 → 再决定改哪里。命中率高，别从头猜。

| 症状 | 最可能原因 | 查法 | 修复方向 |
| --- | --- | --- | --- |
| 页面白屏 / 打开就崩 | 模板直接读 null 深层属性（`order.logs.length`）；VO 缺字段 | 看 console 首条报错定位组件；Network 看实际响应体 | 可选链 + 默认值；后端补字段（A/B 类） |
| 保存成功但刷新后值没了 | 请求体字段错位（`{key:value}` vs `{value}`）；后端静默写空 | 看请求体原文 + DB 实际值 | 前端改结构；后端缺字段直接 400（禁止静默） |
| 接口 500 | 未捕获异常；DTO 与实体类型不匹配；NPE | 后端日志栈首行；定位 mapper/服务行号 | 按 C 类修；补异常处理 |
| 400 但文案是"参数校验失败" | Bean Validation 抢先于业务校验（R6） | 看错误 code 与 `data.errors` | 放宽 DTO 到 10× 硬上限，业务层抛业务码 |
| 列表条数与"共 X 篇"不符 | 计数与明细口径不同源（软删/置顶/状态） | 对比 count SQL 与 list SQL 的 where | 抽同一条件（R13/R18） |
| 时间整体偏 8 小时 | 本地墙钟直传 / 会话时区 / 硬编码 +8 | 请求体、DB 值、`@@session.time_zone`、grep 28800 | R1–R4 |
| 时间筛选少一秒/少一条 | 半开区间 + 界面闭合未统一 | 造边界记录（0.000 / 59.900）测命中 | R2 |
| 重复点击扣两次 | 无幂等键或键未按时释放 | 看请求头、Redis 键状态、流水条数 | R11/R12 |
| 刷新几次后通知不推送 | SSE 连接池占满（无心跳/无淘汰） | 看连接注册计数与 429 | R20 |
| 页面 401/429 刷屏 | 重试策略无上限；刷新逻辑重复实现 | Network 次数、代码里是否有两套刷新 | R22 |
| 图片显示 404 | 文件被删但仍有引用；去重共享被误删 | 查引用点（字段/正文/历史版本） | R19 |
| 搜索搜不到明显该有的文章 | 前缀匹配 vs 包含匹配；大小写/collation | 直接 SQL `LIKE '%kw%'` 验证 | R17 |
| 无限重定向 | `redirect` 参数指向登录页自身 | 看路由守卫与 URL | 排除 /login /register |
| 加载更多一直转圈 | 并发请求序号守卫把 `finally` 挡住 | 看 loading 复位逻辑 | 无条件复位（G3） |
| 构建通过但运行报错 | 类型断言掩盖；运行期数据与类型不符 | 浏览器 console | 去 `as any`，补运行时兜底 |
| 测试全绿但线上仍错 | 断言随语义变旧（假绿）；测试数据与真实数据分布不同 | 看断言是否覆盖新语义 | R6 类：同轮改断言 |
| 手工 SQL 改完时间不对 | 会话时区 SYSTEM | `SELECT NOW(), UTC_TIMESTAMP()` | R4 |
| 前端 HMR 后报未定义变量 | dev server 半更新 | 重启 `npm run dev` | 运维约定 |
| 合并后行为变了 | 分支分叉期间的语义改动被覆盖 | `git log --graph`、对拍改动前后响应 | L 类回滚或补丁 |
| 后台统计与前台数字不一致 | 统计口径/时间字段不同（`created_at` vs `last_message_at`） | 对拍同区间两处数字 | 统一字段与半开口径 |

---

## 8. 证据标准（什么算 PASS）

| 结论类型 | 唯一接受的证据 |
| --- | --- |
| 字段已对齐 | 后端响应 JSON 原文 + 前端类型/解构代码，两段并排 |
| bug 已修 | 改前失败证据（断言/日志/截图）+ 改后同路径成功证据 |
| 口径已统一 | 两个入口（或 SQL 与 API）的**对拍表**，逐行一致 |
| 数据已修正 | before/after 对照表 + 备份路径 + 反向脚本路径 |
| 性能已优化 | 优化前后耗时 + 扫描行数（EXPLAIN） |
| 无回归 | 全量 `mvn test` 数字 + `vue-tsc` + `vite build` + console 0 error |

**不接受**：`应该没问题` / `看起来正常` / `已按预期修复` / 只有代码没有运行结果 / mock 冒充联调 / 单侧通过就宣布完成。

---

## 9. 命令速查

### 9.1 环境自检

```bash
# 后端健康
curl -s http://127.0.0.1:8021/actuator/health
# 端口占用（Windows）
netstat -ano | findstr :8021
# 依赖容器
docker ps
# Redis
docker exec -it local-redis redis-cli ping     # 期望 PONG
```

### 9.2 后端

```bash
mvn -q -DskipTests package          # 构建
mvn test                            # 全量（基线 153）
mvn test -Dtest=ContentIntegrationTest
mvn test -Dtest=ContentIntegrationTest#方法名
```

### 9.3 前端

```bash
npm run dev            # 5173
npm run build          # vue-tsc --noEmit && vite build
npx vue-tsc --noEmit   # 只做类型检查
npm run lint
```

### 9.4 数据库（只读探查）

```sql
SET time_zone = '+00:00';                      -- 手工 SQL 必做（R4）
SELECT NOW(3), UTC_TIMESTAMP(3);               -- 差 0 才说明会话是 UTC
SELECT @@session.time_zone, @@global.time_zone;
EXPLAIN SELECT ...;                            -- 优化前必看
```

### 9.5 Git

```bash
git log --oneline -10
git tag -l
git status --short
git show <tag>:<path>          # 从 tag 恢复单个文件
git revert <commit>            # 回滚（禁止 reset 已推送提交）
```

---

## 10. 提交与台账规则

```
1) commit 粒度：后端 1 个、前端 1 个；文档变更单独 1 个（docs: 前缀）
2) message：<type>(<scope>): <做了什么> —— 正文写「影响面 + 验证方式」
   type ∈ feat | fix | refactor | docs | chore | test
3) 台账：docs/decisions.md 追加本次口径 —— D 编号由负责人分配，AI 不得自增或改序
4) 新问题不修 → 写 docs/backlog.md（B 编号），不排期、不自作主张立项
5) 禁止：push / merge / 强推 / rebase 已推送提交（除明确授权）
6) 危险操作前打 tag（pre-<任务号>），保证一键回滚
7) 前端无远端：任何 `reset --hard` / `clean -fdx` 前必须回报
```

---

## 11. 标准回传格式

```
1) 定位结论（涉及文件:行 / 数据流 / 根因 / 影响面）
2) 改动摘要（文件 + 一句话，不贴大段代码）
3) 验收结果表（逐条 PASS/FAIL + 四类证据）
4) 数据/配置变更（before → after）
5) commit 号 + git status
6) 新发现问题（只登记分级，不给排期建议）
```

**禁止**：复述任务原文、贴完整文件、自创分级（只用 P0/P1/P2）、报告超 200 行、给排期建议。

---

## 12. 提交前自检清单

```
[ ] 1  grep 过 decisions.md，没推翻既有口径（或已按流程更新）
[ ] 2  契约变更已同步 api/接口对接文档.md 与 error-codes.md
[ ] 3  前后端字段名逐个核对过（有对照表）
[ ] 4  时间参数走 datetime.ts，无硬编码 +8
[ ] 5  新增/修改查询默认排除软删，统计与列表口径一致
[ ] 6  写操作有幂等键，失败路径会释放
[ ] 7  错误提示按 code 分支，不依赖 message
[ ] 8  无新增依赖、无关文件改动、顺手重构
[ ] 9  mvn test 全绿（≥ 基线）；vue-tsc + vite build 通过
[ ] 10 浏览器实测：console 0 error；空/错/加载三态正常
[ ] 11 docs/README.md 的核对日期与 commit 已更新
[ ] 12 未 push / 未合并；危险操作已打 tag
```

---

## 13. 需求速查大表

| 你想做什么 | 类型 | 特别注意 |
| --- | --- | --- |
| 加接口 | A | 先定路由与错误码；权限注解；分页统一约定 |
| 加字段 / 改返回结构 | A | 字段对照表；前端类型同步；破坏性变更要兼容期 |
| 改字段名 | B | 两端同改；调用方清单；文档变更记录 |
| 改校验上限 | A | 三处同改：DTO 硬上限(10×) / 业务层 / error-codes.md |
| 改时间筛选 / 投放期 | A | R1–R4：UTC 提交、半开存储、界面闭合、禁硬编码 |
| 加资金/积分类操作 | A | R11–R12：幂等 + 失败释放 + 审计记变动值 |
| 列表数字与条数不符 | C | 先查统计与列表是否同源（R13/R18） |
| 刷新后状态丢失 | C | 常见：VO 字段写死（`liked=false`）、状态只在内存 |
| 页面必崩 | C/G | VO 是否可能 null；模板是否直接读深层属性 |
| 401/429 刷屏 | C | R20–R22：SSE 连接与重试；先关流再跳转 |
| 怀疑越权 | F | 三查：归属校验 / 角色注解 / 前端隐藏≠权限 |
| 接口慢 | E | 先量化：耗时 + EXPLAIN + 行数 |
| 想删代码/文件 | I/C | 删前证明引用 = 0（grep + 编译 + 测试） |
| 想删文档 | H | 先归档再删；打 tag 保证可恢复 |
| 改页面样式 / 布局 / 组件外观 | G（G0 + R25–R30） | 先探 UI 技能再动手，但 token 必须取自 `variables.scss`；改完做同款比对 |
| 换主色 / 换字体 / 换设计方向 | **产品级变更** | 单独立项 + `decisions.md` 落一条 + 全站视觉验收，禁止顺手改 |
| 觉得"这页不够好看" | G0 | 先说明要改哪个元素、依据哪条 token；不接受"整体重做一遍" |
| 数据错了要修 | D | 备份 + 白名单 + 反向脚本 + 回查 |
| 起不来 / 端口占用 | J | preflight + netstat + 正常 shutdown |
| Docker 挂了 | J | `docker ps` → 恢复容器 → 应用自动重连 |
| 只想问"为什么" | K | 只读，给证据 + 方案，不动手 |
| 要回滚 | L | revert 不 reset；有反向脚本才动数据 |
| 重构一段逻辑 | I | 先补测试 → 单概念 → 行为零变化对拍 |
| 升级依赖 | 阻断 | 需要明确授权，单独一轮，不与功能混做 |

---

## 14. 反模式（AI 常犯错）

| # | 反模式 | 正确做法 |
| --- | --- | --- |
| 1 | 边查边改，定位没完就动手 | 先只读定位，回报后再改（P1） |
| 2 | "已修复"但没证据 | 四类证据之一（P3） |
| 3 | 只改一侧就宣布完成 | 字段对照表（A5） |
| 4 | 顺手重构 / 顺手改格式 / 顺手升级 | 一次只做一件事（R-§10） |
| 5 | 发现 P0 顺便修了 | 阻断项必须停下来回报 |
| 6 | 自创需求 / 自排优先级 | 只登记 backlog，不立项 |
| 7 | 报告越写越长、自创 P3 分级 | 只用 P0/P1/P2，≤6 段 |
| 8 | 改语义但没改测试断言（假绿） | 同轮改断言 |
| 9 | 用 mock 或"函数级验证"冒充联调 | 真实 UI / 真实 HTTP |
| 10 | 为了通过测试改测试而不是改代码 | 先判断是代码错还是断言过时 |
| 11 | 在页面里手写时间格式化 | 统一 datetime.ts |
| 12 | 加缓存掩盖数据不一致 | 先修口径，再谈性能 |

---

## 15. 本文件的维护

| 何时更新 | 更新什么 |
| --- | --- |
| 踩了一次新坑 | §6 追加一条红线（含"为什么"与"怎么验"） |
| 遇到新故障症状 | §7 故障模式库追加一行 |
| 技术栈 / 端口 / 测试基线变化 | §1 |
| 新增任务类型 | §4 追加 SOP，§3 决策树补分支 |
| 每次重大收口 | 头部「版本 / commit」 |

> 规则只增不改语义。要推翻旧规则，必须在 `docs/decisions.md` 留一条决策记录，说明原因。
