# 调试阶段收口基线（T-015）

生成时间：2026-10-31 02:30（本机 +08:00）
环境：8021（本地 Maven 运行，PID 35808）、Vite 5173；MySQL local-mysql8、Redis local-redis。

## 分支拓扑（只读，未合并）

| 仓库 | 当前分支 | 分叉点 | 领先/落后主干 | 最新 commit |
| --- | --- | --- | --- | --- |
| 后端 | feat/integration-q1 | d88b8d0（master） | +21 / -0 | b840d6b |
| 前端 | master | — | — | d403576 |

后端远端：origin/master（未 push 本分支）。

## 数据基线（清理后）

| 表 | 行数 |
| --- | --- |
| articles | 18 |
| article_revisions | 31 |
| comments | 27 |
| guestbook_messages | 11 |
| points_ledger | 130 |
| files | 56 |
| banners | 3 |
| audit_logs | 214 |
| ai_sessions | 18 |
| points_accounts（debug_reader） | balance = 299 |

## 测试数据清理记录

| 项 | 处理 |
| --- | --- |
| debug_reader 余额 | 299，未产生新验收调分，无需反向调分 |
| 测试评论 comments#3030（2000 字，PENDING） | 已删除（显式 id） |
| 测试留言 guestbook_messages#4511/#4513（1000 字） | 已删除（显式 id） |
| 测试文章 / 测试 banner / AI 2030 数据 | 排查为 0，无残留 |
| points_ledger 验收样本（5612–5622） | 保留（D42：流水不删） |
| audit_logs 验收样本（T011_V1 9707–9709、T013/T014 操作） | 保留（D46/D42：审计不删） |
| files 测试上传记录（5045–5055 等） | 保留（D49 软删 DELETED） |

## 环境开关与临时项（只列不改）

| 项 | 现状 |
| --- | --- |
| 显式 `@Deprecated` 代码 | 0 处 |
| WECHAT_PROVIDER | 本地 auto/mock（环境覆盖） |
| AI_PROVIDER | mock（本地） |
| WZBLOG_CAPTCHA_ENABLED | true（本地） |
| application-local.yml | 已入库（D16：禁止密钥，私密值走环境变量） |
| 仓库脚本 | scripts/env.sh、mvn.sh、run.sh（正式，保留） |
| 临时验证脚本 | /tmp/login.js、/tmp/t0xx_live.js、/tmp/close_console*.ps1（未入库，不入库） |
