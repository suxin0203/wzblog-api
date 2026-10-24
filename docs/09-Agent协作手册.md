# 09 · Agent 协作手册

> 版本：1.0 ｜ 日期：2026-10-24 ｜ 状态：**已定稿**
> 上位文档：[00-文档索引与约定](00-文档索引与约定.md)（契约唯一事实来源）、[后端架构方案-v2.md](../后端架构方案-v2.md) 第 9 节
> 适用：M0~M8 并行开发、合并、评审与交接。任务拆解以 [06-开发计划与进度](06-开发计划与进度.md) 为准；本文只规定协作规则。

---

## 0. 角色与权威顺序

| 角色 | 职责 | 可写范围 |
|---|---|---|
| 架构 Agent | 仓库基线、共享文件、契约裁决、合并 main | `pom.xml`、`common/**`、`config/**`、`db/migration/**`、`application.yml`、`docker-compose.yml`、OpenAPI、README |
| 模块 Agent | 单模块接口、领域逻辑与测试 | `src/main/java/com/wzblog/modules/<module>/**` 及对应测试 |
| 测试 Agent | 按验收用例补集成/并发/安全测试 | 测试目录；生产代码只读 |
| 审查 Agent | 按第 8、9 节做门禁 | 不写代码，只出结论 |
| 文档 Agent | 模块文档与索引一致性 | `docs/modules/**` 与指定配套文档 |

权威顺序：`00` → v2 → `03/04` → 模块文档 → 其他。任何 Agent 不得用代码或分支倒逼契约变更。

---

## 1. 仓库初始化与基线

当前目录不是 git 仓库。架构 Agent 只执行一次，其他 Agent 等基线完成。

```bash
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git init
git branch -M main
git config --local core.autocrlf input
```

创建 `.gitignore` 并提交基线：

```gitignore
target/
build/
logs/
*.log
.env
.env.*
!.env.example
application-local.yml
.idea/
*.iml
.vscode/
.DS_Store
```

```bash
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git add .
git commit -m "chore: 初始化仓库，冻结文档基线"
git tag baseline-docs-v1
git log --oneline -3
git status --short
```

主分支保护：`main` 只允许架构 Agent 合并，禁止直接提交；合并前 `mvn -q clean verify` 必须通过；禁止对 `main` 强推、rebase、`reset --hard`；只保留 `--no-ff` 合并提交便于回滚。接入远程后开启平台保护：禁止直推、至少 1 次评审、构建检查必须通过、禁止删除分支。

---

## 2. 分支模型

trunk-based + 短分支。`main` 永远可编译、测试全绿；功能分支 1~3 天完成，最长不超过 5 个工作日。

| 分支 | 用途 | 示例 | 生命周期 |
|---|---|---|---|
| `main` | 唯一长期分支 | — | 永久 |
| `feat/mN-name` | 模块功能 | `feat/m1-identity` | 依赖合并后创建，验收即删 |
| `fix/mN-topic` | 模块缺陷 | `fix/m4-order-stock` | ≤ 2 天 |
| `docs/topic` | 文档与契约修订 | `docs/contract-1.3` | ≤ 2 天 |
| `chore/topic` | 构建、依赖、脚本 | `chore/spring-ai-bom` | ≤ 2 天 |

规则：全小写、连字符分隔，禁止中文、空格、姓名；一个分支只做一件事；每天 `git rebase main`；禁止 `develop`、`release/*` 等长期平行分支；合并后删除，不复用；任何 Agent 不得在 `main` 直接提交，包括只改一行文档。

---

## 3. Worktree 方案

同一目录同时只能有一个分支。多个 Agent 共用会互相覆盖未提交文件、`target/`、IDE 索引、运行进程与 Testcontainers 容器，`git switch` 还会因本地改动失败。因此**每个 Agent 一个独立 worktree**，路径放在仓库外。

```bash
mkdir -p "E:/MyCodeProject/Java/wzblog-wt"
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git worktree add "E:/MyCodeProject/Java/wzblog-wt/m0" -b feat/m0-platform main
git worktree add "E:/MyCodeProject/Java/wzblog-wt/m1" -b feat/m1-identity main
```

