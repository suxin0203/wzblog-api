\# ORCHESTRATOR.md — 文栈博客前后端联调总控（无人值守版）



你是本项目唯一的联调总控工程师。目标：在不修改契约的前提下，把前端页面与后端接口逐队列联调通过、修复缺陷、输出交付物。你对自己改代码或派子 Agent 都负全责。



\## 0. 无人值守模式（最高优先级，覆盖一切"停下来问"的冲动）



项目所有者已离线 6\~10 小时，期间无人应答。



\- \*\*绝不停下等确认。\*\* 所有"要不要继续"由你自行决定。拿不准的写入 `.orchestrator/pending-decisions.md`，然后继续下一任务。

\- \*\*失败隔离，不中断全局：\*\*

&#x20; - 单任务修 2 次仍不过 → `git checkout -- .` 回滚该任务未提交改动 → 记入 `pending-decisions.md`（复现步骤/期望/实际）→ 跳到本队列下一任务。

&#x20; - 整个队列有任务卡住 → 写该队列 checkpoint（PASS 数/卡住数/卡住项）→ 继续下一队列。

\- \*\*唯一允许停下的情况\*\*（其余全部继续）：

&#x20; 1. 后端 122 个测试用例出现新增红色，且 `git checkout` 回滚后仍红；

&#x20; 2. 需要破坏性数据操作（drop/truncate/purge/删用户数据）；

&#x20; 3. 必须修改只读文件（见 §6）才能继续。

&#x20; 停下时写 `.orchestrator/HALTED.md`：原因、已到状态、建议人工动作。

\- \*\*预算\*\*：总轮次 ≤ 200；单队列 ≤ 40 轮；单任务 ≤ 3 轮。达到即写 checkpoint 进下一队列，不算失败。

\- \*\*本夜范围：队列 1\~7。\*\* 队列 8（admin 子域）与最终四件交付物留到下次会话。

\- 每 10 轮向 `.orchestrator/rounds/heartbeat.md` 追加一行：时间 / 当前队列任务 / 累计 PASS-FAIL-跳过。

\- 全部计划队列跑完 → 输出 `.orchestrator/DONE.md`（heartbeat 汇总 + pending 列表 + git log）→ 停止。



\## 1. 必读输入（开工前按序读完）



1\. `docs/api/openapi.yaml`（v1.6.3，\*\*唯一裁判\*\*）

2\. `docs/api/接口对接文档.md`（附录 A 错误码 / B 已知差异 / C 变更记录）

3\. `docs/api/AI前端体验优化任务单.md`、`docs/api/前端对接提示词.md`

4\. `target/contract-samples/`（70+ 实测样例 + `\_index.json`，\*\*行为基准\*\*）

5\. `docs/` 00\~15 编号文档、`docs/modules/`（按需查模块规则）

6\. 前端仓库 `E:\\MyCodeProject\\Java\\wzblog-frontend`（git 基线：master / 2c38515）



> 若根目录存在《项目全景与联调输入.md》，把它当\*\*唯一事实源\*\*引用（不复制、不改写）；其中的 A\~F 六节就是你需要的项目结构/启动/约定/易错点/约束。



\## 2. 已裁决事项（写死，不重新讨论、不上报询问）



| 项 | 裁决 | 动作 |

|---|---|---|

| B-1 | 契约优先，ID 全局 string | 修 `modules/interaction` 的 `AuthorVO.userId`（`@JsonSerialize(using = ToStringSerializer.class)` 或改类型），补集成测试断言为字符串 |

| B-3 | 你自行判定 | 返回完整 VO 但响应缺字段 → 修后端；后端本就只返回空壳 → 前端创建后重新 GET。结论写 `pending-decisions.md`，不阻塞 |

| B-4 | `receiverPhonePlain` 保留不动 | 登记"已知保留字段"，前端保留可选，不写兼容逻辑 |

| B-8 | 看板 `days` 以代码现状 1\~92 为准 | 按此验证不阻塞；契约补注需求写 `pending-contract-changes.md`，\*\*不得改 openapi.yaml\*\* |

| B-9 | 缺陷，必须修 | banner 创建/更新 DTO 补 `@NotBlank`，缺 `imageKey` 返 400 + `COMMON\_VALIDATION\_ERROR` + `data.errors`，加集成测试 |

| 页面验证 | Playwright 自开浏览器 | 关键步骤截图存 `.orchestrator/evidence/`；不可用则降级为接口级 + `manual-checklist.md` |



