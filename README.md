# 文栈博客 · Java 后端（WzBlog Backend）

<!-- 推送到 GitHub 后把 <OWNER> 替换为实际仓库所有者，徽章即生效 -->
[![CI](https://github.com/<OWNER>/wzblog-server/actions/workflows/ci.yml/badge.svg)](https://github.com/<OWNER>/wzblog-server/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/<OWNER>/wzblog-server/branch/master/graph/badge.svg)](https://codecov.io/gh/<OWNER>/wzblog-server)

> 基于 **Spring Boot 3.5 / JDK 21** 的模块化单体博客后端：Markdown 内容、评论互动、积分商城、媒体管理、AI 站内助手与运营看板，形成完整闭环。作为 Java 后端实习简历项目开发，所有能力均可测试验证。

## 快速开始

```bash
# 1) 准备 .env（参考 .env.example，填写 DB/Redis/JWT_SECRET 等）
cp .env.example .env

# 2) 启动依赖（或使用已有 MySQL8/Redis7）
docker compose up -d mysql redis

# 3) 启动应用（Flyway 自动建 32 张表并写入种子数据）
./scripts/run.sh

# 4) 验证
curl http://localhost:8021/api/v1/ping
open http://localhost:8021/swagger-ui.html
```

测试（Testcontainers 真实 MySQL/Redis 容器 + ArchUnit + JaCoCo 覆盖率门禁 70%）：

```bash
./scripts/mvn.sh verify
```

演示数据（可选，空库导入一次）：`mysql -u$DB_USER -p $DB_NAME < db/demo-data.sql`

## 模块与状态

| 模块 | 状态 | 能力 |
|---|---|---|
| M0 平台 | ✅ | 32 表 Flyway、统一响应/错误码/traceId、Security 骨架、OpenAPI、Actuator+Prometheus、CI 三 job、Docker Compose |
| M1 认证 | ✅ | 21 路由：双 Token 轮换/家族吊销、验证码、登录锁定、找回密码、会话管理、登录审计 |
| M5 媒体 | ✅ | 7 路由：预签名直传（本地模式 HMAC 能力 URL）、魔数/像素校验、EXIF 方向、异步缩略图、sha256 去重、引用计数、清理任务 |
| M2 内容 | ✅ | 33 路由：Markdown 渲染/白名单清洗/TOC/阅读时长、草稿-发布-回收站、修订回滚、ngram 全文检索、游标分页 |
| M3 互动 | ✅ | 24 路由：两级评论树、Aho-Corasick 敏感词、审核状态机、幂等点赞收藏、留言板、通知（去重/未读数/SSE）、计数对账 |
| M4 积分 | ✅ | 22 路由：不可变账本（行锁+幂等+日上限+反转收敛）、六类事件发放、下单六步（并发防超卖）、订单状态机、称号、调分审计、对账修复 |
| M6 AI | ✅ | 11 路由：SSE 流式对话（session→tool→delta→done）、6 只读工具、三层配额、游客令牌、摘要/标签建议、后台统计 |
| M7 运营 | ✅ | 20 路由：配置白名单与 CRUD、轮播/友链、看板 6 接口、审计查询、日统计预聚合 |
| M8 微信 | ⛔ 已砍除 | P2 可选项；需真实微信凭据无法本地验收，按砍单规则登记（docs/06 §7） |

**测试：99 个集成测试全绿**（Testcontainers 真库），行覆盖率门禁 ≥70%。

## 架构

```
com.wzblog
├─ common/     统一响应、错误码、分页、端口（FileReference/TitleLookup 等）
├─ config/     Security/Web/MyBatis/OpenApi 组合根
└─ modules/
   ├─ identity / content / interaction / points / media / ai / admin
   │   ├─ api/      Controller + DTO/VO（不碰 Mapper/Entity）
   │   ├─ app/      用例编排（@Transactional 只在这层）
   │   ├─ domain/   实体/枚举/领域事件
   │   └─ infra/    Mapper/Redis/存储/外部 API
```

- **跨模块只走 app 端口或领域事件**（如 M2 `ArticleCounterService`、M5 `FileReferencePort`、M1 `UserQueryService`），由 ArchUnit 4 条规则强制。
- **积分账本**：账户行锁串行化 + 幂等键唯一索引 + 日上限锁内计算；反转不透余额，永不产生负数。
- **一致性**：文章三计数由 M2 端口同事务维护，每日对账按明细差值收敛并写审计。
- **明确不做**：微服务/K8s/分库分表/ES/MQ（判据见架构文档 §1.3，面试加分项）。

## 文档

完整设计文档在 [docs/](docs/)：

| 文档 | 说明 |
|---|---|
| [总体架构方案](后端架构方案-v2.md) | 架构决策（ADR）、数据库重设计、核心难点 |
| [文档约定（唯一事实来源）](docs/00-文档索引与约定.md) | 路由、枚举、错误码、模块边界 |
| [数据库设计](docs/03-数据库设计.md) | 32 张表字典、索引、迁移规范 |
| [接口契约](docs/04-接口契约.md) | 全局规范、全量路由、错误码 |
| [开发计划与进度](docs/06-开发计划与进度.md) | WBS、里程碑、进度与砍单登记 |
| [验收用例](docs/07a-验收用例矩阵-核心.md) | 可执行用例矩阵 |
| [部署与运维](docs/08-部署与运维.md) | Compose、观测、备份 |
| [本地环境与运行](docs/11-本地环境与运行.md) | 启动/测试命令、与设计稿的偏差清单 |
| [**项目学习指南**](docs/12-项目学习指南.md) | 写给 Java 新手：模块地图、难点精讲、主线走读、踩坑与面试弹药 |
| [模块设计](docs/modules/) | M0~M8 逐模块设计 |
| [简历与面试](docs/10-简历与面试.md) | 话术与追问准备 |

## 主要偏差（相对设计稿）

见 [docs/11 §5](docs/11-本地环境与运行.md)：文件存储默认本地磁盘（MinIO 预留 adapter）、限流为固定窗口、AI 上游默认 mock（openai 兼容实现就绪）、微信登录砍除。

## 环境

JDK 21 · Maven 3.9 · MySQL 8 · Redis 7 · Docker。密钥全部经环境变量注入（`.env`），仓库不含真实值。