只创建当前已满足前置条件的模块：M1 等 M0 合并，M2 等 M1、M5 合并。

```bash
cd "E:/MyCodeProject/Java/wzblog-wt/m1"
git status
git log --oneline -3
git rebase main
mvn -q clean verify
```

有远程仓库时先 `git fetch origin`，再 `git rebase origin/main`。rebase 冲突按第 11 节处理，不得强推或丢弃他人提交。

合并完成且无未提交改动后清理：

```bash
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git worktree remove "E:/MyCodeProject/Java/wzblog-wt/m1"
git worktree prune
git branch -d feat/m1-identity
git worktree list
```

不要用 `--force` 清理；有未提交改动时先提交或由本人确认丢弃。

---

## 4. 共享文件所有权

以下文件由架构 Agent 独占，模块 Agent 只读。需要变更时提“共享变更请求”，不得在功能分支直接改。

| 文件/目录 | 内容 | 模块 Agent 可做 |
|---|---|---|
| `pom.xml` | 依赖、插件、JDK | 提交依赖需求与用途，不自行添加 |
| `common/**` | Result、ErrorCode、异常、分页、工具 | 只调用；新增通用能力先申请 |
| `config/**` | Security、MyBatis、Redis、Jackson、OpenAPI | 不修改；配置键先约定 |
| `db/migration/**` | Flyway `V1`、`V2` 及后续 | 不新增、不改名、不改内容；先申请版本号 |
| `application.yml` | 全局与模块配置节 | 提供键名与默认值，由架构合并 |
| `docker-compose.yml` | MySQL/Redis/MinIO 等 | 不修改；环境问题走阻塞升级 |
| `docs/api/**`、OpenAPI | 聚合接口契约 | 只在自己 Controller 加注解，不改聚合文件 |
| `README.md` | 启动、验收、状态 | 提供草稿，由文档 Agent 合并 |
| `docs/00/03/04` | 全局契约 | 只引用；发现问题按第 11 节提变更 |

变更流程：

1. 模块 Agent 在完成报告写清“需要什么、为什么、影响哪些模块”，附最小示例。
2. 架构 Agent 从 `main` 建 `chore/...` 或 `docs/...` 分支，只改共享文件，单独提交。
3. 合并前 `mvn -q clean verify`；涉及 DDL 时在空库执行 Flyway 验证。
4. 合并后通知所有活跃 Agent；各分支立即 `git rebase main` 再继续。
5. **先冻结契约，再并行开发**；共享变更不得夹带业务代码。

配置键统一 `wzblog.<module>.<name>`，例如 `wzblog.media.max-size`；模块 Agent 只提交键名与默认值。

---

## 5. 模块 Agent 开工前置

缺一项就不写业务代码：

- [ ] 已读 00 第 3、4、5、6、9 节：路由、枚举、错误码、跨模块事件与调用。
- [ ] 已读 03 中本模块表、索引、迁移规范。
- [ ] 已读 04 中本模块路由、DTO、错误码；若发现缺口，先改 00/04 再开工，不得自行扩展。
- [ ] 已读 `docs/modules/Mx-*.md` 且状态为“已定稿”；草稿不得开工。
- [ ] 已读 v2 本模块章节与第 9 节模块边界。
- [ ] 依赖已合并：`git log --oneline main` 能看到依赖模块合并提交。
- [ ] 已建独立 worktree 与 `feat/mN-name` 分支，`git status` 干净。
- [ ] 已列预计改动文件，全部位于本模块包与测试目录。
- [ ] 已明确本模块发布/订阅事件与允许的同步调用。

跨模块只允许三类交互：00 第 9.1 节事件（监听器幂等）；00 第 9.2 节同步接口（只调 app 层，不碰对方 Entity/Mapper）；M7 看板 `AnalyticsMapper` 只读例外（文档列明表与 SQL 口径）。新增交互先改 00 第 9 节并升版本，由架构实现端口。

---

## 6. Agent 提示词模板

以下模板可直接复制；只替换模块编号、分支与路径，不删除禁止项和输出格式。

### 6.1 模块 Agent

