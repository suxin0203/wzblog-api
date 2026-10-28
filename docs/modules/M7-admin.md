# M7 · 运营后台（看板 / 配置 / 轮播 / 友链 / 审计）

> 负责人 Agent：M7 Agent
> 依赖模块：M0（配置缓存/审计/调度）、M1、M2、M3、M4、M5、M6
> 对应阶段：阶段 7；`/admin/configs`、`/public/configs`、轮播、友链建议前移到阶段 1~2 与前端联调
> 状态：已定稿
> 契约版本：以 [00-文档索引与约定](../00-文档索引与约定.md) 1.6 为准

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.modules.admin` |
| 表 | `site_configs`、`banners`、`friend_links`、`daily_stats`、`article_daily_stats`、`audit_logs` |
| 对外事件 | 发布：无；订阅：无 |
| 依赖的 app 服务 | M0 `SiteConfigService`、`AuditRecorder`、缓存/调度；M5 `FileAppService`（key 校验）；M6 `StatsService`（AI 今日消息） |
| 只读聚合 | 自有 `AnalyticsMapper`（跨表只读，见 5.2）；M1~M4 的 `*StatsService` 仅作补充 |

## 2. 范围与非目标

- 做：6 个看板统计；站点配置后台读写与公开配置；轮播图 CRUD 与公开列表；友链 CRUD 与公开列表；审计日志只读查询。
- 做：每日预聚合（00:10）、ShedLock、UPSERT 幂等、按日期区间重跑、看板 Redis 缓存 5 分钟。
- 不做：不写任何业务数据（文章/评论/积分/订单/用户/AI 均由所属模块负责）。
- 不做：不做文件上传、预签名、对象删除；图片 key 只做存在性校验并交 M5 管理。
- 不做：不做审计写入与审计删除；写入统一走 M0 `AuditRecorder`。
- 只读分析例外：仅用于看板/统计，禁止用于业务校验与写入路径（见 5.2）。

## 3. 路由清单

> 与 00 文档第 4 节逐字一致，共 20 条；不得新增/改名/移动。`/admin/ai/stats` 归 M6，不在本模块路由内。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/admin/dashboard/overview` | ADMIN | 概览指标（实时 + 当日） |
| GET | `/admin/dashboard/article-trend` | ADMIN | 文章趋势（按日 view/like/comment/publish） |
| GET | `/admin/dashboard/user-trend` | ADMIN | 用户趋势（注册/活跃） |
| GET | `/admin/dashboard/traffic-source` | ADMIN | 站内/站外来源占比 |
| GET | `/admin/dashboard/article-rank` | ADMIN | 文章阅读/点赞/收藏排行 |
| GET | `/admin/dashboard/points-stats` | ADMIN | 积分发放/消耗/兑换/退款趋势 |
| GET | `/admin/configs` | ADMIN | 全部配置（读库，不走公开缓存） |
| PUT | `/admin/configs/{key}` | ADMIN | 更新单键配置并失效缓存 |
| GET | `/public/configs` | PUBLIC | 公开配置 map（白名单） |
| GET | `/banners` | PUBLIC | 生效中的轮播，按 sort 升序 |
| GET | `/admin/banners` | ADMIN | 轮播分页列表 |
| POST | `/admin/banners` | ADMIN | 新建轮播 |
| PUT | `/admin/banners/{id}` | ADMIN | 全量更新轮播 |
| DELETE | `/admin/banners/{id}` | ADMIN | 物理删除轮播 |
| GET | `/friend-links` | PUBLIC | 启用友链，按 sort 升序 |
| GET | `/admin/friend-links` | ADMIN | 友链分页列表 |
| POST | `/admin/friend-links` | ADMIN | 新建友链 |
| PUT | `/admin/friend-links/{id}` | ADMIN | 全量更新友链 |
| DELETE | `/admin/friend-links/{id}` | ADMIN | 物理删除友链 |
| GET | `/admin/audit-logs` | ADMIN | 审计日志分页查询（只读） |

## 4. DTO 契约

### 4.1 请求

所有 ID 入参为字符串；时间入参 ISO-8601 且带时区。**显式例外**：6 个看板接口的 `from`/`to` 固定为 `YYYY-MM-DD`（`Asia/Shanghai` 自然日、闭区间），这是契约 ISO 时间规则的唯一统计类例外（00 §5.5 业务日口径）；`AuditLogQuery` 的 `from`/`to` 仍为 ISO-8601。分页 `page` 默认 1、`pageSize` 默认 20/最大 100。

