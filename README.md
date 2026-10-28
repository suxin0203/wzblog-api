# WzBlog Backend · 文栈博客后端

[![CI](https://github.com/suxin0203/wzblog-api/actions/workflows/ci.yml/badge.svg)](https://github.com/suxin0203/wzblog-api/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/suxin0203/wzblog-api/branch/master/graph/badge.svg)](https://codecov.io/gh/suxin0203/wzblog-api)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

一个**功能完整、经过并发与安全验证**的模块化单体博客后端：单人技术博客 + 受邀作者 + 读者社区 + 积分权益。内容主权归作者（EDITOR 只能编辑自己的文章、审自己文章下的评论），平台主权归站主（ADMIN 负责治理与履约，改他人正文需先“接管”）；Markdown 内容、双 Token 认证、不可变积分账本（反转按原收款人）、媒体引用计数、AI 站内助手（SSE + 工具调用）、运营看板——所有能力均有集成测试背书，跑在真实 MySQL/Redis 容器上。

```bash
docker compose up -d mysql redis && ./scripts/run.sh   # → http://localhost:8021/swagger-ui.html
```

---

## ✨ 它能做什么

| 模块 | 能力 |
|---|---|
| **认证 (identity)** | 双 Token 轮换 + 家族复用检测、失败锁定、登录审计、找回密码、微信 openid 身份（自动建号/绑定/迁移） |
| **内容 (content)** | Markdown 单一事实源（渲染/白名单清洗/TOC/阅读时长为派生列）、草稿-发布-回收站状态机、**作者归属校验**（非作者写公开文章 403/隐藏 404）、**站主接管 takeover**、软删/隐藏不释放引用不反转积分（可逆状态无副作用）、修订回滚、ngram 全文检索、游标分页、版本键缓存 |
| **互动 (interaction)** | 两级评论树（父删子留）、Aho-Corasick 敏感词（NFKC 归一 + 原文打星）、审核状态机、**作者审自己文章评论（不能自审）、留言板归站主**、幂等点赞收藏（自赞不计分）、治理通知（SYSTEM）、通知 dedupe + SSE 断线续传、计数对账 |
| **积分 (points)** | **不可变账本**（行锁串行 + 幂等键 + 锁内日上限 + 反转不透余额）、**反转按原流水收款人（接管不错账）**、软删/隐藏不反转（D13）、下单六步事务（条件扣库存防超卖）、订单状态机 + 退款回补、称号、管理员调分审计、对账修复 |
| **媒体 (media)** | 预签名直传（本地 HMAC 能力 URL / 预留 MinIO 双 adapter）、魔数 + 解压炸弹防护、EXIF 方向修正、异步缩略图、sha256 去重（同上传者域）、引用计数、孤儿清理任务 |
| **AI (ai)** | SSE 流式对话（session→tool→delta→done 协议）、6 个只读工具白名单、三层配额（分钟/日/并发 Lua 原子）、游客会话令牌、摘要/标签建议、Mock/OpenAI 双上游 |
| **微信 (wechat)** | 小程序扫码登录状态机（PENDING→CONFIRMED→COMPLETED）、bindToken 一次性、**已上线小程序零改动兼容层**（旧协议壳 + 新安全内核） |
| **运营 (admin)** | 配置白名单 CRUD（JSON 列对称编解码）、轮播/友链、看板 6 接口（实时 + 预聚合）、审计日志、日统计任务 |

**121 个集成测试** · Testcontainers 真库 · ArchUnit 架构守护 · 覆盖率门禁 ≥70%（实测 79%+）

## 🏗️ 架构

```
com.wzblog
├── common/        Result · 错误码 · 滑动窗口限流(Lua) · 跨模块端口 · 旧协议壳工具
├── config/        Security · Web · 组合根
└── modules/       8 个业务模块，每个内部四层
    └── identity / content / interaction / points / media / ai / admin / wechat
        ├── api/       Controller + DTO/VO（不碰 Mapper/Entity）
        ├── app/       用例编排（@Transactional 只在这层）
        ├── domain/    实体 · 枚举 · 领域事件
        └── infra/     Mapper · Redis · 存储 · 外部 API
```

三条硬规则（**ArchUnit 测试强制，不是文档约定**）：

1. `api` 不出现 Mapper/Entity —— 只进出 DTO/VO
2. 跨模块只允许调对方 `app` 层公开服务或订阅领域事件
3. `common` 不反向依赖业务模块

**几个值得一看的设计决策**（完整 ADR 见架构文档 §3）：

- **模块化单体，不拆微服务**——单库单人规模下拆分是负资产；包边界 + 端口已保留按模块拆分的可能
- **余额是快照，真相在流水**——积分账本只追加，冲正靠反向流水，SUM(ledger) = balance 恒成立
- **版本键缓存**——content:article:{id}:v{version}，任何内容变更 bump version 即轮换键，无需删缓存；计数走新鲜行覆盖
- **明确不做**：ES、MQ、K8s、分库分表（判据写在文档里——知道"何时才需要"比"会用"更重要）

## 🚀 快速开始

```bash
# 前置：JDK 21、Docker
git clone https://github.com/suxin0203/wzblog-api.git && cd wzblog-api
cp .env.example .env          # 填 DB_PASSWORD / JWT_SECRET（32+ 随机字节）

docker compose up -d mysql redis   # MySQL 8 + Redis 7
./scripts/run.sh                   # Flyway 自动建 32 张表 + 种子数据

curl http://localhost:8021/api/v1/ping
# Swagger UI → http://localhost:8021/swagger-ui.html
```

**测试**（Testcontainers 起真实容器，非内存库）：

```bash
./scripts/mvn.sh verify            # 110 集成测试 + ArchUnit + 覆盖率门禁
# 覆盖率报告 → target/site/jacoco/index.html
```

## 🔐 安全设计速览

| 威胁 | 对策 |
|---|---|
| XSS | Markdown 渲染 + **OWASP 白名单**清洗（黑名单防不完） |
| 上传伪装 | 魔数校验 + 只读尺寸不解码全图（防解压炸弹） |
| SQL 注入 | 全量 #{} 占位符 + 参数白名单映射 |
| 暴力破解 | 双通道（新 + 小程序兼容）IP 限流 + 失败锁定 + 恒定耗时比对 |
| Token 泄露 | Refresh 家族轮换 + 复用检测吊销全家族 |
| 提示词注入 | AI 工具白名单 + 只读 + 字段裁剪（提示词约束只是补充） |
| 越权 | 归属校验双保险（路由 + 业务层）+ 越权不泄露存在性（404） |
| 密钥 | 全部环境变量注入，仓库零明文；日志 URL 脱敏 |

## 🧪 测试哲学

- **真容器**：正确性一半靠 InnoDB 行锁与唯一索引，模拟库测不出真问题
- **断言打到库**：HTTP 200 不够，ref_count、流水条数、审计行都要对
- **并发真实打**：20 线程抢 1 库存、并发同幂等键、并发刷新轮换
- **架构也测**：ArchUnit 4 规则曾在开发中真实拦截两次跨模块泄漏

## 📚 接口与对接入口

| 入口 | 位置 |
|---|---|
| 接口契约总表 | [docs/04-接口契约.md](docs/04-接口契约.md)：路由、权限、幂等、分页/游标、SSE、错误码 |
| 业务逻辑与角色 | [docs/14-业务逻辑与角色定义.md](docs/14-业务逻辑与角色定义.md)：A 方案口径、角色职责、七条业务闭环、规则与不变量 |
| 字段级 DTO | [docs/modules/](docs/modules/)：M1~M8 各模块 §4 |
| 在线调试 | 启动后访问 `http://localhost:8021/swagger-ui.html`（右上角 Authorize 填 accessToken） |
| OpenAPI JSON | `http://localhost:8021/v3/api-docs` |
| 小程序旧协议 | [docs/13-微信扫码复原计划.md](docs/13-微信扫码复原计划.md)（`/wechat/*`、`/qr-login/*`、`/miniapp/*`、`/users/*`） |
| Web 前端 API 层 | 同级项目 `wzblog-frontend/src/api/`（axios 封装在 `src/utils/request.ts`） |

## 📁 目录导览

```
├── db/migration/          # Flyway: 32 表 DDL + 种子数据（表全注释 + 索引 + CHECK 约束）
├── docs/                  # 设计文档包（契约/表结构/模块设计/验收矩阵/学习指南）
│   └── modules/           #   M0~M8 逐模块设计（状态机/时序/并发策略逐项冻结）
├── scripts/               # run/mvn 包装（本地 JDK/Maven 无需全局安装）
├── src/main/java/com/wzblog/
│   ├── common/            # 限流 Lua、游标分页、端口（FileReference 等）
│   └── modules/           # 8 模块 × api/app/domain/infra 四层
└── src/test/java/         # 每模块一个集成测试 + ArchitectureTest
```

## 🛠️ 技术栈

Java 21 · Spring Boot 3.5 · Spring Security 6 · MyBatis-Plus · MySQL 8 · Redis 7 (Lua) · Flyway · Testcontainers · ArchUnit · JaCoCo · springdoc-openapi · Docker Compose · CI (GitHub Actions)

## 🗺️ 路线图

- [x] M0~M7 全部核心模块 + 全项目审查修复（16 项）
- [x] M8 微信兼容层（mock 验收，provider=auto 凭据齐备自动切真实上游）
- [ ] 真机联调：wzblog-api 域名反代 + 小程序实测
- [ ] 前端（Vue3 网页端 + 小程序 v2 切新契约）
- [ ] 压测（k6）与性能报告归档

## 📄 License

MIT