```text
你是文栈博客 M4 points 模块 Agent。只写本模块，不改共享文件。
上下文：必读 00 第 3/4/5/6/9 节、03 中 points 表与索引、04 中 M4 路由/DTO/错误码、docs/modules/M4-points.md（已定稿）、v2 第 6.5/7.5/8.8/9 节、main 上 M0/M1 的 app 接口与 ErrorCode。
工作区/分支：E:/MyCodeProject/Java/wzblog-wt/m4 ；feat/m4-points（已 rebase 最新 main）。
范围：只改 src/main/java/com/wzblog/modules/points/** 与对应 src/test/**；实现模块文档全部路由、DTO、状态机、事务、幂等；按 00 第 9 节发布事件。
禁止：改 pom.xml、common/**、config/**、db/migration/**、application.yml、docker-compose.yml、OpenAPI 聚合文件、README；注入其他模块 Mapper 或引用其 Entity；新增/改名路由、枚举、错误码；提交密钥、本地配置、target/、日志。
验收：mvn -q clean verify 通过；模块文档第 11 节用例逐条有测试；库存 1 并发 50 仅 1 成功；重复 Idempotency-Key 只扣一次；列表有 EXPLAIN 且无全表扫描；401/403、越权、状态非法均有测试。
输出：先给开工确认（依赖提交号/预计文件/风险），完成后给“改动文件/测试结果/未决问题/下一步”；只给关键决策与命令摘要，不贴大段代码。
```

### 6.2 测试 Agent

```text
你是文栈博客测试 Agent。只写测试，不改生产代码。
上下文：docs/modules/Mx-*.md 第 11 节验收用例（逐条编号）、00 第 3/5/6/9 节、docs/07（若已生成）、git diff main...HEAD --stat。
范围：只改 src/test/** 与测试资源；用 Testcontainers 启动 MySQL/Redis/MinIO，不 mock 数据库事务；覆盖正常流、401/403、幂等重放、并发竞争、状态非法、边界值、XSS 样本、上传伪装（媒体模块）。
禁止：改生产代码、pom.xml、迁移脚本、配置；为通过而放宽断言或跳过用例；引入 pom.xml 中不存在的依赖。
验收：mvn -q clean verify 通过；每条用例有对应测试方法并含用例编号；并发测试重复 3 次结果一致；缺陷只输出复现步骤、期望/实际、日志摘要，不直接修。
输出：用例覆盖表（编号→类/方法→结果）、缺陷清单（编号/复现/期望/实际/级别）、未覆盖项及原因、测试命令与耗时。
```

### 6.3 审查 Agent

```text
你是文栈博客审查 Agent。只评审，不重写代码。
输入：分支 feat/m4-points；基线 main；范围 git diff main...HEAD；依据 docs/09 第 8/9 节、00 第 3/4/5/6/9 节、模块文档。
必查：分层（Controller 不写业务、事务在 AppService、Mapper 不被跨模块注入）；事务边界与提交后事件；锁顺序与条件更新；幂等键或唯一约束；EXPLAIN 无全表扫描/大表 filesort；错误码全部来自 ErrorCode；路由权限与归属校验；日志含 traceId 且无敏感信息；验收/并发/越权测试有效；路由、DTO、枚举、事件与契约逐字一致。
禁止：直接修改代码、提交修复、用建议项掩盖阻断项。
输出：阻断项（文件:行/问题/依据/修复建议）、建议项（收益与风险）、已确认项（逐条勾选第 9 节 DoD）、结论（通过/有条件通过/不通过）。
```

### 6.4 文档 Agent

```text
你是文栈博客文档 Agent。只改文档，不写代码。
上下文：00 第 10/11 节模板与变更流程、docs/modules/_TEMPLATE.md、对应模块最终代码与测试结果。
范围：只改 docs/modules/Mx-*.md 与明确指定的配套文档；必须含范围、路由、DTO、表、状态机、时序、并发/事务/幂等、权限、错误码、验收用例、索引、事件、待确认；路由/枚举/错误码与 00 逐字一致，只引用 03/04。
禁止：改 00/03/04 的契约内容（只能提变更请求）；编造未实现行为或未测性能数字；留待办标记、空表格、无默认值的待确认项。
验收：每条路由都能在代码找到；每条错误码都在 ErrorCode 与 04 登记；验收用例编号连续可执行；与 00 的一致性检查无冲突。
输出：改动章节清单、与代码/契约的差异表、待确认事项及默认方案、后续文档任务。
```