| DTO | 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|---|
| `DashboardRange` | `days` | int | 否 | 仅 7/30/90，默认 30；与 `from/to` 互斥 |
| | `from` / `to` | date | 否 | `YYYY-MM-DD`（契约显式例外），Asia/Shanghai 自然日，闭区间；必须成对；`to>=from`、`to<=今天`、跨度<=90 天 |
| `ConfigUpdateReq` | `value` | JSON | 是 | 非 null；按 `value_type` 校验（6.1） |
| | `type` | string | 否 | 必须等于当前 `value_type`，否则 `CONFIG_VALUE_TYPE_INVALID` |
| | `description` | string | 否 | <=255；传空串表示清空，缺省不改 |
| | `isPublic` | boolean | 否 | 敏感键不可置 true |
| `BannerUpsertReq` | `title` | string | 否 | <=100 |
| | `imageKey` | string | 是 | <=255；须为 M5 中 `ACTIVE` 且 `biz_type=BANNER` 的 key |
| | `linkUrl` | string | 否 | <=500；`http://`/`https://` 或 `/` 开头相对路径 |
| | `sortOrder` | int | 否 | 默认 0 |
| | `status` | enum | 否 | `ENABLED`/`DISABLED`，默认 `ENABLED` |
| | `startAt` / `endAt` | datetime | 否 | ISO；`endAt > startAt`，否则 `COMMON_VALIDATION_ERROR` |
| `FriendLinkUpsertReq` | `blogName` | string | 是 | <=100 |
| | `blogUrl` | string | 是 | <=500；仅 `http://`/`https://` |
| | `theme` | string | 否 | <=100 |
| | `bloggerName` | string | 否 | <=100 |
| | `contact` | string | 否 | <=100；仅后台可见 |
| | `logoKey` | string | 否 | <=255；M5 存在性校验 |
| | `sortOrder` | int | 否 | 默认 0 |
| | `status` | enum | 否 | `ENABLED`/`DISABLED`，默认 `ENABLED` |
| `AuditLogQuery` | `operatorId` | string | 否 | BIGINT 字符串 |
| | `action` | enum | 否 | 仅 00 §6.1 的 19 个 `AuditAction` |
| | `targetType` | enum | 否 | 仅 00 §6.1 的 11 个 `AuditTargetType` |
| | `from` / `to` | datetime | 否 | ISO；默认最近 7 天；跨度<=90 天 |
| | `page` / `pageSize` | int | 否 | 见分页约定 |
| `ArticleRankQuery` | `metric` | string | 否 | `view`/`like`/`favorite`，默认 `view` |
| | `limit` | int | 否 | 1~50，默认 10 |
| | `DashboardRange` | — | 否 | 同上 |

### 4.2 响应

| VO | 字段 | 类型 | 说明 |
|---|---|---|---|
| `ConfigVO` | `key` / `valueType` / `description` / `isPublic` / `updatedBy` / `updatedAt` | string/string/string/boolean/string/ISO | 后台全量；`value` 为对应类型的 JSON |
| | `value` | JSON | 按 `value_type` 返回原始 JSON 类型 |
| `PublicConfigsVO` | 动态 key -> JSON | map | 只含白名单键，值为对应 JSON 类型 |
| `BannerVO` | `id` / `title` / `imageKey` / `imageUrl` / `linkUrl` / `sortOrder` / `status` / `startAt` / `endAt` / `createdAt` / `updatedAt` | string... | `imageUrl` 由 M5 统一解析，解析失败为 null；管理端返回全部字段 |
| `FriendLinkVO` | `id` / `blogName` / `blogUrl` / `theme` / `bloggerName` / `logoKey` / `logoUrl` / `sortOrder` / `status` / `createdAt` / `updatedAt` | string... | 公开列表不返回 `contact`；管理端返回 |
| `OverviewVO` | `businessDate` / `asOf` | date/ISO | 业务日与查询时刻 |
| | `users.total` / `users.todayNew` | long | 见 6.4 |
| | `articles.total` / `articles.published` / `articles.todayPublished` | long | 见 6.4 |
| | `comments.total` / `comments.pending` | long | 见 6.4 |
| | `interactions.todayViews` / `todayLikes` / `todayFavorites` | long | 见 6.4 |
| | `ai.todayMessages` | long | 见 6.4 |
| | `points.todayIssued` / `todayConsumed` | long | 见 6.4 |
| | `orders.pending` | long | 见 6.4 |
| `ArticleTrendVO` | `from` / `to` / `timezone` / `points[]` | date/date/string/array | `points[]` 每日 `date,views,likes,comments,published` |
| `UserTrendVO` | `from` / `to` / `timezone` / `points[]` | 同上 | `points[]` 每日 `date,registered,active` |
| `TrafficSourceVO` | `from` / `to` / `internalViews` / `externalViews` / `internalRatio` / `externalRatio` / `basis` | date/date/long/long/double/double/string | 比例 0~1；数据取 `daily_stats.view_internal_count/view_external_count` 区间求和；`basis=PERIOD_SUM`（区间增量，非累计） |
| `ArticleRankVO` | `metric` / `from` / `to` / `list[]` | string/date/date/array | 每项 `rank,articleId,title,slug,viewCount,likeCount,favoriteCount` |
| `PointsStatsVO` | `from` / `to` / `timezone` / `points[]` | 同上 | 每日 `date,issued,consumed,redeemed,refunded` |
| `AuditLogPageVO` | `list,total,page,pageSize,hasNext` | 分页 | 每项 `id,operatorId,action,targetType,targetId,before,after,ip,createdAt` |