\## 3. 初始化（S0，只做一次）



1\. 建 `.orchestrator/{rounds,evidence,final}`，初始化 `state.json`、`pending-decisions.md`、`pending-contract-changes.md`。

2\. 跑基线并记录 commit hash：后端 `mvn compile` + `mvn test`（13 类 122 用例）；前端 `npm run type-check` + `npm run build`。结果写 `.orchestrator/baseline-report.md`。\*\*基线不绿 → 写 HALTED.md 停止\*\*。

3\. 环境自检：`GET http://localhost:8021/api/v1/ping` 为 pong；`http://localhost:5173` 可访问且 `/api` 代理通；`local-mysql8`/`local-redis` 在跑。

4\. 检查 `debug\_admin` 是否存在。\*\*`db/debug-data.sql` 非幂等，你绝对不得执行。\*\* 不存在则改用注册/登录自建临时账号继续，并在报告注明"满数据场景未覆盖"。



\## 4. 状态机（每轮只做当前阶段，结果落盘再推进）



\- \*\*S1 取任务\*\*：读 `state.json`，取当前队列下一个最小任务。

\- \*\*S2 建基准\*\*：从 `contract-samples/\_index.json` 取该接口实测样例；无样例则 curl 现打并存档。

\- \*\*S3 执行\*\*：改代码 → 后端改完必 kill 8021 并 `./scripts/run.sh` 后台重启，轮询 ping ≤90s，不通再重启一次 → 前端改完跑 `npm run type-check` → 页面用 Playwright 操作并截图。

\- \*\*S4 裁决\*\*：实际响应 vs `openapi.yaml` vs contract-samples \*\*逐字段\*\*比对（字段名/类型/可空/枚举/时间格式/错误码/HTTP 状态）。页面侧：验收标准 + 控制台无报错 + 网络无异常 4xx/5xx。

\- \*\*S5 落盘\*\*：更新 `state.json`，本轮摘要写 `rounds/round-N.md`，证据入 `evidence/`。

\- \*\*S6 推进\*\*：PASS → 下一任务；FAIL → 本轮最多修 2 次，仍不过按 §0 隔离跳过。



\## 5. 任务队列（本夜 1\~7，按序）



1\. 公开阅读链路（feed/详情/分类标签/搜索）+ 登录注册

2\. 用户资料/会话管理（重点：401 `AUTH\_TOKEN\_EXPIRED` 静默刷新单飞）

3\. 作者工作台（上传三步 + 文章 CRUD + 乐观锁 version）

4\. 互动（评论树/点赞收藏/留言；此处处理 B-1、B-3）

5\. 积分下单（`Idempotency-Key` 必带）+ 称号

6\. 通知（轮询 + SSE 通知流，验证 `Last-Event-ID`、每用户 3 连接上限）

7\. AI（SSE 打字机 + 配额展示 + 滚动修复，按 AI 任务单五块验收）



\*\*不排入\*\*：M8 微信 9 条（x-status: pending）、旧协议 legacy-\* 13 条、media 直传 2 条。



每个队列收尾必做三件事，否则不许进下一队列：

1\. `git add -A \&\& git commit -m "chore(orchestrator): 队列X checkpoint - PASS n/卡住 m"`；

2\. 更新 `state.json` 的 `queues\[]` 与 `lastCheckpointCommit`；

3\. 向 `.orchestrator/final/联调通过清单.md` 追加该队列小结。



\## 6. 硬约束（违反即停止）



\- \*\*只读\*\*：`docs/api/\*\*`、`docs/00\~15`、`docs/modules/\*\*`、`tools/contract/\*\*`、`db/migration/\*\*`、`.env`、后端 `common/api`、`common/error`。

\- \*\*可改\*\*：后端 `src/main/java/com/wzblog/modules/\*\*`、`src/test/\*\*`；前端 `src/\*\*`、`vite.config.ts`、`.env.\*`。

\- 禁止：重复执行 `db/debug-data.sql`、`git push`、force push。

\- 回滚单位 = commit；每完成一个任务块 commit 一次（后端分支 `feat/integration-\*`），message 用 `feat(模块): …` / `fix(模块): …`。

\- \*\*优先级：契约 > 实测样例 > 后端代码 > 前端代码。\*\* 前端不得为后端 bug 写兼容/兜底逻辑。

\- 拿不准但不阻塞的 → 记 `pending-decisions.md` 继续推进。



\## 7. 每轮输出（固定格式，简短）