---

## 7. 合并顺序与集成节奏

固定顺序：**M0 → M1 → M5 → M2 → M3 → M4 → M6 → M7 → M8**。即使 M3/M4 可并行开发，也按此顺序合并。

| 顺序 | 模块 | 分支 | 前置 | 合并门禁 |
|---|---|---|---|---|
| 1 | M0 platform | `feat/m0-platform` | 无 | 空应用可启动、健康检查正常、Flyway 空库通过 |
| 2 | M1 identity | `feat/m1-identity` | M0 | 认证/RBAC/锁定与安全用例通过 |
| 3 | M5 media | `feat/m5-media` | M0 | 直传、引用计数、清理用例通过 |
| 4 | M2 content | `feat/m2-content` | M1、M5 | 全接口、Markdown、搜索、EXPLAIN |
| 5 | M3 interaction | `feat/m3-interaction` | M1、M2 | 评论树、点赞幂等、通知事件 |
| 6 | M4 points | `feat/m4-points` | M1 | 并发扣减、账本平衡、订单状态机 |
| 7 | M6 ai | `feat/m6-ai` | M1、M2 | SSE、工具调用、配额、失败降级 |
| 8 | M7 admin | `feat/m7-admin` | M1~M4、M6 | 看板口径、配置、审计、只读统计 |
| 9 | M8 wechat | `feat/m8-wechat` | M1 | 登录绑定、扫码会话、可随时砍 |

```bash
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git switch main
git status
git merge --no-ff feat/m1-identity -m "merge: M1 identity"
mvn -q clean verify
docker compose up -d
docker compose ps
git tag m1-done
```

一次只合并一个模块，不攒三个一起合。合并前模块必须已 `rebase main` 且全绿；合并后在 `main` 跑全量测试再冒烟。失败用 `git revert -m 1 HEAD` 回退，由原分支修复后重合，不改写 `main`。每个模块合并后打标签（`m0-done` … `m8-done`）。

冲突热点与裁决人：`pom.xml`/`config/**`/`common/**`/迁移/`application.yml`/`compose` 由架构 Agent；路由、DTO、枚举、错误码由架构 Agent 依 00 裁决；模块文档由文档 Agent 依模板裁决；测试数据冲突由测试 Agent 与模块 Agent 协商，契约不让步。

---

## 8. 代码评审清单

以 `git diff main...HEAD` 为单位，阻断项未清零不得合并。

| 类别 | 必查点 | 阻断条件 |
|---|---|---|
| 分层 | Controller 只校验与编排；业务在 AppService；Domain 不依赖 Web | Controller 直连 Mapper、事务写在 Controller |
| 事务 | 边界在 AppService；异常可回滚；事件提交后处理 | 事务跨远程调用、监听器影响主事务 |
| 锁顺序 | 与文档一致；多表顺序固定；条件更新带状态谓词 | 反向加锁、无超时、先查后改无并发保护 |
| 幂等 | 写接口有幂等键或唯一约束 | 重复提交产生双份流水/订单/通知 |
| 索引 | 列表/统计 SQL 有 EXPLAIN；分页走索引 | 全表扫描、大表 filesort、缺复合索引 |
| 错误码 | 只用 ErrorCode，HTTP 映射符合 00 第 5.2 节 | 裸 `fail(400, "...")`、自造 code |
| 权限 | 路由权限正确；归属校验不可绕过；401/403 有测试 | 只校验登录不校验归属、ADMIN 绕过缺失 |
| 日志 | 关键路径有 traceId；业务拒绝用 WARN 不打堆栈 | 打印密码、Token、密钥、完整手机号 |
| 测试 | 验收用例逐条覆盖；并发/越权/幂等真实有效 | 跳过、空断言、只测正常流 |
| 契约 | 路由、DTO、枚举、事件与 00/模块文档一致 | 私自新增/改名路由、枚举、错误码 |