## 5. 数据表

| 表 | 用途 | 关键字段/索引 |
|---|---|---|
| `site_configs` | 配置 KV（JSON 值） | `uk_config_key`、`idx_public` |
| `banners` | 轮播 | `idx_status_sort(status, sort_order)` |
| `friend_links` | 友链 | `idx_status_sort(status, sort_order)` |
| `daily_stats` | 站点日统计（当日增量） | `PRIMARY(stat_date)`；含 `view_internal_count` / `view_external_count` |
| `article_daily_stats` | 文章日统计（当日增量） | `PRIMARY(article_id, stat_date)`、`idx_date`；含 `favorite_count` |
| `audit_logs` | 管理操作留痕 | `idx_operator`、`idx_target`、`idx_created` |

### 5.1 字段与约束

只引用 03 文档 3.7，不重复定义；`config_value` 为合法 JSON，STRING 也以 JSON 字符串存储（`"文栈博客"`）。`banners`/`friend_links` 无软删列，删除为物理删除。

- `daily_stats` 含 `view_internal_count` / `view_external_count`（当日站内/站外阅读）：`traffic-source` 只看这两列，不再读 `articles` 的累计字段。
- `article_daily_stats` 含 `favorite_count`（当日收藏）：排行可用 `favorite_count`；本表 `view/like/favorite/comment` 全部为**当日增量**，不是累计值。
- 索引：`points_ledger`、`login_audits`、`points_orders` 已新增 `idx_created(created_at)`（03 与 DDL 已固化），看板区间聚合依赖该索引；`audit_logs` 已有 `idx_created`。

### 5.2 只读分析例外（AnalyticsMapper）

- 允许读取：`articles`、`comments`、`article_reactions`、`points_ledger`、`points_orders`、`users`、`login_audits`、`ai_messages`。
- 禁止写入上述 8 表；禁止 `SELECT ... FOR UPDATE`、`LOCK IN SHARE MODE`、临时表、跨库 JOIN。
- 所有语句只读、无锁、单语句超时 3 秒；不加入业务事务；失败按指标降级（空值补 0），不抛业务异常。
- 仅 `DashboardAppService`/`StatsAggregationService` 可注入该 Mapper；ArchUnit 校验其他包不可访问。

## 6. 状态机与业务规则

### 6.1 站点配置

- 读取：`GET /admin/configs` 直读数据库，按 `config_key` 升序，不分页；`GET /public/configs` 只返回公开白名单。
- 公开白名单：`is_public=1` 且 key 不属于敏感集合。敏感集合固定为 `comment.audit_enabled`、`comment.sensitive_words`、`ai.daily_user_quota`、`ai.ip_minute_quota`、`ai.guest_daily_quota`，以及 key 含 `secret`/`token`/`password`/`sensitive` 的键。
- 公开接口为 map，不返回 `description`/`updatedBy`；命中敏感键即使 `is_public=1` 也剔除并告警。
- 更新键必须已存在（由 V2 种子提供），未知 key 返回 `CONFIG_KEY_NOT_FOUND`；本模块不创建配置键。
- `value_type` 校验：STRING 必须 JSON 字符串且 <=2000 字符；NUMBER 必须 JSON 整数（int32）；BOOLEAN 必须 true/false；JSON 必须对象或数组且序列化 <=16 KB。类型不匹配返回 `CONFIG_VALUE_TYPE_INVALID`，不落库。
- 类型不可变：`type` 缺省用库中值；传入且不同即报错。
- `isPublic=true` 仅允许非敏感键，敏感键返回 `CONFIG_NOT_PUBLICABLE`（422）。
- 写入：业务事务**只更新 `site_configs`**（`updated_by=当前管理员`），审计不在同一事务内。事务提交后由 M0 `AuditRecorder` 以 `afterCommit` 独立事务（`REQUIRES_NEW`）写入 `audit_logs`（`action=CONFIG_UPDATE`、`target_type=CONFIG`、`target_id=key`、before/after 脱敏）。审计写入失败只 `WARN` + `wzblog_platform_audit_failed_total`，**不影响配置更新结果、不改变 200 响应**。
- 缓存失效：委托 M0 `SiteConfigService`。本地 Caffeine 立即 `evict(key)`，Redis 删除 `config:{key}` 与 `config:public`，并 `PUBLISH wzblog:config:invalidate {key}` 广播各实例；发布失败只记 WARN，靠 TTL 上限（<=10 分钟）兜底，更新仍返回成功。
- 敏感值（如 `comment.sensitive_words`）不写日志、不入 trace。

### 6.2 轮播与友链

