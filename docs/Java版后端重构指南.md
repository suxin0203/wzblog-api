# 文栈博客 · Node.js → Java 后端重构指南（AI 协作开发文档）

> 生成日期：2026-09-23
> 用途：本文档是「用 Java 重构现有 Express 后端」的唯一事实来源（Source of Truth）。
> 所有 Node 侧信息均从当前代码库实际提取（非凭记忆），所有 Java 版本信息均于 2026-09 联网核实（来源见文末）。
> 性质：**仅用于学习目的**——从零开始学习 Java 后端开发，并用本项目的真实业务做练习题。

---

## 目录

1. [使用说明（给人看，也给 AI 看）](#一使用说明)
2. [现有 Node 后端架构总览](#二现有-node-后端架构总览)
3. [Node → Java 技术栈对照表](#三node--java-技术栈对照表)
4. [目标 Java 技术栈（2026-09 主流推荐）](#四目标-java-技术栈2026-09-主流推荐)
5. [数据库设计（18 张表完整字段）](#五数据库设计)
6. [全局接口契约（重构必须保持的行为）](#六全局接口契约)
7. [全量接口清单（97 个）](#七全量接口清单)
8. [核心业务规则速查](#八核心业务规则速查)
9. [分阶段学习开发路线](#九分阶段学习开发路线)
10. [进阶模块实现要点（扫码登录 / AI 向导）](#十进阶模块实现要点)
11. [给 AI 的重构提示词模板](#十一给-ai-的重构提示词模板)
12. [版本核实记录与来源](#十二版本核实记录与来源)

---

## 一、使用说明

### 1.1 这份文档给谁用

- **给人**：了解现有 Node 后端的全貌，作为 Java 学习路线的"翻译对照书"。
- **给 AI**：作为重构任务的完整上下文。AI 拿到本文档后，应能不读（或少量阅读）Node 源码就开发出行为一致的 Java 后端。

### 1.2 三条基本原则

1. **数据库直接复用，不做迁移。** Java 后端连接同一个 MySQL 库（建表脚本见 `sql/wz_blog_schema.sql` + `sql/migrations/`），表名保持 `wz_` 前缀，字段保持下划线命名，由 ORM 做驼峰映射。这样前端 Vue3 与小程序可以零改动切换后端。
2. **接口契约向后兼容。** 路径、请求参数、响应结构（`{code, message, data}`）与现有版本保持一致，见第六、七节。前端已有的调用不能被破坏。**修订(2026-10-08)**:响应结构继续兼容 `{code, message, data}`,但 Java 版**路径不再复刻 Node**,已改为 RESTful 风格 + `/api` 全局前缀,规则见 1.4。
3. **学习优先，允许简化。** 本文第九节把工程拆成 11 个由易到难的阶段。每个阶段先跑通最小 demo，再对齐真实接口。卡住的复杂模块（如 AI 向导）标注为"进阶选做"，不影响核心学习闭环。

### 1.3 AI 开发时的自查清单

每次生成 Java 代码前，AI 应确认：

- [ ] 响应体是 `{code, message, data}`，HTTP 状态码规则符合第六节
- [ ] 接口路径遵守 1.4 的 Java 版风格:全部在 `/api` 前缀下(由 WebConfig 全局自动加,controller 里不重复写);**不再使用 `/token` 路径段表达鉴权**(阶段 4 改用注解 + 拦截器方案)
- [ ] SQL 表名/字段名与第五节一致（`wz_users` 而不是 `users`，`avatar_url` 而不是 `avatarUrl`）
- [ ] 密码用 BCrypt；校验逻辑能识别 `$2b$` 前缀（见 6.4）
- [ ] 分页参数名为 `page` / `pageSize`（与现有一致），不是 `current` / `size`

### 1.4 Java 版接口风格修订(2026-10-08 起,优先级高于第六、七节的路径描述)

> 背景:Java 版尚未接前端,正是自定接口规范的窗口期。经决策,弃用 Node 版「`/token` 路径段表鉴权」的约定,改为业界主流写法。第七节的 Node 接口清单**仍然保留**,作为"业务行为"的对照来源;Java 版落地时按本节规则转换路径。

**新约定(此后所有模块遵守):**

1. **全局前缀 `/api`**:所有接口统一挂在 `/api` 下。由 `config/WebConfig#configurePathMatch` 对全部 `@RestController` 自动加前缀,controller 里不重复写。旧路径 `/xxx` → 新路径 `/api/xxx`。
2. **RESTful 路由**:资源名用复数名词,操作语义交给 HTTP 动词 —— `GET` 列表 / `GET /{id}` 详情 / `POST` 新建 / `PUT /{id}` 更新 / `DELETE /{id}` 删除。`POST /categories/token/` 这类"动词藏路径"的写法废除。
3. **鉴权方式**:路径不再承担鉴权信息。阶段 4 实现为「自定义注解(如 `@RequireLogin` / `@RequireRole`)+ 拦截器读注解判断」,具体注解名届时定。
4. **RPC 式动词路径保留为例外**:无法自然归入资源 CRUD 的操作(如点赞 toggle、批量删除),允许 `POST /api/likes/articles/{id}/toggle` 这类动词结尾路径,到对应模块再定,不强行 RESTful。
5. **响应壳不变**:仍统一 `{code, message, data}`,业务错误仍是 HTTP 200 + body.code(6.1 节规则继续有效)。等 Java 版前端适配稳定后,再评估是否切到纯 REST 的 HTTP 状态码语义。

**已完成模块的路径映射:**

| 旧(Node 风格) | 新(Java 版) |
|---|---|
| `GET /ping` | `GET /api/ping` |
| `GET /categories` | `GET /api/categories` |
| `POST /categories/token/` | `POST /api/categories` |
| `PUT /categories/token/:id` | `PUT /api/categories/{id}` |
| `DELETE /categories/token/:id` | `DELETE /api/categories/{id}` |

---

## 二、现有 Node 后端架构总览

### 2.1 基本信息

| 项 | 值 |
|---|---|
| 运行时 | Node.js（≥14.17，服务器为 14，代码因此用 axios 而非原生 fetch） |
| 框架 | Express ~4.16 |
| 入口 | `Express/bin/www`（启动），`Express/app.js`（应用装配） |
| 端口 | `8021`（环境变量 `PORT` 可覆盖） |
| 数据库 | MySQL ≥8.0，库名 `wztest`（见 `Express/.env` 的 `DB_NAME`），utf8mb4 |
| 连接方式 | `mysql2/promise` 连接池（`common/pool.js`），**原生 SQL，无 ORM** |
| API 文档 | swagger-jsdoc + swagger-ui-express，挂载在 `/api-docs` |
| 开发热重载 | nodemon |
| 前端联调 | Vue3 前端 `VITE_API_BASE_URL` 指向 `http://localhost:8021`，接口**无统一前缀**（如直接 `/articles`、`/users/login`） |

### 2.2 分层结构（Express 目录 → Java 包的映射参考）

```
Express/
├── app.js            # 应用装配：中间件链 + 路由挂载 + 全局错误处理  → Java: 启动类 + WebMvcConfigurer + Interceptor
├── routes/*.js       # 路由定义（path → controller 方法）           → Java: @RestController 的 @GetMapping 等
├── controllers/*.js  # 控制器：参数解析、调用 service、组装响应      → Java: controller 包
├── services/*.js     # 业务逻辑与 SQL（service 里直接写 SQL）       → Java: service 包 + mapper 包（SQL 下沉到 Mapper）
├── common/           # pool.js(连接池) response.js(统一响应) jwt.js(密钥) aiConfig.js → Java: common/config 包
├── config/swagger.js # Swagger 配置                                → Java: springdoc 配置类
└── public/           # 静态资源（上传的图片存这里）                  → Java: 本地目录 + 静态资源映射
```

### 2.3 中间件链（app.js 中的执行顺序，Java 需一一对应）

按注册顺序：

1. `trust proxy`（取真实 IP）
2. **CORS**：`cors()` 全放开
3. **时间戳防重放**：请求头 `X-Request-Time`（毫秒时间戳）若存在，与服务器时间差 >30 秒则拒绝，返回 `200 + {code:400, message:'请求已过期...'}`（注意：HTTP 是 200）
4. `multer().any()`：全局文件上传解析，落盘到 `./public/upload/Filerelay/`
5. morgan 日志 / json / urlencoded / cookie-parser / 静态资源
6. Swagger（`/api-docs`，无需登录）
7. **可选 Token 解析**：URL 不含 `/token` 且带 `Authorization: Bearer xxx` 时，解析 JWT 挂到 `req.user`；无效则静默忽略（供"游客可看、登录后多显示信息"的接口用）
8. **强制鉴权中间件**：URL 包含 `/token` 时必须携带合法 JWT，并做角色判断 + 回库校验用户状态（详见 6.2）
9. 各业务路由（见第七节清单）
10. 404 处理 → 全局错误处理（统一 JSON 输出）

---

## 三、Node → Java 技术栈对照表

| 职责 | Node 侧（现状） | Java 侧（目标） | 备注 |
|---|---|---|---|
| Web 框架 | Express 4 | Spring Boot Web (Spring MVC) | 注解式路由 |
| 数据库驱动 | mysql2 3.6 | `com.mysql:mysql-connector-j`（版本由 Boot 管理） | |
| 连接池 | mysql2 内置池（10 连接） | HikariCP（Spring Boot 默认内置） | |
| 数据访问 | 手写 SQL + 占位符 `?` | MyBatis-Plus（CRUD 接口化 + 可写 SQL） | 与"手写 SQL"习惯最接近 |
| JWT | jsonwebtoken 9 | jjwt 0.13.0（api/impl/jackson 三件套） | 见 6.3 |
| 密码加密 | bcrypt 5（哈希前缀 `$2b$`） | BCrypt（Spring Security 的 BCryptPasswordEncoder，或 Hutool BCrypt） | 存量哈希兼容见 6.4 |
| 文件上传 | multer 1.4 | Spring `MultipartFile` + 本地存储 + 静态资源映射 | |
| 跨域 | cors 中间件 | `WebMvcConfigurer#addCorsMappings` 或 CorsFilter | |
| 环境变量 | dotenv + `.env` | `application.yml` + 环境变量 / `application-local.yml` | |
| 日志 | morgan | Spring Boot 默认 Logback（`spring-boot-starter-logging`） | |
| API 文档 | swagger-jsdoc + swagger-ui | springdoc-openapi（Boot 3 用 v2.x / Boot 4 用 v3.x） | `/swagger-ui.html` 或配 `/api-docs` 路径 |
| 热重载 | nodemon | spring-boot-devtools（可选） | |
| HTTP 客户端（调微信/智谱 API） | axios | `RestClient`（Spring 6.1+ 内置，同步）或 WebClient | |
| 参数校验 | 手写 if | `spring-boot-starter-validation`（Jakarta Validation） | 学习期可先手写 |
| 工具库 | moment / uuid / crypto | Hutool 5.8.x（时间、UUID、验证码、BCrypt 等） + JDK 内置 | |
| 验证码 | 自研数学验证码（内存 Map，见 6.5） | 复刻同款（Hutool 有图形验证码；但为接口兼容建议复刻数学题方案） | |
| 限流（AI 接口） | 内存 Map 滑窗 10 次/分 | 复刻同款内存限流（进阶可换 Bucket4j/Redis） | |
| 二维码 | 微信云 API / 第三方（utils.js 为遗留代码） | ZXing（仅扫码登录需要生成小程序码时用微信 API 即可） | |

---

## 四、目标 Java 技术栈（2026-09 主流推荐）

### 4.1 推荐组合（学习主线，版本已核实）

| 组件 | 推荐版本 | 说明 |
|---|---|---|
| **JDK** | **21（LTS）** | 教程与生态覆盖最广的 LTS；JDK 25 也是 LTS（2025-09 发布），Spring Boot 3.5 后期补丁与 4.x 均支持，追新可换 |
| **Spring Boot** | **3.5.16** | 3.5 线的最后一个开源版本（2026-06-25 发布）。选它的理由：AI 生成代码命中率最高、中文教程最多、生态（MyBatis-Plus/springdoc/Sa-Token）全部成熟。注意：3.5 线的开源维护已于 3.5.16 结束——学习项目无影响；若在意，用下面的 4.x 线 |
| **构建工具** | Maven（稳定版即可） | 国内教程主流；Gradle 亦可 |
| **ORM** | MyBatis-Plus 3.5.x，依赖坐标 `com.baomidou:mybatis-plus-spring-boot3-starter` | 具体小版本以 [baomidou.com](https://baomidou.com) / Maven Central 最新为准 |
| **API 文档** | springdoc-openapi v2.x（`springdoc-openapi-starter-webmvc-ui`） | 具体小版本以 [springdoc.org](https://springdoc.org) 为准 |
| **JWT** | jjwt 0.13.0（2025-08-20 发布，当前最新） | `jjwt-api` + `jjwt-impl`(runtime) + `jjwt-jackson`(runtime) |
| **工具库** | Hutool `cn.hutool:hutool-all:5.8.x`（5.8.44 为 2026-03 的 5.x 稳定版；6.x 尚在里程碑阶段，勿用） | |
| **数据库** | MySQL 8.0+，库名沿用现有库 | 脚本：`sql/wz_blog_schema.sql` + `sql/migrations/01~04` |

### 4.2 追新替代线（Spring Boot 4.x）

如果想学当前最新代（2026-09 现状：Spring Boot 最新为 **4.1.1**，4.0.x 在维护中），需要同步替换：

| 组件 | Boot 3 线 | Boot 4 线（替换为） |
|---|---|---|
| Spring Boot | 3.5.16 | 4.1.x（Spring Framework 7，要求 JDK 17+，兼容至 26） |
| MyBatis-Plus | `mybatis-plus-spring-boot3-starter` | `mybatis-plus-spring-boot4-starter`（官方已发布） |
| springdoc | v2.x | v3.x（模块化，依赖名有变化） |
| Sa-Token（可选鉴权框架） | `sa-token-spring-boot3-starter` | 1.45.0 起官方支持 Boot 4，坐标以官方文档为准 |

> **给 AI 的提醒**：训练语料中 Boot 3 代码远多于 Boot 4，若用户选择了 4.x 线，凡是 starter 坐标、文档配置、注解差异处，先查官方文档确认再写，不要按 Boot 3 习惯照搬。

### 4.3 本项目明确不引入的东西（保持学习范围收敛）

- **Redis**：现有 Node 版没有 Redis（验证码、限流都是内存 Map）。学习版照旧用内存实现即可；Redis 作为独立进阶练习。
- **Spring Security 完整框架**：现有 RBAC 逻辑很简单（见 6.2），用"一个 JWT 拦截器 + 角色判断"复刻即可，学习价值更高；Security 全家桶可作为后续独立学习课题。Sa-Token 是更轻的替代选项，可选。
- **微服务 / 网关 / 消息队列**：单体足矣。

### 4.4 环境准备清单

1. JDK 21（Temurin / Oracle 均可），`java -version` 验证
2. Maven 3.9+（或用 IDEA 自带），`mvn -v` 验证
3. MySQL 8.0+：导入 `sql/wz_blog_schema.sql`，再按顺序执行 `sql/migrations/01~04`（03、04 是 `IF NOT EXISTS`/幂等脚本）
4. IDEA（社区版即可）+ 插件 Lombok
5. 用 [Spring Initializr](https://start.spring.io) 建项目：Maven / Jar / Java 21 / Boot 3.5.x，勾选 `Spring Web`、`Lombok`、`MySQL Driver`、`Validation`（MyBatis-Plus、jjwt、Hutool 后续手动加）

---

## 五、数据库设计

### 5.1 总览（18 张表）

来源：`sql/wz_blog_schema.sql`（15 张）+ `sql/migrations/03-ai-session-stats.sql`（2 张）+ `sql/migrations/02-normalize-image-urls.sql` 第 3 节（1 张）。

| # | 表名 | 用途 | 模块 |
|---|---|---|---|
| 1 | `wz_users` | 用户（含角色/积分/refresh_token/找回密码计数） | 用户 |
| 2 | `wz_categories` | 文章分类 | 内容 |
| 3 | `wz_tags` | 标签 | 内容 |
| 4 | `wz_articles` | 文章（含计数字段、软删除） | 内容 |
| 5 | `wz_article_tags` | 文章↔标签 多对多 | 内容 |
| 6 | `wz_comments` | 评论（支持楼中楼，status 审核） | 互动 |
| 7 | `wz_article_likes` | 文章点赞（article_id+user_id 唯一） | 互动 |
| 8 | `wz_article_favorites` | 文章收藏（同上） | 互动 |
| 9 | `wz_messages` | 留言板（status 审核） | 互动 |
| 10 | `wz_otherswitch` | 站点配置 KV（站点名、敏感词 JSON、公告等） | 系统 |
| 11 | `wz_friendslink` | 友情链接 | 系统 |
| 12 | `wz_swiper` | 轮播图 | 系统 |
| 13 | `wz_points_goods` | 积分商品（实物/称号两类） | 商城 |
| 14 | `wz_points_orders` | 兑换订单（含收货人、物流信息） | 商城 |
| 15 | `wz_user_points_log` | 积分流水（`change` 可正可负） | 商城 |
| 16 | `wz_ai_sessions` | AI 会话（游客 user_id 为 NULL） | AI |
| 17 | `wz_ai_messages` | AI 消息（含 tool 角色消息） | AI |
| 18 | `wz_qr_login_sessions` | PC 扫小程序码登录会话（状态机） | 登录 |

> 迁移 04 还会插入一个占位账号 `deleted-user`（username，status=0，昵称"已注销用户"），用于用户注销后的内容换绑，并将 `wz_articles.author_id`、`wz_points_orders.user_id`、`wz_user_points_log.user_id` 的外键从 CASCADE 收紧为 RESTRICT。
> 另有一张**不在脚本中**的遗留表 `activity`（`controllers/activity.js` 在用），属历史遗留代码，不在 schema 里——**Java 版直接跳过该模块**。

### 5.2 用户表 `wz_users`

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int AI PK | |
| username | varchar(100) UK | 登录名 |
| password | varchar(255) | **BCrypt 哈希**（Node 版为 `$2b$` 前缀） |
| email | varchar(150) UK 可空 | 找回密码用 |
| nickname | varchar(100) 可空 | 展示昵称 |
| avatar_url | varchar(255) 可空 | 默认头像 URL |
| role | enum('user','editor','admin') 默认 'user' | 角色 |
| is_root | tinyint(1) 默认 0 | 1=管理员（与 role 冗余，判断权限用它） |
| status | tinyint(1) 默认 1 | 1=正常 0=禁用 |
| points | int 默认 0 | 积分余额 |
| title | varchar(50) 可空 | 当前称号（商城兑换） |
| openid | varchar(200) 可空 | 微信小程序 openid |
| last_login_at | timestamp 可空 | |
| created_at / updated_at | timestamp | 自动维护 |
| refresh_token | varchar(512) 可空 | 刷新令牌（落库） |
| refresh_token_expires_at | datetime 可空 | |
| reset_attempt_count | int 默认 0 | 当日找回密码错误次数 |
| reset_attempt_date | date 可空 | |

### 5.3 内容表

**`wz_categories`**：`id` PK / `name` varchar(100) UK / `description` text 可空 / `sort_order` int 默认 1 / `created_at`

**`wz_tags`**：`id` PK / `name` varchar(50) UK / `created_at`

**`wz_articles`**：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int AI PK | |
| title | varchar(255) | |
| summary | varchar(500) 可空 | 摘要 |
| cover_url | varchar(255) 可空 | 封面图 |
| content | longtext | 富文本正文 |
| category_id | int FK→categories | 分类 |
| author_id | int FK→users | 作者（迁移 04 后为 RESTRICT） |
| status | tinyint 默认 0 | **0=展示，1=置顶，2=回收站（软删除）** |
| view_count / like_count / comment_count / favorite_count | int 默认 0 | 冗余计数，由互动行为维护 |
| created_at / updated_at | timestamp | |
| view_internal_count / view_external_count | int 默认 0 | 站内/站外阅读来源细分 |

**`wz_article_tags`**：`article_id` + `tag_id` 联合主键，两个 FK 均 CASCADE。

### 5.4 互动表

**`wz_comments`**：`id` PK / `article_id` FK / `user_id` FK 可空（游客评论）/ `parent_id` FK→自身可空（楼中楼）/ `content` text / `status` tinyint 默认 1（**1=已发布，0=待审核，2=屏蔽**）/ `like_count` int / `created_at`

**`wz_article_likes`**：`id` PK / `article_id` FK / `user_id` FK / `created_at`；`UNIQUE(article_id, user_id)`

**`wz_article_favorites`**：结构同上。

**`wz_messages`**：`id` PK / `user_id` FK 可空 / `name` varchar(255) 默认'匿名' / `content` text / `status` tinyint 默认 1（同评论）/ `value` int 默认 25 / `created_at`

### 5.5 系统/商城表

**`wz_otherswitch`**：`id` PK / `name` varchar(255)（配置 key）/ `content` text 可空（可为 JSON）/ `value` int 默认 0 / `deleted` int 默认 0（软删标记）/ `created_at`。已知配置项：`sensitive_words`（JSON 数组）、`site_name`、`site_description`、公告等（见迁移 01 第 4 节）。

**`wz_friendslink`**：`link_id` PK（注意不叫 id）/ `blog_name` / `blog_url` / `blog_theme` 可空 / `blogger_name` 可空 / `contact_info` 可空 / `logo_url` 可空 / `sort_order` 默认 1。

**`wz_swiper`**：`id` PK / `image_url` / `link_url` 可空 / `title` 可空 / `sort_order` 默认 1 / `status` tinyint 默认 1（1 展示 0 隐藏）/ `created_at`。

**`wz_points_goods`**：`id` PK / `name` / `type` enum('physical','title') 默认 physical / `description` 可空 / `image_url` 可空 / `points_cost` int / `stock` int 可空（NULL=不限）/ `status` tinyint 默认 1（1 上架）/ `created_at` / `updated_at`。

**`wz_points_orders`**：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int AI PK | |
| user_id | int FK→users（RESTRICT） | 兑换人 |
| goods_id | int FK→goods（RESTRICT） | 商品 |
| quantity | int 默认 1 | |
| points_cost | int | 单件积分快照 |
| total_points | int | 本次消耗 |
| status | enum('pending','approved','shipped','completed','cancelled') | 订单状态机 |
| receiver_name / receiver_phone / receiver_address | varchar 可空 | 收货信息 |
| logistics_company / logistics_no | varchar 可空 | 物流信息 |
| user_remark / admin_remark | varchar 可空 | 备注 |
| created_at / updated_at | timestamp | |

**`wz_user_points_log`**：`id` PK / `user_id` FK（RESTRICT）/ **`change`** int（正=获得 负=消耗，是 MySQL 保留字，SQL 里要反引号）/ `reason` varchar(255)（见 8.1）/ `created_at`。

### 5.6 AI 与扫码登录表

**`wz_ai_sessions`**：`id` **varchar(64)** PK（UUID）/ `user_id` int 可空**无外键**（游客为 NULL，删用户不级联删会话）/ `channel` varchar(20) 默认 'web' / `message_count` int 默认 0 / `created_at` / `updated_at`（索引：user、updated）。

**`wz_ai_messages`**：`id` **bigint** AI PK / `session_id` varchar(64)（索引 session_id,id）/ `role` enum('user','assistant','tool') / `content` mediumtext（tool 角色存工具结果 JSON）/ `tool_name` varchar(64) 可空 / `cost_ms` int 可空 / `created_at`。

**`wz_qr_login_sessions`**：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint unsigned AI PK | |
| scene_id | varchar(64) UK | 扫码会话 ID（二维码场景值） |
| status | enum('pending','confirmed','completed','expired') | 状态机 |
| user_id | int unsigned 可空 | 确认登录的用户 |
| channel | varchar(32) 默认 'pc' | |
| temp_openid | varchar(128) 可空 | 扫码但未注册/绑定时的 openid |
| bind_token / bind_token_expires_at | varchar(128) / datetime 可空 | 注册/绑定用一次性 token |
| client_ip / user_agent | varchar(45)/varchar(255) 可空 | PC 端信息 |
| created_at / updated_at / expires_at | datetime | expires_at 控制过期 |

---

## 六、全局接口契约

### 6.1 统一响应格式

所有接口返回 JSON：`{ "code": number, "message": string, "data": any }`。

HTTP 状态码与 body code 的对应规则（**注意这是混合约定，Java 版必须原样复刻**，否则前端判断会出错）：

| 场景 | HTTP 状态码 | body.code | 来源（Node） |
|---|---|---|---|
| 成功 | 200 | 200 | `common/response.js#success` |
| 业务校验失败（参数错、余额不足、重名等） | **200** | 400（或自定义如 201） | `fail()`：HTTP 恒为 200，错误码放 body |
| 系统异常 | 500 | 500 | `error()` |
| 未带 Token / Token 过期 / Token 非法 | **401** | 401 | app.js 鉴权中间件（真 401） |
| 已登录但角色无权限 / 账号停用 | **403** | 403 | 同上（真 403） |
| 资源不存在（404 兜底） | 404 | 404 | 全局错误中间件 |
| AI 接口限流 | **429** | 429 | aiController（真 429） |

前端判定逻辑：先看 `res.data.code === 200`（HTTP 200 场景），401/403 拦截器单独处理。Java 版用"全局异常处理器 + Result 封装"复刻。

### 6.2 鉴权路径约定（本系统最大特色，务必保持）

- **路径中包含 `/token` 段 = 必须登录**，例如 `GET /articles/token/list`、`POST /categories/token/`。公开接口一律不含 `/token`。
- 角色规则（来自 app.js）：
  - `is_root === 1`（admin）或 `role === 'editor'`：可访问全部 `/token` 接口；
  - `role === 'user'` 只允许四类：`/likes/token/*`（自己的点赞）、`/favorites/token/*`（自己的收藏）、`/points/token/log`（自己的积分流水）、`PUT /users/token/{自己的id}`（改自己的资料）；
  - 其余情况返回 403 `"当前角色无权限进行此操作"`。
- **每次受保护请求都会回库查用户**：不存在 → 401 `"用户不存在"`；`status=0` → 403 `"账号已停用，请联系管理员"`；并用数据库最新 role/is_root/status 覆盖 token 里的旧值。
- 公开接口若带 `Authorization` 头，解析成功则挂载用户信息，失败静默忽略（不拦截）。

### 6.3 JWT 细节

| 项 | 值 |
|---|---|
| 签名密钥 | 环境变量 `JWT_SECRET`（Node 兜底值 `suxin0203_Blog_mysql`，Java 版放进 yml） |
| Access Token payload | `{ id, username, is_root, role }`，有效期 **2h** |
| Refresh Token payload | `{ id, type: 'refresh' }`，有效期 **7d**，签发后**同时写入** `wz_users.refresh_token` / `refresh_token_expires_at` |
| 刷新接口 | `POST /users/refresh`：校验 refresh token 与库中一致且未过期，换发新 access + refresh |
| 找回密码 token | `{ id, type: 'forgot_reset' }`，有效期 **5m**（校验成功后签发，用于最终重置） |
| 请求头格式 | `Authorization: Bearer <token>` |

Java 实现：jjwt 0.13.x（HS256）；解析失败区分 `ExpiredJwtException`（→"Token过期"）与其他异常（→"Token校验失败"）。

### 6.4 密码哈希（存量账号兼容）

Node 版用 bcrypt 生成 `$2b$` 前缀哈希。Java 版要求：

- 新用户注册：`BCryptPasswordEncoder.encode()`（默认强度 10，生成 `$2a$`）即可；
- **登录校验存量用户**：`BCryptPasswordEncoder.matches()` 在较新 Spring Security 中可直接识别 `$2a/$2b/$2y`（BcryptVersion 支持，见 spring-security issue #3320）。若所用版本不识别 `$2b$`，校验前把哈希前缀 `$2b$` 替换为 `$2a$` 再比对（两者算法兼容）。

### 6.5 数学验证码（登录/注册/找回共用）

现有实现（`userController.js`）：

1. `GET /users/captcha` → 服务端生成 `num1、num2`（各 0~19 随机整数），`captchaId = UUID`，以 `{captchaId: {answer: num1+num2, exp: now+5min}}` 存**内存 Map**（一次性消费），返回 `{captchaId, num1, num2}`；
2. 前端计算 `MD5(String(num1+num2))` 作为 `captchaAnswer` 提交；
3. 服务端取出并**删除**该 captchaId，比对 `MD5(answer)`，5 分钟过期，过期先清理。

Java 版照抄这套协议（Map → `ConcurrentHashMap` + 定时清理），保证前端零改动。

### 6.6 其他全局行为

- **防重放**：请求头 `X-Request-Time` 存在时，与服务器时间偏差 >30s → HTTP 200 + `{code:400, message:'请求已过期或时间偏差过大，请同步系统时间后重试'}`。Java 用 `HandlerInterceptor` 实现。
- **分页约定**：`?page=1&pageSize=8`（默认 page=1，pageSize=8；部分列表接口默认 5~20，见 controller）。返回结构含 `list/total/page/pageSize`（以现有 service 返回为准，AI 对齐时可现场读 Node service）。
- **CORS**：全放开（学习目的）。
- **上传**：图片落盘本地目录；类型限 jpg/png；富文本上传、轮播图上传、图片列表、删图四个接口见第七节。

---

## 七、全量接口清单

> 共 **97 个**路由（含 1 个遗留首页）。`[鉴权]` 列：公开 = 无需登录；登录 = 路径含 `/token`（角色规则见 6.2）。
> Java 版按此清单逐模块对齐；实现某模块时建议同时阅读对应 Node 的 `routes/*.js` 与 `controllers/*.js` 以对齐参数细节。

### 用户模块 `/users`（14 个）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/users/captcha` | 公开 | 获取数学验证码 |
| GET | `/users/me` | 公开(可选token) | 当前用户信息 |
| GET | `/users/token/` | 登录 | 用户列表（管理） |
| GET | `/users/token/:id` | 登录 | 用户详情 |
| PUT | `/users/token/:id` | 登录 | 更新用户（user 仅限自己） |
| DELETE | `/users/token/:id` | 登录 | 删除/注销用户（换绑占位账号，见迁移04） |
| POST | `/users/register` | 公开 | 注册（需验证码） |
| POST | `/users/login` | 公开 | 登录（需验证码，返回双 token） |
| POST | `/users/refresh` | 公开 | 刷新 access token |
| GET | `/users/forgot-email` | 公开 | 找回密码-查询绑定邮箱（脱敏） |
| POST | `/users/forgot-verify` | 公开 | 找回密码-校验邮箱验证码（5 分钟 reset token） |
| POST | `/users/forgot-reset` | 公开 | 找回密码-重置密码 |
| POST | `/users/token/updatePassword` | 登录 | 修改密码 |
| POST | `/users/token/admin-add-user` | 登录 | 管理员添加用户 |

### 文章模块 `/articles`（10 个）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/articles` | 公开 | 前台文章列表（keyword/category_id/tag_id/year/month/page/pageSize） |
| GET | `/articles/token/list` | 登录 | 后台文章列表（含回收站，status 筛选） |
| GET | `/articles/top` | 公开 | 热门榜（view/like/favorite/comment） |
| GET | `/articles/archive` | 公开 | 时间归档 |
| GET | `/articles/:id` | 公开 | 文章详情（`incrementView=1` 时计数，`source=internal/external` 区分来源） |
| POST | `/articles/token/` | 登录 | 发布文章（tags 数组；发文积分奖励，见 8.1） |
| PUT | `/articles/token/:id` | 登录 | 更新 |
| DELETE | `/articles/token/:id` | 登录 | 删除（soft=0 物理删，否则进回收站；扣回发文奖励） |
| PUT | `/articles/token/:id/restore` | 登录 | 从回收站恢复 |
| POST | `/articles/:id/view` | 公开 | 阅读量 +1 |

### 分类 `/categories`、标签 `/tags`（各 4 个，结构相同）

GET `/`（公开列表）、POST `/token/`、PUT `/token/:id`、DELETE `/token/:id`。

### 评论 `/comments`（5 个）

GET `/article/:articleId`（公开，楼中楼树）、GET `/token/`（后台列表）、POST `/`（游客/用户都可发，进审核流程见 8.2）、PUT `/token/:id`（审核：改 status，过审加积分）、DELETE `/token/:id`。

### 点赞 `/likes`、收藏 `/favorites`（2 + 3 个）

- POST `/likes/token/article/:articleId/toggle`（切换点赞，作者得积分）、GET `/likes/article/:articleId/check`（公开，可选 token）
- POST `/favorites/token/article/:articleId/toggle`、GET `/favorites/token/list`（我的收藏）、GET `/favorites/article/:articleId/check`

### 留言板 `/messages`（4 个）

GET `/`、POST `/`（公开，游客可发）、PUT `/token/:id`、DELETE `/token/:id`。

### 积分商城 `/points`（9 个）

| 方法 | 路径 | 鉴权 |
|---|---|---|
| GET | `/points/goods`、`/points/goods/:id` | 公开（仅上架） |
| POST/PUT/DELETE | `/points/token/goods`、`/token/goods/:id` | 登录（管理） |
| GET | `/points/orders`、`/points/orders/:id` | 公开/半公开（列表支持按 user 过滤） |
| POST | `/points/orders` | 公开路径（body 传 user_id；下单扣积分事务） |
| PUT | `/points/token/orders/:id` | 登录（改订单状态/填物流） |
| GET | `/points/token/log` | 登录（user 看自己流水） |

### 看板 `/dashboard`（6 个，均 `/token`）

`/token/stats`（概览）、`/token/article-rank`、`/token/user-trend`、`/token/article-trend`、`/token/traffic-source`、`/token/ai-stats`（AI 使用统计，来自 wz_ai_* 两表）。

### 扫码登录（3 + 3 个）

- PC 端 `/qr-login`：POST `/session`（创建，返回 sceneId）、GET `/session/:sceneId`（轮询状态）、GET `/session/:sceneId/code.png`（生成小程序码图片，调微信 `getwxacodeunlimit` API）
- 小程序端 `/miniapp`：POST `/qr-login/entry`（扫码）、POST `/qr-login/register-and-confirm`（未注册→注册并确认，用 bind_token）、POST `/qr-login/bind-and-confirm`（已注册→绑定并确认）

### 微信登录 `/wechat`（2 个）

GET `/openid/:code`（code 换 openid 并登录/注册）、GET `/userinfo/:openid`。

### 上传 `/upload`（4 个）

POST `/token/rich_editor_upload`（富文本图）、POST `/token/lbt_upload`（轮播图）、GET `/imglist`（公开）、DELETE `/token/delimg`。

### 其余

- 轮播图 `/swiper`（4）、友链 `/friendslink`（4，注意主键是 `link_id`）、站点配置 `/otherswitch`（4）
- 工具 `/utils` GET `/`：**遗留接口**（调第三方生成活动二维码，硬编码 appId），Java 版可跳过
- 活动 `/activity`（5）：**遗留模块**（使用不在 schema 中的 `activity` 表），Java 版跳过
- AI 向导 `/ai`（5）：见第十节
- 首页 `GET /`（1）：健康检查类

---

## 八、核心业务规则速查

### 8.1 积分规则（常量提取自代码）

| 行为 | reason 前缀/值 | 变化 | 日上限 |
|---|---|---|---|
| 每日登录 | `daily_login` | +5 | 1 次/天（查当日是否已有该 reason） |
| 发布文章（过审即发即得） | `article_publish:{articleId}:{title}` | +10/篇 | 100/天 |
| 删除文章 | `article_delete:{id}:{title}` | 扣回该文获得的奖励（按流水回退） | — |
| 文章被点赞 | `article_liked:{articleId}:{title}` | 作者 +1 | 无 |
| 取消点赞 | `article_unliked:{...}` | 作者 -1（回退） | — |
| 评论过审 | `comment_approved:{commentId}:{articleTitle}` | +5/条 | 50/天 |
| 删除评论（已给过分的） | `comment_removed:{...}` | -（扣回） | — |
| 商城兑换 | `redeem_goods`（负值） | 按商品 | — |
| 管理员调整 | `admin:{备注}` | 任意 | — |

实现要点（`pointsService.js`）：所有加积分走"写流水 + 更新 `wz_users.points`"，日上限类用 `SELECT SUM(change>0 AND reason LIKE 'prefix%' AND DATE(created_at)=CURDATE())` 判断已得额度再给差额。Java 版建议封装 `PointsService#addPointsLog` / `addDailyCappedPointsLog`，与点赞/评论/发文在同一事务。

### 8.2 计数一致性

- 点赞/收藏 toggle：唯一键 `(article_id, user_id)` 防重复；切换时同步增减 `wz_articles.like_count / favorite_count`（同事务）。
- 评论新增/删除/过审：维护 `wz_articles.comment_count`。
- 文章阅读：`view_count` 同时 `view_internal_count/view_external_count` 二选一累加。

### 8.3 文章软删除

`status: 0=展示, 1=置顶, 2=回收站`。删除默认 soft（进回收站），`?soft=0` 才物理删除；`PUT /:id/restore` 恢复为 0。

### 8.4 敏感词

存于 `wz_otherswitch`（name=`sensitive_words`，content 为 JSON 数组）。评论/留言创建时过滤/拦截（具体行为见 `commentService/messageService`）。

### 8.5 用户注销（迁移 04）

删除用户前，将其文章、点赞、收藏、评论、订单、积分流水**换绑**到占位账号 `deleted-user`，再删用户行（外键 RESTRICT 保证不换绑删不掉）。

---

## 九、分阶段学习开发路线

> 建议一个分支一个阶段，跑通验收再进下一阶段。每阶段都标注了对应的现有接口，方便对照测试。
> 包结构建议：`com.suxin.wzblog` 下分 `common / config / interceptor / controller / service / mapper / entity / dto / vo`。

### 阶段 0：项目初始化与 Hello World

**目标**：跑起 Spring Boot，理解自动装配与启动流程。

- Initializr 建项目 → 加入 Web/Lombok/MySQL Driver/Validation
- application.yml 配置端口 8021
- 写 `GET /ping` 返回 `Result.ok("pong")`

**验收**：浏览器访问 `http://localhost:8021/ping` 得 JSON。

```java
// common/Result.java —— 全项目统一响应体
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) { return build(200, "success", data); }
    public static <T> Result<T> fail(int code, String message) { return build(code, message, null); }
    private static <T> Result<T> build(int code, String message, T data) {
        Result<T> r = new Result<>();
        r.code = code; r.message = message; r.data = data;
        return r;
    }
}
```

### 阶段 1：连接数据库（第一个查询）

**目标**：数据源 + MyBatis-Plus 跑通，学会看 SQL 日志。

```yaml
# application.yml
server:
  port: 8021
spring:
  datasource:
    # 库名：本地新建库可叫 wz_blog（README 的标准名）；若直连现有库，填 Express/.env 里 DB_NAME 的值（当前为 wztest）
    url: jdbc:mysql://127.0.0.1:3306/wz_blog?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true        # avatar_url -> avatarUrl
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl   # 学习期打印 SQL
  global-config:
    banner: false
    db-config:
      id-type: auto                            # 对应表的自增主键
```

```java
@Entity 对应写法（分类表示例）：
@Data
@TableName("wz_categories")
public class Category {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}

public interface CategoryMapper extends BaseMapper<Category> {}   // 就这一行，CRUD 齐了
```

**最小 demo**：`CategoryController#list()` 用 `categoryMapper.selectList(null)` 返回全部分类。

**验收**：`GET /categories` 能查出库里的分类数据。

### 阶段 2：统一响应 + 全局异常 + 参数校验

**目标**：把 6.1 的响应契约做成基础设施。

- `Result` 已有；补 `BusinessException(code, message)`
- `@RestControllerAdvice` 全局异常处理：业务异常 → HTTP 200 + body code；其他异常 → HTTP 500；404 → HTTP 404（配 `spring.mvc.throw-exception-if-no-handler-found` 或用 `ErrorController`）
- 顺手实现防重放拦截器（`X-Request-Time` 校验，6.6）

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)          // 业务错误：HTTP 200 + body.code
    public Result<Void> business(BusinessException e) { return Result.fail(e.getCode(), e.getMessage()); }

    @ExceptionHandler(Exception.class)                  // 系统错误：HTTP 500
    public Result<Void> other(Exception e) { return Result.fail(500, "服务器错误"); }
}
```

### 阶段 3：分类/标签模块（第一个完整 CRUD）

**目标**：对齐 `GET/POST/PUT/DELETE /categories*`、`/tags*` 共 8 个接口，练习 MyBatis-Plus 的 `selectList/LambdaQueryWrapper`、唯一名校验（重名 → `fail(400, "分类名已存在")`）。

**验收**：用 Postman 走通增删改查；与 Node 版相同请求返回结构一致。

### 阶段 4：认证体系（最重要的一阶段）

**目标**：对齐用户模块核心：验证码、注册、登录、refresh、JWT 拦截器、RBAC。

开发顺序：
1. `CaptchaService`（内存 Map 数学验证码，协议见 6.5）
2. `JwtUtil`（jjwt：`generateToken(user, 2h)` / `generateRefreshToken(id, 7d)` / `parse(token)`）
3. `POST /users/register`、`POST /users/login`（BCrypt 校验，兼容 `$2b$` 见 6.4；登录成功写 refresh_token 落库）
4. `POST /users/refresh`
5. `AuthInterceptor`（复刻 app.js 两个中间件）：

```java
@Component
public class AuthInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String uri = req.getRequestURI();
        String auth = req.getHeader("Authorization");
        if (!uri.contains("/token")) {              // 公开接口：可选解析，失败静默
            if (auth != null) tryParse(req, auth);  // 解析成功挂 request.setAttribute("user", claims)
            return true;
        }
        // 受保护接口：必须合法 token
        Claims user = mustParse(auth, resp);        // 失败写 401 JSON（Token不存在/过期/校验失败）
        checkRoleAndDbStatus(user, req, resp);      // 角色白名单 + 回库验状态，失败写 403/401
        return true;
    }
}
// 注册：registry.addInterceptor(authInterceptor).addPathPatterns("/**")
//      .excludePathPatterns("/api-docs/**", "/swagger-ui/**", "/upload/**", "/ping");
```

**验收**：登录拿 token → 带 token 访问 `/users/token/` 得列表；不带 → 401；user 角色访问 `/articles/token/list` → 403；user 改自己资料 → 200。

### 阶段 5：文章模块

**目标**：对齐 10 个文章接口。新知识点：

- MyBatis-Plus **分页插件**（`PaginationInnerInterceptor`，参数映射成 `page/pageSize` 返回）
- 多表联查：文章列表 join 分类、聚合标签（自定义 XML SQL 或 Wrapper）
- 软删除（status=2 回收站 / restore，见 8.3）
- 阅读计数与来源细分（`POST /:id/view`）
- 发文积分奖励 + 删文回退（调阶段 8 的积分服务，或先 stub）
- 归档接口（`GROUP BY DATE_FORMAT(created_at,'%Y-%m')`）

**验收**：前台列表筛选/翻页正常；后台能看回收站并恢复；发文后用户积分 +10（日上限 100）。

### 阶段 6：互动模块（点赞/收藏/评论/留言）

**目标**：练习**事务**（`@Transactional`）与计数一致性（8.2）。

- toggle 点赞/收藏（唯一键冲突处理 → 删除并回退计数）
- 评论树（`parent_id` 组装两层结构：根评论 + 楼中楼列表）
- 评论/留言审核状态机（0 待审 1 过审 2 屏蔽），过审发积分（日上限 50）
- 敏感词过滤（读 `wz_otherswitch` 的 JSON 数组，`containsAny` 判断）

### 阶段 7：积分商城

**目标**：综合事务练习。下单流程（复刻 `pointsService.createOrder`）：

```
开事务 → 查商品(上架?) → 校验库存 → 校验用户积分 ≥ total_points
→ 写订单(pending) → 写积分流水(-total_points) → 扣用户 points → 减库存 → 提交
任何一步失败 → 整体回滚 → fail(400, 具体原因)
```

订单状态机：`pending → approved → shipped → completed`，可 `cancelled`（取消时回退积分与库存）。

### 阶段 8：文件上传 + API 文档

**目标**：`MultipartFile` 落盘 + 静态资源映射 + springdoc。

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("file:" + uploadDir + "/");   // 对应 Node 的 express.static(public)
    }
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins("*").allowedMethods("*");   // 对应 cors()
    }
}
```

对齐 4 个上传接口；接 springdoc 后把全部接口补上 `@Operation` 注解。

### 阶段 9：看板统计 + 杂项模块

**目标**：纯 SQL 练习。轮播图/友链/站点配置三组 CRUD（各 4 接口）+ 看板 6 个统计接口（趋势图用 `GROUP BY 日期`、排行用 `ORDER BY xxx LIMIT n`、AI 统计查 `wz_ai_*`）。对应前端 ECharts 图表逐个联调。

### 阶段 10：微信与扫码登录（选做）

要点：`RestClient` 调微信 API（`jscode2session`、`getwxacodeunlimit`）；`wz_qr_login_sessions` 状态机（pending→confirmed→completed/expired）；bind_token 一次性；PC 端轮询。配置项 `WX_MINIAPP_APPID/SECRET` 放 yml。

---

## 十、进阶模块实现要点

### 10.1 AI 向导（整个项目最有含金量的模块）

**5 个接口**：`POST /ai/chat/stream`（SSE 流式对话，body 传 `{ message, sessionId? }`）、`GET /ai/history?sessionId=`（游客回放，仅 user/assistant 最近 20 条）、`GET /ai/token/sessions`（后台会话列表）、`GET /ai/token/sessions/:id/messages`、`DELETE /ai/token/sessions/:id`。

**现有实现**（`aiService/aiTools/aiSessionService/aiController`，Java 版逐点复刻）：

| 项 | 值 |
|---|---|
| 提供方 | 智谱 BigModel 开放平台，`https://open.bigmodel.cn/api/paas/v4`，**OpenAI 兼容协议** `POST /chat/completions` |
| 模型 | `glm-4-flash-250414`（免费档；配置备注：`glm-4.7-flash` 免费档限流严重、`glm-4.5-flash` 是推理模型延迟高） |
| Key | 环境变量 `ZHIPU_API_KEY` |
| 上下文 | 系统提示词（站点介绍 + 工具使用规则 + 回答边界）+ 数据库最近 **10** 条历史 + 新问题 |
| 工具 | 6 个：`search_articles`、`list_categories`、`list_tags`、`get_hot_articles`、`get_article_detail`、`get_site_overview`（JSON Schema 定义见 `aiTools.js`，Java 版原样翻译） |
| 调用流程 | ① 工具轮：**非流式**带 tools 调用，模型若返回 `tool_calls` 则本地执行、结果以 `role:'tool'` 消息回填（截断 6000 字符），**最多 2 轮**；② 模型不再要工具 → 该回复即最终答案（一次性下发）；③ 若 2 轮后仍要工具 → 追加收口指令，改走**流式**生成最终回答 |
| 守卫 | 首轮命中站点数据意图正则但模型没调工具 → 打回强制重试一次 |
| 会话 | `sessionId`（UUID）由服务端生成/续用；`wz_ai_sessions` 记会话，`wz_ai_messages` 逐条落库（含 tool 消息与耗时）；游客 user_id 为 NULL |
| 限流 | 每 IP 10 次/分钟（内存 Map），超限 HTTP 429 |
| 长度 | 单问 ≤600 字 |
| 断开 | 客户端断开（`close` 事件）→ 取消对上游的请求 |

**SSE 事件协议**（`Content-Type: text/event-stream`，`X-Accel-Buffering: no`；每条 `data: <JSON>\n\n`）：

```
data: {"sessionId":"..."}                         // 首条：会话 ID
data: {"tool":{"name":"search_articles","brief":"搜索文章"}}   // 工具调用提示
data: {"delta":"部分回答文本"}                     // 最终回答（一次性或流式增量）
data: {"error":"友好错误文案"}                     // 出错（此时不能再改 HTTP 状态码）
data: [DONE]                                      // 结束标记
```

**Java 实现建议**：

- SSE 用 `SseEmitter`（MVC）或 WebFlux `Flux<String>`；注意复刻 `[DONE]` 结尾与 error 事件语义；
- 调智谱用 `RestClient`（流式解析 SSE 行）——协议是 OpenAI 兼容的，Node 版就是 axios 直连；也可用官方 SDK（坐标以 [bigmodel 开放平台文档](https://open.bigmodel.cn) 为准，不要凭记忆猜）；
- 工具执行器做成 `Map<String, Function<Map<String,Object>, Object>>`，每个工具对应一条真实 SQL（搜索用 `LIKE`，热门用 `ORDER BY view_count DESC LIMIT` 等，SQL 文本直接翻 `aiTools.js`）。

### 10.2 与 Node 版的行为差异清单（Java 版允许的改进）

以下差异**不影响前端**，鼓励在 Java 版中做得更规范：

- HTTP 状态码可以继续用"业务错误=HTTP 200"旧约定（推荐，前端零改动），但代码里用 `BusinessException` 语义化；
- `wz_user_points_log.change` 这类保留字字段，MyBatis-Plus 实体用 `@TableField("\`change\`")` 显式标注；
- 事务边界显式化（Node 版是"同一接口里顺序 SQL"，Java 版 `@Transactional` 更严谨）；
- 密码错误次数、验证码清理等内存态，可以顺手改成定时任务/`Caffeine`，但接口行为不变。

---

## 十一、给 AI 的重构提示词模板

> 使用方法：新开会话，把本文件内容（或文件路径）+ 下面的模板一起发给 AI。

```text
你是一名资深 Java 后端工程师，同时是我的 Java 学习导师。请阅读《Java版后端重构指南.md》，
按照其中第九节的阶段规划，带我从零开始用 Java 重构文栈博客的 Node.js 后端。

约束：
1. 这是学习项目：每一步先给我讲解涉及的知识点（是什么/为什么/怎么用），再给代码；
   代码要逐段解释，不要一次甩给我整个工程。
2. 技术栈严格遵守指南第四节（Spring Boot 3.5.16 + JDK 21 + MyBatis-Plus + jjwt + Hutool）。
   依赖版本如果不确定，先让我去 Maven Central 确认，不要编造版本号。
3. 数据库直接复用指南第五节的 18 张表（我本地已导入 wz_blog 库），表名和字段名不许改。
4. 接口路径、参数名、响应结构必须与指南第六、七节完全一致（包括 /token 鉴权路径约定、
   {code,message,data} 响应体、HTTP 200 承载业务错误的规则）。
5. 当前进度：我们从阶段 [X] 开始。每完成一个接口，用 curl 命令给出验收方法。
6. 我卡住时，允许你让我阅读对应的 Node 源码文件（指南中有路径），并用 Java 等价实现。

现在请开始：介绍阶段 [X] 的目标、涉及知识点清单、以及第一步做什么。
```

---

## 十二、版本核实记录与来源

以下版本信息于 2026-09-23 联网核实：

| 结论 | 来源 |
|---|---|
| Spring Boot 最新为 4.1.1；4.0.x 维护中；3.5.16 为 3.5 线最后开源版（2026-06-25） | [spring.io/projects/spring-boot](https://spring.io/projects/spring-boot)、spring.io 博客 |
| MyBatis-Plus 稳定主线 3.5.x；Boot 3 用 `mybatis-plus-spring-boot3-starter`，Boot 4 用 `mybatis-plus-spring-boot4-starter`（官方已发布） | [baomidou.com](https://baomidou.com)、Maven Central |
| springdoc v2.x ↔ Boot 3；v3.x ↔ Boot 4（模块化） | [springdoc.org](https://springdoc.org)、[GitHub Releases](https://github.com/springdoc/springdoc-openapi/releases) |
| jjwt 最新 0.13.0（2025-08-20），拆分为 api/impl/jackson | [mvnrepository](https://mvnrepository.com/artifact/io.jsonwebtoken/jjwt)、[jwtk/jjwt](https://github.com/jwtk/jjwt) |
| Hutool 5.x 稳定线 5.8.44（2026-03）；6.x 仍为里程碑（包名改为 org.dromara.hutool），生产/学习均建议 5.8.x | [hutool.cn](https://hutool.cn)、Maven Central |
| Sa-Token 最新 1.46.0（2026-08-19）；1.45.0 起支持 Spring Boot 4 | [sa-token.cc](https://sa-token.cc)、[Gitee dromara/sa-token](https://gitee.com/dromara/sa-token) |
| Spring Security BCrypt 对 `$2b$` 的支持与兼容（BcryptVersion） | [spring-security issue #3320](https://github.com/spring-projects/spring-security/issues/3320) |

> 未给出精确小版本的依赖（MyBatis-Plus、springdoc 的具体 3.5.x/2.x/3.x 号），是因为其发布频繁且搜索结果未给出权威"当前版"数字——**以 Maven Central 检索结果为准**，这本身也是给 AI 的正确姿势。