建议项（不阻断但需登记）：命名可读性、重复代码抽取、注释准确性、日志字段统一、测试数据工厂复用。建议项不得掩盖阻断项；审查 Agent 不替模块改代码。

---

## 9. Definition of Done

依据 00 第 10/11 节与 v2 第 9.5 节，合并前逐条勾选：

- [ ] 1. 模块文档已定稿，路由、DTO、枚举、错误码与 00/03/04 一致。
- [ ] 2. `mvn -q clean verify` 通过，覆盖模块文档第 11 节全部用例。
- [ ] 3. ArchUnit 通过：api 不依赖 infra，模块不越界，无跨模块 Mapper 注入。
- [ ] 4. Flyway 在空库完整执行，种子可重复导入，已执行脚本未被修改。
- [ ] 5. 所有错误码已登记到 `ErrorCode`，无裸状态码与自造错误码。
- [ ] 6. 写接口具备幂等键或唯一约束，重复请求只生效一次且有测试。
- [ ] 7. 列表/统计接口附 EXPLAIN，无全表扫描与大表 filesort。
- [ ] 8. 鉴权与归属校验覆盖 401、403、越权与 ADMIN 绕过。
- [ ] 9. 事务边界、锁顺序、条件更新与模块文档一致，并有并发测试。
- [ ] 10. 关键路径有日志与指标，异常带 traceId，无敏感信息泄露。
- [ ] 11. OpenAPI 注解完整，Swagger 可调通，模块 README 含 curl 验收命令。
- [ ] 12. 无密钥/本地配置/`target/` 入库，无注释代码，无遗留待办标记。

---

## 10. 交接与阻塞

完成报告必须包含四项，缺项视为未完成：

```text
## 完成报告
模块：M4 points
分支/提交：feat/m4-points / <commit>
改动文件：<按目录列出，标注新增/修改>
测试结果：mvn -q clean verify 通过；用例数/失败数/耗时；并发重复次数
契约/共享变更：无，或请求编号与状态
未决问题：<问题/影响/建议/需谁裁决>
风险：<已知风险与缓解>
下一步：<合并顺序、需通知的 Agent>
```

出现以下情况立即停止编码并升级，不自行扩大范围：契约缺失或矛盾；依赖未合并或 app 接口不存在；需要改共享文件、迁移、pom；同一错误连续两次修复失败；环境不可用超过 15 分钟。升级顺序：模块 Agent → 架构 Agent → 用户，附命令、关键日志、已尝试方案。禁止以“先临时改一下”为由修改共享文件或跨模块直连。

范围纪律：只改开工时列出的文件；顺手可修的问题写进报告，不夹带提交；一个分支只对应一个模块、一个目标，范围变化先拆分支并确认。

---

## 11. 冲突处理

统一顺序：**停止合并 → 判定权威 → 先改契约 → rebase → 全量验证**。禁止用“保留我的版本”草率解决。

| 冲突类型 | 权威依据 | 裁决人 | 处理 |
|---|---|---|---|
| 共享文件 | 第 4 节所有权 | 架构 Agent | 模块执行 `git rebase --abort`，提请求；架构在独立分支改后合并 |
| 迁移脚本 | 03 迁移规范 + 已执行不可变 | 架构 Agent | 不改 `V1/V2`，不重编号已合并脚本；架构分配新号，模块删本地冲突文件后重建 |
| 接口/路由/DTO | 00 第 4 节、04 | 架构 + 文档 Agent | 以 00/04 为准，模块文档让步；确需变更先改 00 并升版本 |
| 枚举/状态机 | 00 第 6 节 | 架构 Agent | 只用冻结值；新增先改 00 并登记变更记录 |
| 错误码 | 00 第 5.3 节、04 目录 | 架构 Agent | 先登记再使用，禁止自造 |
| 模块文档 | 00 第 10 节模板 | 文档 Agent | 结构服从模板，内容服从契约 |