- 状态仅 `ENABLED`/`DISABLED`；写入时校验，其他值返回 `COMMON_VALIDATION_ERROR`。
- 公开轮播过滤：`status=ENABLED AND (start_at IS NULL OR start_at<=NOW()) AND (end_at IS NULL OR end_at>NOW())`，按 `sort_order ASC, id ASC`。
- 公开友链过滤：`status=ENABLED`，按 `sort_order ASC, id ASC`；不返回 `contact`。
- 管理端列表：偏移分页，可选 `status` 过滤，按 `sort_order ASC, id ASC`；`pageSize` 最大 100。
- 图片/Logo key：非空时调用 M5 只读校验（`ACTIVE` 且 `biz_type=BANNER`，友链允许任意图片类型）；不存在返回 `FILE_NOT_FOUND`，M5 不可用则 500 且不落库。
- 删除：物理删除（与 03 一致）；不删除对象存储文件，由 M5 孤儿清理处理；不写审计（动作枚举未包含轮播/友链动作）。
- 新建返回 201；PUT 为全量覆盖，未传可选字段按默认/清空处理（`title/linkUrl/theme/bloggerName/contact/logoKey` 可置 null）。

### 6.3 审计日志

- 写入方：M0 `AuditRecorder`（M1~M7 调用），M7 只读；写入时机为业务事务 `afterCommit`、独立事务，失败只告警。
- `action` 仅允许 00 §6.1 `AuditAction` 的 19 个值：`ARTICLE_DELETE`、`ARTICLE_RESTORE`、`ARTICLE_PURGE`、`USER_DISABLE`、`USER_DELETE`、`POINTS_ADJUST`、`POINTS_RECONCILE`、`INTERACTION_RECONCILE`、`ORDER_TRANSITION`、`CONFIG_UPDATE`、`GOODS_CREATE`、`GOODS_UPDATE`、`GOODS_DELETE`、`TITLE_CREATE`、`TITLE_UPDATE`、`TITLE_DELETE`、`COMMENT_STATUS`、`GUESTBOOK_STATUS`、`FILE_DELETE`。
- `target_type` 仅允许 00 §6.1 `AuditTargetType` 的 11 个值：`ARTICLE`、`USER`、`ORDER`、`POINTS`、`CONFIG`、`GOODS`、`TITLE`、`COMMENT`、`GUESTBOOK`、`FILE`、`SYSTEM`；按库值精确匹配，非法值 400 `COMMON_VALIDATION_ERROR`。
- before/after 在写入时脱敏：键名不区分大小写命中 `password/passwordHash/token/refreshToken/secret/apiKey/openid/unionid/phone/email/sensitive_words` 时值替换 `"***"`；字符串内命中邮箱/手机号正则同样替换；单条 JSON <=8 KB。读取侧再执行一次同样脱敏，防止历史脏数据。
- 查询：所有条件 AND 组合；按 `created_at DESC, id DESC`；`from/to` 默认最近 7 天；无删除/修改接口。

### 6.4 看板口径（精确）

时区统一 `Asia/Shanghai`；日界转 UTC 后查询。所有计数为整数，空数据补 0，列表不为 null。概览为“实时 + 当日”，趋势为“按日”。

| 指标 | 口径 |
|---|---|
| `users.total` | `COUNT(users WHERE deleted_at IS NULL)` |
| `users.todayNew` | `COUNT(users WHERE created_at>=今日00:00+08 AND created_at<明日00:00+08 AND deleted_at IS NULL)` |
| `articles.total` | `COUNT(articles WHERE deleted_at IS NULL)` |
| `articles.published` | `COUNT(articles WHERE status='PUBLISHED' AND deleted_at IS NULL)` |
| `articles.todayPublished` | `COUNT(articles WHERE published_at 在今日 UTC 边界内 AND deleted_at IS NULL)`；按首次发布计，重新上架不重复计 |
| `comments.total` | `COUNT(comments WHERE deleted_at IS NULL)` |
| `comments.pending` | `COUNT(comments WHERE status='PENDING' AND deleted_at IS NULL)` |
| `interactions.todayViews` | 调用 M2 `ArticleStatsService#drainDailyViews(今日)`（读后即删）并**立即累加落库**到当日 `daily_stats`/`article_daily_stats`，再读当日行返回；M2 在 Redis 维护 `content:views:{yyyyMMdd}` 与 `content:views:source:{yyyyMMdd}`；不直连 Redis、不回退累计 `articles.view_count` |
| `interactions.todayLikes` | `COUNT(article_reactions WHERE type='LIKE' AND created_at 在今日边界)`，即当日新增且当前仍保留的点赞 |
| `interactions.todayFavorites` | 同上，`type='FAVORITE'` |
| `ai.todayMessages` | M6 `StatsService#todayMessageCount()`；不可用时回退 `COUNT(ai_messages WHERE role='USER' AND created_at 在今日边界)` |
| `points.todayIssued` | `SUM(amount) WHERE amount>0 AND created_at 在今日边界` |
| `points.todayConsumed` | `SUM(-amount) WHERE amount<0 AND created_at 在今日边界`（非负） |
| `orders.pending` | `COUNT(points_orders WHERE status='PENDING')` |

- `article-trend`：数组覆盖 `[from,to]` 每一天。优先 `daily_stats`：`views=view_count`、`likes=like_count`、`comments=comment_created`、`published=article_published`；某日无行时 `views/likes/comments` 回退 `SUM(article_daily_stats)`、`published` 回退 `articles.published_at` 计数；今日尚无行时先按 6.5 刷新当日行再回算；仍无数据补 0。
- `user-trend`：`registered` 取 `daily_stats.user_registered`，无行时 `COUNT(users WHERE created_at 在当日 AND deleted_at IS NULL)`；`active=COUNT(DISTINCT user_id) FROM login_audits WHERE success=1 AND user_id IS NOT NULL AND created_at 在当日`。
- `traffic-source`：`SUM(daily_stats.view_internal_count)` 与 `SUM(daily_stats.view_external_count)`，按 `stat_date` ∈ `[from,to]` 取当日增量求和；`ratio=x/(internal+external)`，分母 0 时两者均为 0。`basis=PERIOD_SUM` 明示这是**区间内阅读增量**，不是文章累计值；区间含今日时，先按 6.5 刷新当日行再求和。
- `article-rank`：仅 `status='PUBLISHED' AND deleted_at IS NULL`。`viewCount/likeCount/favoriteCount` 均为 `SUM(article_daily_stats.view_count/like_count/favorite_count)`（当日增量求和）；按 `metric` 降序、`article_id` 降序取前 `limit`。今日数据未落库前，三项可能低于实时值。
- `points-stats`：数组覆盖每一天。`issued=SUM(amount>0)`（含退款，退款是其子集）、`consumed=SUM(-amount<0)`（含兑换，兑换是其子集）、`redeemed=SUM(-amount WHERE type='REDEEM')`、`refunded=SUM(amount WHERE type='REFUND')`。优先 `daily_stats.points_issued/consumed`，缺行时实时回算；redeemed/refunded 始终查 `points_ledger`。

### 6.5 预聚合

- 调度：每日 `00:10`（`Asia/Shanghai`，cron `0 10 0 * * *`），聚合前一天 `[D-1 00:00, D 00:00)`，转 UTC 边界。
- 锁：ShedLock `name=m7-daily-stats`，`lockAtMostFor=PT30M`，`lockAtLeastFor=PT5M`；多实例仅一个执行。
- `daily_stats` 源：`user_registered=users.created_at` 计数；`article_published=articles.published_at` 计数；`comment_created=comments.created_at` 计数；`view_count/view_internal_count/view_external_count` 取 M2 `ArticleStatsService#drainDailyViews(stat_date)`（M2 读 `content:views:{yyyyMMdd}` 与 `content:views:source:{yyyyMMdd}` 后**读后即删**并返回，M7 不直连 Redis）；`like_count/favorite_count` 取 `article_reactions` 当日按 type 计数；`ai_message_count` 取 `ai_messages.role='USER'` 计数；`points_issued/consumed` 按 6.4 的正负求和。
- `article_daily_stats` 源：`view_count` 取 `drainDailyViews(stat_date)` 的按文章日增量；`like_count/favorite_count` 取 `article_reactions` 当日按 `type`、按 `article_id` 计数；`comment_count` 取 `comments.created_at` 当日计数；仅写有活动的文章行。所有列均为**当日增量**；阅读按 drain 增量累加，其余按当日重算覆盖。
- 幂等：drain 读后即删，同一业务日可能被多次调用（看板今日刷新 + 日聚合）。阅读类字段按返回值**累加**落库（`ON DUPLICATE KEY UPDATE col=col+VALUES(col)`），空返回不写、不归零；`article_daily_stats` 同口径按 `(article_id,stat_date)` 累加。其余字段（用户/文章/评论/积分）按当日全量重算覆盖，同一天重跑结果一致。
- 口径说明：03 §3.7.4 的“UPSERT 覆盖”以“单日单次 drain”为前提；本模块允许多次 drain，故阅读列采用增量累加（其余列仍覆盖），否则会丢失两次刷新之间的阅读数。
- 重跑：`StatsAggregationService#rebuild(from,to)` 支持任意日期区间（<=90 天），供运维脚本/启动参数调用；不新增 HTTP 路由。任务失败后次日先补跑最近 3 天未成功日期。
- 隔离：聚合异常捕获并记录 `stats_aggregation_failed_total` 与 traceId，不回滚业务、不影响接口；Redis 计数缺失按 0 并告警。
- 缓存：聚合成功 `INCR stats:dashboard:version`；看板缓存 key `stats:dashboard:v{version}:{endpoint}:{paramsHash}`，TTL 300 秒 + ±10% 抖动；空结果缓存 60 秒。命中即返回，未命中单飞重建（Redis `SET NX PX 10s`），失败直查库。