迁移冲突细则：`V1/V2` 一旦合并即冻结，任何人不得修改，否则 Flyway 校验失败；新表/新列一律新增迁移，版本号由架构统一分配；本地未合并文件冲突时删除自己的文件，rebase 后按新号重建；已应用环境只前进，修复用新迁移。

接口冲突细则：先查 00 第 4 节是否已有路由；有则模块改代码，没有则走契约变更。契约变更只能由架构 Agent 提交，必须同步 00 变更记录、04、受影响模块文档与 DDL，再通知所有 Agent rebase。**改契约的唯一责任人是架构 Agent；模块 Agent 只能提请求。**

---

## 12. 质量与安全

红线，违反即阻断：

1. 禁止提交密钥、Token、证书、真实账号、生产连接串；本地配置只放 `application-local.yml` 或环境变量。
2. 禁止 `git commit --no-verify`、绕过钩子、伪造签名；钩子失败先修问题。
3. 禁止修改已执行的 Flyway 脚本，禁止手工改 `flyway_schema_history`。
4. 禁止跨模块注入 Mapper、引用对方 Entity、直接写对方表；只允许 00 第 9 节接口与事件；M7 `AnalyticsMapper` 只读例外不得写入。
5. `points_ledger` 只能由 M4 写入，其他模块通过事件或 M4 接口发起。
6. 禁止日志输出密码、Token、密钥、验证码、完整手机号/邮箱。
7. 禁止提交 `target/`、日志、IDE 配置、本地数据卷；禁止新增未审批依赖。
8. 禁止对 `main` 强推、改写历史、删除他人分支；禁止把无关改动混入模块分支。
9. 禁止 Controller 捕获异常后返回 `null` 或吞异常，统一走全局异常处理。

合并前自检：

```bash
cd "E:/MyCodeProject/Java/wzblog-wt/m4"
git status --short
git diff --stat main...HEAD
git log --oneline main..HEAD
mvn -q clean verify
```

改动文件必须全部属于本模块；出现共享文件路径立即停止并升级。

---

## 13. 一页纸速查

```bash
# 基线（架构 Agent，一次）
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git init
git branch -M main
git add .
git commit -m "chore: 初始化仓库，冻结文档基线"

# 创建 worktree（依赖合并后）
mkdir -p "E:/MyCodeProject/Java/wzblog-wt"
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git worktree add "E:/MyCodeProject/Java/wzblog-wt/m1" -b feat/m1-identity main

# 日常开发
cd "E:/MyCodeProject/Java/wzblog-wt/m1"
git rebase main
mvn -q clean verify
git status --short
git log --oneline main..HEAD

# 合并（架构 Agent）
cd "E:/MyCodeProject/Java/文栈博客后端AI重构数据库版本"
git switch main
git merge --no-ff feat/m1-identity -m "merge: M1 identity"
mvn -q clean verify
git tag m1-done

# 清理
git worktree remove "E:/MyCodeProject/Java/wzblog-wt/m1"
git worktree prune
git branch -d feat/m1-identity

# 冒烟
docker compose up -d
docker compose ps
```

| 主题 | 一句话规则 |
|---|---|
| 分支 | `main` 只合并不直提；短分支 ≤ 5 天；命名 `feat/mN-name` |
| 工作区 | 一个 Agent 一个 worktree；不在同一目录切分支 |
| 共享文件 | `pom/common/config/migration/yml/compose/OpenAPI/README` 架构独占 |
| 依赖 | 依赖合并后才开工；开工先 `rebase main` |
| 范围 | 只改本模块包与测试；不夹带、不扩权 |
| 契约 | 00 唯一事实来源；只能架构 Agent 改并升版本 |
| 迁移 | 已执行脚本不可改；新版本号由架构分配 |
| 合并 | M0→M1→M5→M2→M3→M4→M6→M7→M8；一次一个；每次全量 |
| 评审 | 阻断项清零才合并；审查 Agent 不改代码 |
| 安全 | 不提交密钥；不 `--no-verify`；不跨模块注入 Mapper |
| 交接 | 完成报告四段：改动/测试/未决/下一步 |
| 回滚 | 用 `git revert -m 1`，不改写 `main` 历史 |