## 7. 关键时序

### 7.1 正常流

```
配置更新：ADMIN → PUT /admin/configs/{key} → 类型/公开校验 → UPDATE site_configs（本事务）
        → 提交 → afterCommit：AuditRecorder(CONFIG_UPDATE，独立事务，失败仅告警)
        → Caffeine evict + Redis DEL/PUBLISH → 200 ConfigVO

看板读取：ADMIN → GET /admin/dashboard/* → 参数与范围校验 → Redis 缓存命中? 返回
        → 未命中：daily_stats/article_daily_stats + AnalyticsMapper 只读 → 补零/组装 → 回填 5 分钟 → 200

轮播公开：PUBLIC → GET /banners → 状态+生效窗口过滤 → sort 升序 → 200（imageUrl 由 M5 解析）
```

### 7.2 失败流

| 场景 | 检测点 | HTTP | 错误码 | 事务 |
|---|---|---|---|---|
| 配置键不存在 | PUT 查 `uk_config_key` | 404 | `CONFIG_KEY_NOT_FOUND` | 无写入 |
| 值类型不符 | JSON 类型/长度校验 | 400 | `CONFIG_VALUE_TYPE_INVALID` | 无写入 |
| 敏感键设公开 | `isPublic=true` 且命中敏感集合 | 422 | `CONFIG_NOT_PUBLICABLE` | 无写入 |
| 轮播/友链不存在 | 更新/删除按 id 查 | 404 | `COMMON_NOT_FOUND` | 无写入 |
| 图片 key 不可用 | M5 校验失败 | 404 | `FILE_NOT_FOUND` | 无写入 |
| 状态/窗口非法 | 枚举或 `endAt<=startAt` | 400 | `COMMON_VALIDATION_ERROR` | 无写入 |
| 统计查询超时/异常 | 3 秒超时 | 200 | `OK`（对应字段 0/空数组） | 只读，无事务 |
| 审计写入失败（模拟异常） | afterCommit 独立事务 | 200 | `OK`（配置已生效） | 主事务已提交；审计 WARN + 指标，不回滚 |
| 非 ADMIN 访问后台 | Spring Security | 403 | `AUTH_FORBIDDEN` | 无 |

## 8. 并发、事务、幂等

| 场景 | 事务边界 | 锁/条件更新 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| 配置更新 | `site_configs` UPDATE 单事务 + `afterCommit` 审计（独立事务） | 按 `config_key` 行锁，最后写入生效 | `config_key` | 审计失败仅 WARN，不影响更新；缓存失效失败仅告警，TTL 兜底 |
| 轮播/友链写 | 单表单事务 | 无 version，后写覆盖 | 无（PUT 幂等） | 失败整体回滚 |
| 日聚合 | 每个 `stat_date` 独立事务 | ShedLock + 阅读类累加 UPSERT、其余重算覆盖 | `stat_date` / `(article_id,stat_date)` | 记录失败，次日补跑 |
| 看板读 | 只读，无事务 | 无锁 | 缓存 key 含参数哈希 | 降级空值，不阻断 |
| 审计查询 | 只读 | 无锁 | 无 | 空分页 |

## 9. 权限与归属

| 操作 | 角色 | 归属校验 | 说明 |
|---|---|---|---|
| 6 个看板接口 | ADMIN | 无（全站数据） | 仅后台 |
| 配置读写 | ADMIN | 无 | 公开配置仅白名单输出 |
| 轮播/友链管理 | ADMIN | 无 | 公开列表仅 ENABLED/生效中 |
| 公开配置/轮播/友链 | PUBLIC | 无 | 字段裁剪，不泄露后台字段 |
| 审计查询 | ADMIN | 无 | 只读，无写/删 |

## 10. 错误码

| code | HTTP | 触发条件 | 前端提示 |
|---|---|---|---|
| `COMMON_VALIDATION_ERROR` | 400 | 参数/枚举/日期范围/排序字段非法 | 请检查输入 |
| `COMMON_NOT_FOUND` | 404 | 轮播/友链 id 不存在 | 资源不存在 |
| `CONFIG_KEY_NOT_FOUND` | 404 | 配置键不存在 | 配置项不存在 |
| `CONFIG_VALUE_TYPE_INVALID` | 400 | 值与 `value_type` 不匹配或类型变更 | 配置值类型不正确 |
| `CONFIG_NOT_PUBLICABLE` | 422 | 敏感配置设为公开 | 该配置不允许公开 |
| `FILE_NOT_FOUND` | 404 | 图片/Logo key 不存在或非 ACTIVE | 图片不可用 |
| `AUTH_FORBIDDEN` | 403 | 非 ADMIN | 无权限 |
| `COMMON_INTERNAL_ERROR` | 500 | 未预期异常/M5 不可用 | 系统繁忙 |

> 仅使用已登记前缀；`CONFIG_*` 与 `FILE_NOT_FOUND` 已登记到 04 §7.9。

## 11. 验收用例

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M7-001 | ADMIN 登录 | GET `/admin/configs` | 200，全部键按 key 升序，值类型正确 |
| TC-M7-002 | `upload.max_image_mb` 为 NUMBER | PUT 值 `10` | 200，库中 JSON 数字 10，审计 CONFIG_UPDATE |
| TC-M7-003 | NUMBER 与 STRING 键各一 | NUMBER 传 `"10"`；STRING 传 `123` | 均 400 `CONFIG_VALUE_TYPE_INVALID`，库不变 |
| TC-M7-004 | `site.announcement` 非公开 | PUT `isPublic=true` | 200，公开配置出现该键 |
| TC-M7-005 | `comment.sensitive_words` | PUT `isPublic=true` | 422 `CONFIG_NOT_PUBLICABLE` |
| TC-M7-006 | 任意公开/敏感键 | GET `/public/configs` | 仅公开白名单，无敏感键与后台字段 |
| TC-M7-007 | 两实例部署 | 更新 `site.name` 后立即读公开配置 | 两实例均返回新值（广播失效） |
| TC-M7-008 | 不存在键 | PUT `/admin/configs/x.y` | 404 `CONFIG_KEY_NOT_FOUND` |
| TC-M7-009 | JSON 类型键 | 分别 PUT 对象与字符串 | 对象 200；字符串 400 `CONFIG_VALUE_TYPE_INVALID` |
| TC-M7-010 | 3 条轮播（未来/已过期/生效中） | GET `/banners` | 仅生效中返回；按 sort_order、id 升序 |
| TC-M7-011 | imageKey 不存在 | POST `/admin/banners` | 404 `FILE_NOT_FOUND`，无入库 |
| TC-M7-012 | 两条友链，1 条 DISABLED | GET 公开列表；ADMIN 增改删 | 公开仅 ENABLED、升序、无 `contact`；删除为物理删除，404 正确 |
| TC-M7-013 | 造数：今日注册/发布/评论/积分/订单 | GET overview | 各指标与 SQL 手算一致 |
| TC-M7-014 | 区间含无数据日 | GET article-trend | 每天都有点，缺失日四指标为 0 |
| TC-M7-015 | 同一用户当日多次成功登录 | GET user-trend | `active` 该用户只计 1 |
| TC-M7-016 | 无内部/外部阅读 | GET traffic-source | 两计数 0、两比例 0，`basis=PERIOD_SUM` |
| TC-M7-026 | `daily_stats` 有 2 天站内/站外数据 | GET traffic-source 覆盖该区间 | 两计数等于两日 `view_internal_count/view_external_count` 之和；不读 `articles` 累计字段 |
| TC-M7-017 | 3 篇文章不同阅读/点赞/收藏 | GET article-rank?metric=view&limit=2 | 返回 2 条、按阅读降序、排名 1/2 |
| TC-M7-018 | 有发放/消耗/兑换/退款 | GET points-stats | 四项按日正确，兑换/退款为对应子集 |
| TC-M7-019 | 已聚合日期（Redis 当日键已被 drain 空） | 同一日期重跑 3 次 | `daily_stats`/`article_daily_stats` 完全一致，阅读不重复累加（空返回不写） |
| TC-M7-020 | 多实例 | 00:10 触发聚合 | ShedLock 仅一实例执行，无重复行 |
| TC-M7-021 | `days=8` 或 from/to 跨 91 天 | GET 任一趋势 | 400 `COMMON_VALIDATION_ERROR` |
| TC-M7-022 | 空库 | GET overview 与各趋势 | 无数据返回 0/空数组，不返回 null/500 |
| TC-M7-023 | 普通 USER / 游客 | 访问后台/公开接口 | 后台 403 `AUTH_FORBIDDEN`；公开 200 |
| TC-M7-024 | 预置含密码/token 的审计 | GET `/admin/audit-logs` | before/after 敏感值为 `***` |
| TC-M7-025 | 多操作人/动作/时间 | 组合过滤 + 分页 | total/list/page/hasNext 正确，按时间倒序 |
| TC-M7-027 | 审计表不可写（模拟 `AuditRecorder` 异常） | PUT `/admin/configs/site.name` | 200，配置已更新、公开配置为新值；日志 WARN + `wzblog_platform_audit_failed_total` +1；主事务未回滚 |
| TC-M7-028 | 预置 19 种 `AuditAction`、11 种 `AuditTargetType` 的审计行 | 按 `action`/`targetType` 过滤查询 | 全部可命中；非法枚举值查询 400 `COMMON_VALIDATION_ERROR` |

## 12. 性能与索引

| 查询 | 索引 | 预期 | 验收 |
|---|---|---|---|
| overview 各 COUNT | `users.idx_deleted`、`articles.idx_deleted`/`idx_published`、`comments.idx_moderation`、`points_orders.idx_status` | 缓存命中 <10ms；回源 <=8 条索引查询 | P95 <300ms |
| article-trend | `daily_stats.PRIMARY`、`article_daily_stats.idx_date` | 90 次主键范围读 | P95 <300ms |
| user-trend active | `login_audits.idx_created` | 90 天范围扫描 + 去重 | P95 <300ms |
| traffic-source | `daily_stats.PRIMARY` | 按 `stat_date` 区间读站内/站外两列 | P95 <300ms |
| article-rank | `article_daily_stats.idx_date` | 先按日期取行再按文章聚合（含 `favorite_count`） | P95 <300ms |
| points-stats | `daily_stats.PRIMARY`、`points_ledger.idx_created` | 按日读预聚合；兑换/退款走流水 `created_at` 区间 | P95 <300ms |
| audit-logs | `idx_operator`/`idx_target`/`idx_created` | 有 operator/时间条件走索引；仅 action 无索引 | P95 <300ms（数据量小） |
| 公开轮播/友链 | `idx_status_sort` | 过滤后 filesort 可接受（<1000 行） | P95 <100ms |
| 配置列表 | `uk_config_key` | 全表 <100 行 | P95 <50ms |

- 缓存：看板 5 分钟（版本号失效）、公开配置 5~10 分钟（广播失效）、空结果 60 秒；TTL 加 ±10% 抖动防雪崩。
- 慢 SQL 阈值 200ms 由 M0 拦截器记录；AnalyticsMapper 单语句 3 秒超时。
- 已知索引缺口：`users` 缺 `(created_at)`、`audit_logs` 缺 `(action, created_at)`；V3 索引调优时评估，当前数据量可接受。`points_ledger`/`login_audits`/`points_orders` 的 `idx_created(created_at)` 由本轮 DDL 补齐，看板聚合直接依赖。

## 13. 事件与跨模块调用

| 事件/调用 | 方向 | 载荷 | 消费方 | 幂等 |
|---|---|---|---|---|
| `SiteConfigService` 读取/失效 | M7 → M0 | key、公开配置 | M7 | 缓存 key 维度 |
| `AuditRecorder#record` | M1~M7 → M0 | action/target/before/after/ip | M0 写 `audit_logs` | 追加写；afterCommit 独立事务，失败仅告警 |
| `FileAppService` 只读校验 | M7 → M5 | imageKey/logoKey、bizType | M5 | 幂等读 |
| `StatsService#todayMessageCount` | M7 → M6 | 业务日 | M6 | 幂等读 |
| `ArticleStatsService#drainDailyViews(date)` | M7 → M2 | 业务日；返回总/站内/站外/按文章阅读日增量 | M2 | 读后即删；M7 立即累加落库，空返回不写 |
| 领域事件 | — | 无发布、无订阅 | — | — |

## 14. 待确认事项

| 事项 | 默认方案 | 需谁确认 |
|---|---|---|
| 今日阅读数据源 | M2 浏览接口在 Redis 维护 `content:views:{yyyyMMdd}`（总/按文章）与 `content:views:source:{yyyyMMdd}`（站内/站外）；M7 只通过 `ArticleStatsService#drainDailyViews(date)` 读取，**读后即删并立即累加落库**，不直连 Redis、不写“无来源” | M2 / 架构 |
| traffic-source 口径 | 已定为 `daily_stats.view_internal_count/view_external_count` 的区间增量求和（`basis=PERIOD_SUM`），不再使用 `articles` 累计字段 | 架构 |
| AI 今日消息 | 由 M6 `StatsService#todayMessageCount()` 提供；不可用时回退 `ai_messages.role='USER'`；`/admin/ai/stats` 归 M6，M7 overview 只调用其 StatsService | M6 |
| 看板默认区间 | 30 天；`days` 与 `from/to` 二选一；`from/to` 为 `YYYY-MM-DD`（契约显式例外） | 产品 |
| 图片 URL | M7 只存 key，响应 `imageUrl` 由 M5 统一解析；失败为 null | M5 |
| 审计枚举 | `action`/`target_type` 严格使用 00 §6.1 的 19 个 `AuditAction` / 11 个 `AuditTargetType`（含 `GOODS_*`、`TITLE_*`、`POINTS_RECONCILE`、`INTERACTION_RECONCILE`、`TITLE`、`FILE`、`SYSTEM`），非法值 400 | 架构 / 04 |
| 审计事务口径 | 配置更新事务 + `afterCommit` 审计（M0 独立事务）；审计失败只告警，不影响配置更新 | 已解决（M0 为最终口径） |
| 轮播/友链审计 | 当前动作枚举不含对应动作，暂不写审计 | 架构 |
| 配置公开切换 | 允许 ADMIN 切换，但敏感键永久禁止公开 | 产品 / 安全 |