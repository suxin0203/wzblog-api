# M3 · 互动模块（评论 / 点赞收藏 / 留言 / 通知）

> 负责人 Agent：M3-interaction
> 依赖模块：M0（`SiteConfigService` 敏感词/审核开关）、M1（`UserQueryService#getBrief/getBriefs` 只读）、M2（`ArticleQueryService#getBrief/getBriefs` 只读；`ArticleCounterService#increment/decrement` 同事务计数）
> 对应阶段：阶段 3（互动）
> 状态：已定稿
> 契约版本：以 [00-文档索引与约定](../00-文档索引与约定.md) 1.3 为准

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.modules.interaction` |
| 表 | `comments`、`article_reactions`、`comment_likes`、`guestbook_messages`、`notifications` |
| 对外事件 | 发布：`CommentApprovedEvent`、`CommentRemovedEvent`、`ReactionToggledEvent`（仅 `LIKE`）；订阅：`OrderStatusChangedEvent`、`PointsAdjustedEvent` |
| 依赖的 app 服务 | `SiteConfigService`（M0）、`ArticleQueryService#getBrief` / `#getBriefs`（M2，只读）、`ArticleCounterService#increment` / `#decrement`（M2，同事务维护文章三计数）、`UserQueryService#getBrief` / `#getBriefs`（M1，只读，补触发人/作者信息）、`AuditRecorder`（M7 共享组件） |
| 基础设施 | Redis（限流、未读数、通知去重、SSE 广播）、MySQL、Spring `ApplicationEventPublisher` |
| 阶段 | 3；P0：评论/点赞收藏/留言/审核；P1：通知含 SSE；P2：评论点赞 |

## 2. 范围与非目标

- 做：文章评论（两级树）、评论点赞、文章点赞/收藏 toggle、我的点赞/收藏、留言板、评论与留言审核、站内通知（列表/未读数/批量已读/全部已读/SSE）、三处冗余计数（经 M2 `ArticleCounterService`）维护与每日对账。
- 不做：积分落账（只发 `CommentApprovedEvent`/`CommentRemovedEvent`/`ReactionToggledEvent`，由 M4 按幂等键处理）；文章阅读数、看板统计、审计查询（M2/M7）；私信、@提醒、举报、评论表情/图片、WebSocket；不新增路由、枚举、表字段。

## 3. 路由清单（24，与 00 第 4 节逐字一致）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/articles/{articleId}/comments` | OPTIONAL | 评论树一页（根游标）；匿名仅见 APPROVED，登录用户另见自己的 PENDING 与 `liked` |
| POST | `/articles/{articleId}/comments` | OPTIONAL | 发表根评论或回复；201 |
| DELETE | `/comments/{id}` | OWNER | 软删本人评论，子评论保留 |
| POST | `/comments/{id}/like` | USER | 评论点赞，幂等 |
| DELETE | `/comments/{id}/like` | USER | 取消评论点赞，幂等 |
| PUT | `/articles/{id}/reactions/{type}` | USER | 幂等设置点赞/收藏 |
| DELETE | `/articles/{id}/reactions/{type}` | USER | 幂等取消点赞/收藏 |
| GET | `/articles/{id}/reactions/me` | OPTIONAL | 本人两种 reaction 状态与文章计数 |
| GET | `/me/favorites` | USER | 我的收藏，游标分页 |
| GET | `/me/likes` | USER | 我的点赞，游标分页 |
| GET | `/guestbook` | PUBLIC | 公开留言列表（仅 APPROVED），游标分页 |
| POST | `/guestbook` | OPTIONAL | 发表留言；201 |
| DELETE | `/guestbook/{id}` | OWNER | 软删本人留言 |
| GET | `/admin/comments` | EDITOR（仅自己文章，必须带 articleId）/ADMIN | 审核列表，可按 `status` 过滤 |
| PUT | `/admin/comments/{id}/status` | EDITOR（仅自己文章，不能自审）/ADMIN | 审核/下架/恢复 |
| DELETE | `/admin/comments/{id}` | EDITOR（仅自己文章，不能自审）/ADMIN | 管理端软删评论 |
| GET | `/admin/guestbook` | ADMIN | 留言审核列表 |
| PUT | `/admin/guestbook/{id}/status` | ADMIN | 留言审核/下架/恢复 |
| DELETE | `/admin/guestbook/{id}` | ADMIN | 管理端软删留言 |
| GET | `/notifications` | USER | 通知列表（偏移分页） |
| GET | `/notifications/unread-count` | USER | 未读数（Redis 优先） |
| POST | `/notifications/read` | USER | 批量标记已读（1~100 个 id） |
| POST | `/notifications/read-all` | USER | 全部已读 |
| GET | `/notifications/stream` | USER | SSE 推送，15s 心跳，`Last-Event-ID` 续传 |

> `{type}` 仅接受小写 `like`/`favorite`，映射 `ReactionType.LIKE/FAVORITE`；`articleId` 与 `id` 的差异来自契约原文，不得改写。

## 4. DTO 契约

### 4.1 请求

| DTO | 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|---|
| CreateCommentReq | content | string | 是 | trim 后 1~2000 码点，禁止纯空白/控制字符；纯文本，服务端转义输出 |
| | parentId | string | 否 | 数字字符串，存在的评论，属于同一 articleId，`status=APPROVED` 且未删除 |
| | guestNickname | string | 否 | 1~30 码点；登录用户传入则忽略；缺省展示「匿名」 |
| CommentListQuery | cursor | string | 否 | 不透明 Base64URL；非法 → 400 |
| | size | int | 否 | 1~50，默认 20 |
| | rootId | string | 否 | 指定根评论，返回其子评论平铺游标列表（子按时间升序） |
| ModerateReq | status | enum | 是 | `PENDING/APPROVED/REJECTED/HIDDEN`；目标值不得为 `PENDING` |
| | remark | string | 否 | ≤200 码点，仅写入 `audit_logs`（表无备注列） |
| AdminCommentQuery | page / pageSize | int | 否 | ≥1 / 1~100，默认 1/20 |
| | status | enum | 否 | 同上枚举，缺省全部 |
| GuestbookCreateReq | content | string | 是 | 1~1000 码点 |
| | nickname | string | 否 | 1~30 码点，缺省 `匿名` |
| GuestbookListQuery | cursor / size | string/int | 否 | 同 CommentListQuery |
| NotificationReadReq | ids | string[] | 是 | 1~100 个，去重；非本人 id 静默忽略 |
| PageQuery | page / pageSize | int | 否 | 同 AdminCommentQuery（通知列表/后台留言） |

> 昵称校验：评论 `guestNickname` 与留言 `nickname` 均为 **1~30 字符**（Unicode 码点）；`comments.guest_name` / `guestbook_messages.nickname` 的 `VARCHAR(50)` 只是 DB 余量，不代表 API 上限为 50。

### 4.2 响应（所有 ID 为字符串，时间为 ISO-8601 UTC）

| VO | 字段 | 类型 | 说明 |
|---|---|---|---|
| CommentNodeVO | id / articleId / rootId / parentId | string | 根评论 rootId、parentId 为 null |
| | author | object | `{userId?, nickname, avatarKey?, guest}` |
| | replyToUser | object | `{userId?, nickname}`；仅回复有值 |
| | content | string | 删除占位时为 null |
| | status | enum | 仅本人 PENDING 与后台列表返回，公开项省略 |
| | likeCount / liked / replyCount | int/bool/int | `liked` 匿名恒 false |
| | hasMoreReplies | bool | 子评论超过 200 条时为 true |
| | deleted | bool | 占位节点 true，作者显示「该评论已删除」 |
| | children | CommentNodeVO[] | 仅根节点携带，子按时间升序 |
| CommentLikeVO | commentId / liked / likeCount | string/bool/int | toggle 后当前状态 |
| ReactionVO | articleId / type / active / count | string/enum/bool/int | `type` 返回 `LIKE/FAVORITE` |
| MyReactionVO | articleId / like / favorite / likeCount / favoriteCount | string/bool/bool/int/int | 游客 `like/favorite=false` |
| ArticleBriefVO | 由 M2 提供 | object | 列表项 `{article, reactedAt}`，文章软删/下架项过滤 |
| CursorPage | list / nextCursor / hasNext | array/string/bool | 评论/留言/我的列表统一外壳 |
| GuestbookVO | id / author / content / createdAt / mine | string/object/string/string/bool | author 同 CommentNodeVO |
| NotificationVO | id / type / title / content | string/enum/string/string | title ≤150、content ≤500 |
| | linkType / linkId | string/null | `ARTICLE/COMMENT/GUESTBOOK/ORDER/POINTS/SYSTEM` |
| | actor | object | `{userId?, nickname, avatarKey?}`，系统通知 null |
| | isRead / readAt / createdAt | bool/string/string | |
| UnreadCountVO | count | int | ≥0 |
| ReadResultVO | updated / unreadCount | int/int | 幂等：重复标记 updated=0 |
| ModerationResultVO | id / status / updatedAt | string/enum/string | |
| SsePayload | id / type / title / content / linkType / linkId / createdAt | — | `event: notification` 的 data |

> `NotificationVO.type` 取 00 §6.1 的全部 13 个值（含 `ARTICLE_COMMENTED`、`ORDER_COMPLETED`）；`linkType` 固定为 `ARTICLE/COMMENT/GUESTBOOK/ORDER/POINTS/SYSTEM`。`dedupe_key` 仅用于落库去重，不对外返回。

### 4.3 统一规则

- 校验失败 → 400 `COMMON_VALIDATION_ERROR`，`data.errors` 字段级原因；长度按 Unicode 码点计。
- 软删资源一律 404（`COMMENT_NOT_FOUND`/`GUESTBOOK_NOT_FOUND`），不泄露存在性；越权但存在 → 403 `AUTH_FORBIDDEN`。
- `PUT/DELETE` 均为幂等状态设置，重复请求返回 200 与当前状态（不用 POST toggle）。
- 删除类成功返回 200 `{id}`，不返回 204，便于前端同步计数。

## 5. 数据表与关键字段（字段定义见 03 第 3.3 节，不重复）

| 表 | 用途 | 关键字段/索引 |
|---|---|---|
| `comments` | 两级评论 | `root_id`/`parent_id`/`reply_to_user_id`/`status`/`deleted_at`/`guest_name`/`like_count`；`idx_article`、`idx_root`、`idx_user`、`idx_moderation`、`idx_parent` |
| `article_reactions` | 点赞/收藏共用 | 唯一键 `uk_target(article_id,user_id,type)`；`idx_user(user_id,type,created_at)` 支撑我的列表 |
| `comment_likes` | 评论点赞 | 联合主键 `(comment_id,user_id)` 天然幂等 |
| `guestbook_messages` | 留言 | `status`/`deleted_at`/`nickname`；`idx_status_created`、`idx_user` |
| `notifications` | 站内通知 | `is_read`/`read_at`；`dedupe_key`（UK `uk_dedupe`，同一业务事件只通知一次）；`idx_recipient_read`、`idx_recipient_created`；`link_type`+`link_id` |
| `articles`（M2 所有） | 三个冗余计数 | M3 **不直接写**；同事务调用 `ArticleCounterService#increment` / `#decrement`（§18），维护 `like_count`/`favorite_count`/`comment_count` |

## 6. 状态机与业务规则

### 6.1 评论/留言状态（枚举与 00 6.1 一致）

```
PENDING ──approve──► APPROVED ──hide──► HIDDEN
   │                    ▲  │               │
   └──reject──► REJECTED└──┴────restore────┘
任意状态 ──delete(OWNER/EDITOR)──► deleted_at（子评论保留）
```

| 当前 | 允许目标 | 触发者 | 副作用（同事务/提交后） |
|---|---|---|---|
| `PENDING` | `APPROVED` / `REJECTED` | EDITOR | →APPROVED：`ArticleCounterService#increment(COMMENT,+1)`；提交后事件+通知 |
| `APPROVED` | `HIDDEN` | 作者/ADMIN | `#decrement(COMMENT,1)`；**不发 `CommentRemovedEvent`（D13：隐藏不反转积分）** |
| `REJECTED` / `HIDDEN` | `APPROVED` | 作者/ADMIN | `#increment(COMMENT,+1)`；`CommentApprovedEvent` 仍发，但幂等键已用过，不重复发分/通知 |
| 任意（未删除） | 同状态 | 作者/ADMIN | 幂等 200，无副作用、无事件 |
| 其他组合 | — | — | 409 `COMMENT_STATUS_INVALID` |
| `deleted_at≠NULL` | — | — | 404 |

- 仅 `APPROVED` 对公众可见；本人可在列表看到自己的 `PENDING` 根评论和回复（含 `status` 字段；v1.6 修复了回复不可见的缺口）。
- 创建状态：敏感词命中 → 强制 `PENDING`；否则 `comment.audit_enabled=true` → `PENDING`，`false` → `APPROVED`。
- 回复仅允许挂在 `APPROVED` 且未删除的评论上；父评论被拒/待审时不能回复。
- `remark` 无落库列，仅写入 `audit_logs`（`COMMENT_STATUS`/`GUESTBOOK_STATUS`，含 from/to/remark）。
- 留言状态机相同，但无计数、无事件、无积分副作用；审核仍写审计。
- 删除：写 `deleted_at`，不物理删；若原状态为 `APPROVED` 则调 `ArticleCounterService#decrement(COMMENT,1)` 并在提交后发 `CommentRemovedEvent`。

### 6.2 审核动作与权限

| 动作 | 路由 | 角色 | 结果 |
|---|---|---|---|
| 通过 | `PUT /admin/comments/{id}/status` | 文章作者/ADMIN | `APPROVED` + 计数 + 事件 + 作者通知 |
| 拒绝 | 同上 | 文章作者/ADMIN | `REJECTED`，不计数，不通知 |
| 下架 | 同上 | 文章作者/ADMIN | `APPROVED→HIDDEN`，计数回退；**不发反向事件** |
| 恢复 | 同上 | 文章作者/ADMIN | `HIDDEN/REJECTED→APPROVED`，重新计数；事件幂等，不重复发分 |
| 管理删除 | `DELETE /admin/comments/{id}` | 文章作者/ADMIN | 软删 + 计数回退 + 反向事件（删除是终态） |

> 条件更新 `WHERE id=? AND status=:from AND deleted_at IS NULL`，影响行数 0 → 重新读状态：已同目标值返回幂等 200，否则 409。

## 7. 评论树组装（两次查询 + 内存组装，深度仅 2）

1. 校验文章：`ArticleQueryService#getBrief` 返回非空、`PUBLISHED`、可见性允许、未软删；否则 404。
2. 根查询（游标 `created_at DESC, id DESC`）：
   - `status='APPROVED' AND root_id IS NULL AND deleted_at IS NULL`；
   - 已登录时 `UNION ALL` 本人 `PENDING` 根评论（走 `idx_user`），合并后统一排序分页；
   - 已删除根评论仅在其仍有未删除的 APPROVED 子评论时保留（`EXISTS` 子查询走 `idx_root`），用于「该评论已删除」占位。
3. 子查询（一次）：`root_id IN (本页根 id)` 且 `status='APPROVED'`，用窗口函数按 `root_id` 分区取前 201 条并统计 `replyCount`，超出 200 截断并置 `hasMoreReplies=true`；排序 `created_at ASC, id ASC`。
4. 内存组装：按 `root_id` 分组挂到父节点；子评论平铺（回复的回复仍属同一根，通过 `reply_to_user_id` 展示「回复 @某人」）。
5. 删除语义：子评论被删且无回复 → 不返回；有回复 → 返回占位节点（content=null、author 匿名、likeCount=0、deleted=true）。父删子留，根节点占位但仍返回其子。
6. `?rootId=` 模式：直接返回该根的 APPROVED 子评论游标列表（`created_at ASC, id ASC`），不组装树。
7. 深度守卫：`parentId` 指向的评论若 `root_id IS NULL` 则新评论 `root_id=parentId`、`parent_id=parentId`；否则 `root_id=parent.rootId`、`parent_id=parentId`、`reply_to_user_id=parent.userId`；任何情况不产生第三层。

## 8. 点赞/收藏 toggle 语义

- `PUT /articles/{id}/reactions/{type}` = 幂等「设置」：先校验文章可交互（非 DRAFT/ARCHIVED/PRIVATE/已删，否则 404）；`INSERT` 忽略唯一冲突；仅当真正插入 1 行时调用 `ArticleCounterService#increment(articleId, LIKE|FAVORITE, 1)`。已存在时直接返回当前状态与计数，不重复计数、不发事件。
- `DELETE .../reactions/{type}` = 幂等「取消」：`DELETE WHERE article_id=? AND user_id=? AND type=?`；明细影响 1 行才调用 `ArticleCounterService#decrement(articleId, LIKE|FAVORITE, 1)`；`WHERE {col}>0` 守卫在 M2 端口内，影响 0 行视为已取消（不再递减）。
- 唯一键 `uk_target` 兜底并发生：捕获 `DuplicateKeyException` 视为已存在 → 回读计数返回；不重试、不二次加一。
- 计数读取在同一事务内、端口调用之后取端口返回的新计数值；前端以响应为准。
- 事件：**仅 `LIKE`** 发布 `ReactionToggledEvent{articleId,authorId,actorId,type,active}`（首次点赞 active=true 加分、取消 active=false 反转）；`FAVORITE` 不发跨模块事件，仅在本模块 AFTER_COMMIT 写 `ARTICLE_FAVORITED` 通知。
- 通知：首次 `LIKE`→`ARTICLE_LIKED`，首次 `FAVORITE`→`ARTICLE_FAVORITED`；取消不通知；作者=操作人时跳过。
- 评论点赞：`INSERT IGNORE`/`DELETE` + `comments.like_count` 原子增减（同事务），重复请求返回 `{liked, likeCount}`；通知 `COMMENT_LIKE` 在本模块 AFTER_COMMIT 写入。

## 9. 计数一致性

| 计数 | 变更点 | 方式 |
|---|---|---|
| `articles.like_count` | PUT/DELETE like | 同事务调 M2 `ArticleCounterService#increment/decrement`（单条原子 UPDATE） |
| `articles.favorite_count` | PUT/DELETE favorite | 同上 |
| `articles.comment_count` | 创建即 APPROVED；审核通过/下架/拒绝、软删后按公开树可见数回算 | 同事务调 M2 `ArticleCounterService`，创建 APPROVED 原子 +1；审核/删除后按 `countVisibleByArticle` 差值收敛 |
| `comments.like_count` | 评论点赞/取消 | 同事务原子增减（`comments` 属本模块） |

- `comment_count` 口径 = **公开列表可见评论**：根评论 `APPROVED` 且未删；子评论 `APPROVED` 且未删，并且根评论同文章、`APPROVED`。根评论软删时自身不计，但已过审子回复仍计（列表保留删除占位）；`PENDING/HIDDEN/REJECTED` 根下的子回复不计。
- 子回复审核为 `APPROVED` 时必须校验根评论同文章且 `APPROVED`，禁止制造「计数有、列表无」的不可见已过审回复。

- 永不「先查后写」；文章三计数一律由 M2 端口以单条原子 UPDATE 执行，M3 **不写** `articles` 表、不注入 M2 的 Mapper。递减影响 0 行且预期应变更 → 记 WARN + 对账线索，不抛业务异常（不阻断明细事务）。
- 每日 03:30（`Asia/Shanghai`）对账任务：按 `article_reactions`/`comments` 明细重算三个文章计数与评论点赞数；评论数按公开树可见口径重算；对「最近 10 分钟无互动明细写入」的文章（以明细表 `MAX(created_at)` 判定，不读 `articles.updated_at`），先以 `increment(articleId, type, 0)` 读取当前计数，再按差值一次 `increment/decrement` 收敛；差异写 `audit_logs(action='INTERACTION_RECONCILE')`（articleId、字段、原值、重算值）。
- 对账同时检测跨文章 `root_id` 与「根不可见但子回复已过审」的异常，记 WARN + 一条 `INTERACTION_RECONCILE/COMMENT/VISIBILITY` 审计；状态修复留给管理员，不自动改审核结论。
- 文章删除由 M2 软删，M3 不级联、不清理评论；公开入口因文章不可见而 404。仅 ADMIN `purge` 物理删除时由外键 `CASCADE` 清理互动数据。
- 对账 SQL 以 `article_reactions`/`comments` 聚合为准，只按差值收敛计数，不重写明细，避免二次漂移。

## 10. 限流与反垃圾

| 场景 | 维度 | 阈值（契约） | Redis Key |
|---|---|---|---|
| 评论（登录） | userId | 1 次/10s、50 次/天 | `rl:comment:u:{userId}:10s`、`rl:comment:u:{userId}:d{yyyyMMdd}` |
| 评论（游客） | IP | 1 次/60s、10 次/天/IP | `rl:comment:ip:{ip}:60s`、`rl:comment:ip:{ip}:d{yyyyMMdd}` |
| 留言 | IP | 1 次/60s、10 次/天/IP | `rl:guestbook:ip:{ip}:60s`、`rl:guestbook:ip:{ip}:d{yyyyMMdd}` |

- 滑动窗口 ZSET（score=毫秒时间戳）+ Lua 原子「清理过期→计数→写入」；超限 429 `COMMON_RATE_LIMITED`，`Retry-After` 为窗口剩余秒。
- 次数在业务校验通过后、写库前扣减；写库失败不回补（宁可少放行，不可重复放行）。
- 敏感词：`SiteConfigService` 读取 `comment.sensitive_words`，本地 Aho-Corasick（Caffeine 缓存 + 配置版本刷新）；匹配前做 NFKC 全角转半角、去零宽字符（U+200B~U+200D、U+FEFF）、去控制字符、大小写归一；命中片段按字符数替换为 `*`，并强制 `PENDING`。
- 游客开关：`comment.guest_enabled=false` 时匿名 POST 返回 403；不影响读取。
- 所有 POST 记录 `ip`、`user_agent`（截断 255）；IP 仅在受信反向代理后取 `X-Forwarded-For` 首跳，否则用 `RemoteAddr`。

## 11. 通知与 SSE

### 11.1 类型映射与去重

| 来源 | type | 接收人 | linkType/linkId | dedupe_key（唯一） |
|---|---|---|---|---|
| 评论过审 | `COMMENT_APPROVED` | 评论作者 | `COMMENT`/commentId | `comment_approved:{commentId}` |
| 根评论过审（文章被评论） | `ARTICLE_COMMENTED` | 文章作者 | `ARTICLE`/articleId | `article_commented:{commentId}` |
| 回复过审 | `COMMENT_REPLY` | 父评论作者，缺省文章作者 | `COMMENT`/commentId | `comment_reply:{commentId}` |
| 文章点赞 | `ARTICLE_LIKED` | 文章作者 | `ARTICLE`/articleId | `article_liked:{articleId}:{actorId}` |
| 文章收藏 | `ARTICLE_FAVORITED` | 文章作者 | `ARTICLE`/articleId | `article_favorited:{articleId}:{actorId}` |
| 评论点赞 | `COMMENT_LIKE` | 评论作者 | `COMMENT`/commentId | `comment_like:{commentId}:{actorId}` |
| `OrderStatusChangedEvent` | `ORDER_APPROVED/SHIPPED/COMPLETED/REJECTED/CANCELLED` | 下单用户 | `ORDER`/orderNo | `order:{orderNo}:{toStatus}` |
| `PointsAdjustedEvent` | `POINTS_ADJUSTED` | 用户 | `POINTS`/null | `points:{ledgerId}` |

- `notifications.dedupe_key` 为唯一索引（`uk_dedupe`），订单/积分通知一律以它去重；`INSERT` 命中唯一冲突即视为已通知，直接跳过。
- Redis `SET notify:dedupe:{recipientId}:{key} 1 NX EX 2592000` 仅作快路径，最终一致性由 `dedupe_key` 唯一键保证；Redis 不可用时退化为直接依赖唯一键。
- `SYSTEM` 现在由 M3 用于文章治理通知（`ArticleModeratedEvent`：SOFT_DELETE/RESTORE/PURGE/TAKEOVER，link_type=ARTICLE，dedupe `article_moderated:{articleId}:{action}:{version}`）；未新增 NotificationType，0 迁移。`ORDER_COMPLETED` 正常发通知（不再静默）。
- 写入时机：监听器 `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`；失败只记日志，不影响主事务。
- 内容模板（≤150/≤500）：标题含动作与对象，正文附内容摘要 100 字；不拼接用户 HTML。

### 11.2 未读数

- Key `notify:unread:{userId}`；写路径 Lua：key 存在才 `INCR`，不存在则不创建（返回 -1），避免缓存缺失时从 1 开始。
- 读路径：`GET` 缺失或为 -1 → `COUNT(*) WHERE recipient_id=? AND is_read=0` 回填并 `EX 3600`；Redis 故障直接回源 COUNT。
- 批量已读：`UPDATE ... SET is_read=1, read_at=NOW(3) WHERE recipient_id=? AND id IN (...) AND is_read=0`，按影响行数 `DECRBY`（Lua 钳制 ≥0）；`read-all` 用单条 UPDATE 后 `DEL` key。
- 重复标记 updated=0，未读数不变。

### 11.3 列表与分页

- `GET /notifications`：偏移分页 `page/pageSize`，`ORDER BY created_at DESC, id DESC`，仅本人；`actor` 昵称头像经 M1 `UserQueryService#getBriefs(ids)` 批量取。
- `GET /admin/comments|guestbook`：偏移分页，`status` 过滤；评论项附文章 `{id,title,slug}`（M2 `ArticleQueryService#getBriefs` 批量取）。

### 11.4 SSE

- 客户端必须用 `fetch` 流式读取（`ReadableStream`）并在请求头带 `Authorization: Bearer <accessToken>`；**不使用原生 `EventSource`**（无法设置请求头，禁止把 token 放 query）。
- 响应头：`Content-Type: text/event-stream`、`Cache-Control: no-cache`、`X-Accel-Buffering: no`；连接建立先发 `retry: 3000`。
- 事件格式：`id: {notificationId}\nevent: notification\ndata: {SsePayload JSON}\n\n`；每 15s 发注释行 `: ping` 心跳。
- 建连：`Last-Event-ID` 存在时 `WHERE recipient_id=? AND id > ? ORDER BY id ASC LIMIT 50` 补发，再发 `event: ready`（含未读数）；无该头则只发 ready。
- 连接管理：每用户最多 3 条、每实例 2000 条，超限 429 `NOTIFY_STREAM_LIMITED`；`SseEmitter` 超时 30 分钟，客户端按 `retry` 自行重连并带 `Last-Event-ID`。
- 多实例（P1）：Redis Pub/Sub `notify:sse:{userId}` 广播通知 JSON，各节点只推给本地 emitter；单实例先使用内存注册表，接口保持不变。
- 降级：Redis/推送失败不影响 DB 落库，前端轮询 `/notifications` 与 `unread-count`。

## 12. 关键时序

```
发评论（审核开启）
Client → Controller → 限流 → SiteConfig(guest/audit/词库) → ArticleBrief → 父评论校验
      → 敏感词替换 → INSERT comments(PENDING) → 201(status=PENDING)
```

```
发评论（audit_enabled=false 且未命中）
… → INSERT comments(APPROVED) + ArticleCounterService#increment(COMMENT,+1)（M2，同事务）→ commit
   → AFTER_COMMIT: CommentApprovedEvent → M4；M3 写 COMMENT_REPLY 通知 + 未读 INCR + SSE
```

```
审核通过
EDITOR → 条件 UPDATE status(PENDING→APPROVED)，affected=1 → #increment(COMMENT,+1)（M2，同事务）→ commit
       → CommentApprovedEvent → M4 积分(comment_approved:{id})；通知 COMMENT_APPROVED（作者）+ 根评论 ARTICLE_COMMENTED（文章作者）或回复 COMMENT_REPLY；审计
```

```
点赞 / 取消
PUT → ArticleBrief → INSERT reaction(dup→返回当前状态) → #increment(LIKE,+1)（M2）→ commit
    → ReactionToggledEvent(active=true) → M4 +1；ARTICLE_LIKED 通知
DELETE → DELETE reaction，affected=1 → #decrement(LIKE,1)（M2）→ commit
       → ReactionToggledEvent(active=false) → M4 -1（不通知）
```

```
留言
Client → 限流(IP) → guest_enabled → 敏感词 → INSERT guestbook(PENDING|APPROVED) → 201
```

```
通知消费（订单）
M4 commit → OrderStatusChangedEvent → M3 AFTER_COMMIT → 去重 SETNX
         → INSERT notifications → 未读 INCR → SSE/本地推送
```

## 13. 并发、事务、幂等

| 场景 | 事务边界 | 锁/条件更新 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| 评论插入+计数 | 单事务 | 先 INSERT 明细，再调 M2 计数端口（单条原子 UPDATE） | 无（重复提交由限流兜底） | 回滚，500 或 429 |
| 审核 | 单事务 | `WHERE id=? AND status=:from AND deleted_at IS NULL` | 同状态重复请求幂等 | affected=0 → 409 或 200 |
| 点赞设置 | 单事务 | `uk_target`；锁序 reaction 行 → M2 计数行（`articles`） | 唯一键 | 冲突即视为已存在，返回当前计数 |
| 点赞/取消交错 | 各自单事务 | 同上，计数随明细 affected 行数，由 M2 端口执行 | 唯一键 + affected | 最终态=最后一次提交；计数与明细一致 |
| 评论点赞 | 单事务 | 联合主键 + affected | 联合主键 | 重复返回当前状态 |
| 通知重复消费 | 每通知独立事务 | `notifications.dedupe_key` 唯一键 + Redis 快路径 | dedupe_key | 命中唯一冲突即跳过并记录 |
| 批量已读并发 | 单事务 | `WHERE is_read=0` | affected 驱动 DECR | 未读数 Lua 钳制 ≥0 |
| 对账 vs 写入 | 任务分批 | 仅处理 10 分钟无明细变更的文章；按差值经 M2 端口收敛 | 以明细重算 | 差异审计，不阻塞线上 |

- 锁顺序固定：`article_reactions`/`comment_likes` 明细行 → `articles`（经 M2 计数端口）/`comments` 计数行；禁止反向，避免死锁。
- 计数端口调用失败（异常或非预期 0 行）与明细同一事务整体回滚；0 行表示已到目标态，不视为失败。

## 14. 权限与归属

| 操作 | 角色 | 归属/校验 |
|---|---|---|
| 看评论 | OPTIONAL | 匿名仅 APPROVED；登录用户另见自己的 PENDING；软删文章 404 |
| 看留言 | PUBLIC | 仅 APPROVED |
| 发评论/留言 | OPTIONAL | 游客受 `comment.guest_enabled`；登录用户校验 ACTIVE |
| 删评论/留言 | OWNER | `user_id` 等于当前用户；ADMIN 可越过；游客内容仅管理端可删 |
| 审核/管理删 | EDITOR（仅自己文章，不能自审）/ADMIN | EDITOR 必须是文章作者且评论不是自己写的；ADMIN 不限；全部写审计 |
| 点赞/收藏/评论点赞 | USER | 游客 401；文章须可交互 |
| 我的列表/通知/SSE | USER | 仅本人数据，忽略他人 id |

## 15. 错误码（已登记到 04 §7.5）

| code | HTTP | 触发条件 |
|---|---|---|
| `COMMENT_NOT_FOUND` | 404 | 评论不存在/已删/不可见 |
| `COMMENT_PARENT_INVALID` | 422 | 父评论跨文章、未过审、已删 |
| `COMMENT_DISABLED` | 422 | 文章 `allow_comment=0` |
| `COMMENT_GUEST_DISABLED` | 403 | 游客评论被关闭 |
| `COMMENT_STATUS_INVALID` | 409 | 审核流转非法或并发已被修改 |
| `REACTION_TYPE_INVALID` | 400 | `{type}` 非 `like/favorite` |
| `GUESTBOOK_NOT_FOUND` | 404 | 留言不存在/已删/不可见 |
| `GUESTBOOK_DISABLED` | 403 | 游客留言被关闭 |
| `GUESTBOOK_STATUS_INVALID` | 409 | 留言流转非法 |
| `NOTIFY_STREAM_LIMITED` | 429 | 单用户 SSE 连接超限 |
| `NOTIFY_STREAM_UNAVAILABLE` | 503 | 推送资源耗尽/关闭 |
| 复用 | 400/401/403/404/429/500 | `COMMON_VALIDATION_ERROR`、`AUTH_*`、`COMMON_NOT_FOUND`、`COMMON_RATE_LIMITED`、`COMMON_INTERNAL_ERROR` |

## 16. 验收用例（32）

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M3-001 | audit=true | 发根评论 | 201；`PENDING`；公开列表不含；本人可见 |
| TC-M3-002 | audit=false | 发根评论 | 201；`APPROVED`；列表可见；`comment_count+1` |
| TC-M3-003 | 词库含「敏感词」 | 发含全角/零宽变体内容 | 内容掩码为 `*`；强制 `PENDING`（audit=false 亦然） |
| TC-M3-004 | 已有根评论 | 回复根评论，再回复该回复 | 两跳均 `root_id` 同一根；无第三层 |
| TC-M3-005 | 另一文章评论/已删父评论 | 用其 id 作 parentId | 422 `COMMENT_PARENT_INVALID` |
| TC-M3-006 | 25 条根评论 | 游标翻页 size=10 | 三页不重不漏；子评论归属正确 |
| TC-M3-007 | 根评论有 3 条子评论 | 删除根评论 | 根显示「已删除」占位；3 条子评论仍可见 |
| TC-M3-008 | 子评论无回复 | 删除该子评论 | 列表不再返回该子评论 |
| TC-M3-009 | — | content=2000 / 2001 码点 | 2000 成功；2001 返回 400 |
| TC-M3-010 | guest_enabled=false | 匿名发评论 | 403 `COMMENT_GUEST_DISABLED` |
| TC-M3-011 | — | guestNickname 1 / 30 / 31 码点 | 1、30 成功；31 返回 400 |
| TC-M3-012 | 同一用户 | 10s 内连发 2 条 | 第 2 条 429，带 `Retry-After` |
| TC-M3-013 | 当日已 50 条 | 再发 1 条 | 429 |
| TC-M3-014 | 用户 A 评论 | 用户 B 调 OWNER 删除 | 403；文章作者或 ADMIN 走 `/admin/comments/{id}` 成功 |
| TC-M3-015 | PENDING 评论 | 文章作者通过 | `APPROVED`；计数 +1；发事件；作者收 `COMMENT_APPROVED`；审计含 remark；审自己的评论 403 |
| TC-M3-016 | APPROVED 评论 | 文章作者下架 | `HIDDEN`；计数 -1；**不发 `CommentRemovedEvent`，积分不反转**（D13） |
| TC-M3-017 | APPROVED 评论 | 直接改 PENDING；并发两次通过 | 前者 409；后者仅一次生效，计数只 +1 |
| TC-M3-018 | — | 连续 PUT like 10 次 | 仅 1 行明细；`like_count+1`；响应均 200 active=true |
| TC-M3-019 | — | 100 并发 PUT like（同用户同文章） | 唯一键冲突被吞；最终 1 行、计数 +1 |
| TC-M3-020 | 已点赞 | DELETE 两次 | 第 1 次计数 -1；第 2 次 200、计数不变；事件仅一次反转 |
| TC-M3-021 | — | 收藏后查 `/me/favorites`、`/me/likes` | 收藏进前者、不进后者；反之亦然 |
| TC-M3-022 | 人工把 like_count 改为 +5 | 执行对账 | 计数回正；修复经 M2 `ArticleCounterService#increment/decrement` 按差值收敛（M3 不直接 UPDATE `articles`）；`audit_logs` 记录原值/新值 |
| TC-M3-023 | APPROVED 评论 | POST/DELETE like 各两次 | 明细 1 行→0 行；`like_count` 同步；重复请求幂等 |
| TC-M3-024 | — | 留言 1000/1001 字、连发两次 | 1000 成功、1001 400；第 2 次限流 429；列表仅 APPROVED |
| TC-M3-025 | 收到 3 条通知 | 批量已读 2 条两次、再全部已读 | unread 3→1；重复 updated=0；read-all 后 0 |
| TC-M3-026 | 同一订单事件投递 2 次（含 `COMPLETED`） | 消费 | 每个 toStatus 仅 1 条通知（`dedupe_key=order:{orderNo}:{toStatus}`）；`COMPLETED` 产生 `ORDER_COMPLETED` |
| TC-M3-027 | 已登录，SSE 已连接 | 用 fetch 流式读取并带 `Authorization` | 15s 内收到心跳；事件为 `notification`；`Last-Event-ID` 不重复补发；未带 token 401 |
| TC-M3-028 | DRAFT/PRIVATE 文章；他人通知 id；未登录 SSE | 点赞/批量已读/连接 | 404；他人 id 被忽略；401 |
| TC-M3-029 | 根评论过审 | 查文章作者通知 | 收到 `ARTICLE_COMMENTED`（linkType=ARTICLE, linkId=articleId）；评论作者收到 `COMMENT_APPROVED`；同一 commentId 重复投递不新增行 |
| TC-M3-030 | 匿名 / 登录用户各有 PENDING 评论 | GET 评论列表 | 匿名看不到 PENDING；登录用户能看到自己的 PENDING（带 status），看不到他人的 |
| TC-M3-031 | 文章 `like_count=0` | 调用 M2 `ArticleCounterService#decrement(LIKE,1)` | 返回 0、影响 0 行；计数保持 0，不抛异常、不产生负数 |
| TC-M3-032 | 文章 `comment_count=0`，人工使明细与计数一致 | 审核一条 APPROVED 评论后删除 | 计数 1→0；全流程只有 `ArticleCounterService` 写 `articles`，SQL 审计无 M3 直写 `articles` |

## 17. 性能与索引

| 查询 | 索引 | 预期 | 验收 |
|---|---|---|---|
| 根评论分页 | `idx_article(article_id,status,root_id,created_at)` | `type=ref`，扫描≈一页；EXISTS 子查询走 `idx_root` | 20 根 + 200 子 P95 < 100ms |
| 子评论批量 | `idx_root(root_id,created_at)` | `type=range`，`root_id IN` 20 个 | 子查询 ≤ 50ms |
| 我的点赞/收藏 | `idx_user(user_id,type,created_at)` | 反向索引扫描，无 filesort（id 兜底列除外） | size=50 P95 < 80ms |
| 通知列表 | `idx_recipient_created` | `type=ref` + 小 filesort（id 并列） | P95 < 80ms |
| 未读数 | `idx_recipient_read` / Redis | Redis O(1)；回源 COUNT 走覆盖索引 | P95 < 10ms（命中） |
| 审核列表 | `idx_moderation(status,created_at)` | `type=ref` | P95 < 100ms |
| 留言公开列表 | `idx_status_created` | `type=ref` + 范围 | P95 < 80ms |
| 计数更新（M2 端口） | PRIMARY | 单行原子 `UPDATE`，行锁 | toggle P95 < 30ms |

- 所有列表禁止 `SELECT *`；公开字段显式裁剪，不返回 `ip`/`user_agent`（管理端仅返回 ip）。
- 评论树固定 2 次查询 + 1 次用户信息批量查询；禁止按评论逐条查用户。

## 18. 事件与跨模块调用

| 事件/调用 | 方向 | 载荷 | 消费方 | 幂等 |
|---|---|---|---|---|
| `CommentApprovedEvent` | 发布 | commentId, articleId, commenterId, articleTitle | M4 | `comment_approved:{commentId}` |
| `CommentRemovedEvent` | 发布 | commentId, articleId, commenterId | M4（删除时） | `comment_removed:{commentId}` |
| `ArticleModeratedEvent` | 订阅（M2） | articleId, authorId, moderatorId, action, reason, version | M3 生成 SYSTEM 通知 | `article_moderated:{articleId}:{action}:{version}` |
| `ReactionToggledEvent` | 发布（仅 LIKE） | articleId, authorId, actorId, type, active | M4 | `like:{articleId}:{actorId}` / `unlike:...` |
| `OrderStatusChangedEvent` | 订阅 | orderId, orderNo, userId, fromStatus, toStatus | M3 | `order:{orderNo}:{toStatus}` |
| `PointsAdjustedEvent` | 订阅 | ledgerId, userId, amount, balanceAfter, reason | M3 | `points:{ledgerId}` |
| `ArticleQueryService#getBrief` / `#getBriefs` | 调用 M2 | articleId / ids | M3 | 只读 |
| `ArticleCounterService#increment` / `#decrement` | 调用 M2 | articleId、`CounterType`、delta | M3 | 同事务；0 行=已到目标态 |
| `UserQueryService#getBrief` / `#getBriefs` | 调用 M1 | userId / ids | M3 | 只读 |
| `SiteConfigService` | 调用 M0 | key | M3 | 缓存 |
| `AuditRecorder#record` | 调用 M7 | action/target/from/to/remark | — | 只写不读 |

- 游客评论无 `commenterId`，不发积分事件（M4 无接收人），也不发 `COMMENT_APPROVED` 通知。
- 事件监听异常只记日志；不因通知失败回滚评论/点赞。
- 收藏、评论点赞不产生跨模块事件，避免 M4 误判为点赞积分。
- 文章三计数只经 `ArticleCounterService` 同步写入，M3 不直接写 `articles`、不为计数新增事件。

## 19. 待确认事项与契约问题

### 19.1 待确认（均给默认值）

| 事项 | 默认方案 | 需谁确认 |
|---|---|---|
| 评论作者昵称/头像来源 | M1 `UserQueryService#getBrief(ids)` / `#getBriefs(ids)`（00 §9.2 已允许） | 已解决 |
| `/me/favorites` 文章摘要 | M2 `ArticleQueryService#getBriefs(ids)` 批量取，不逐条调用 | 已解决 |
| 文章计数写入权 | 已解决：M3 在自身事务内调用 M2 `ArticleCounterService#increment/decrement`，不直接 UPDATE `articles`（00 §9.2 已列该端口） | 已解决 |
| 审核 `remark` 落库 | 写 `audit_logs`，不新增列 | 已解决 |
| 对账差异处理 | 自动修正三个计数并审计 | 已解决 |
| `OrderStatusChangedEvent` 的 `COMPLETED` | 发 `ORDER_COMPLETED` 通知 | 已解决 |
| 通知列表分页 | 偏移分页 `page/pageSize` | 仍待前端确认 |
| 子评论展示上限 | 每根 200 条 + `hasMoreReplies`，超出用 `?rootId=` 翻页 | 仍待前端确认 |
| 游客评论是否始终审核 | 跟随 `comment.audit_enabled`（false 即直通） | 仍待产品确认 |
| 自己给自己点赞 | 允许，仅跳过通知 | 仍待产品确认 |
| 通知保留期 | 不主动清理，保留 `idx_created` 供后续任务 | 仍待运维确认 |
| SSE 鉴权 | `fetch` 流式读取 + `Authorization` 头，不使用原生 `EventSource` | 已解决 |

### 19.2 契约问题（只报告，不在本模块变更）

| # | 问题 | 状态 |
|---|---|---|
| 1 | `docs/04-接口契约.md` 未生成时错误码无登记处 | 已解决：全部登记到 04 §7.5 | 已解决 |
| 2 | 00 §9.2 未列 M3→M1 用户只读接口 | 已解决（1.2 增列 `UserQueryService#getBrief/getBriefs`） |
| 3 | 00 §9.2 仅列 `getBrief` 单条、无批量 | 已解决（1.2 增列 `getBriefs(ids)`） |
| 4 | M3 需更新 `articles` 三列计数，但 `articles` 属 M2 | 已解决（M2 §16.2 提供 `ArticleCounterService#increment/decrement`，M3 同事务调用，不直写 `articles`） |
| 5 | `notifications` 无去重键列/唯一索引 | 已解决（DDL 已加 `dedupe_key` + `uk_dedupe`） |
| 6 | `PointsAdjustedEvent` 载荷无 `ledgerId` | 已解决（00 §9.1 载荷含 `ledgerId`，键 `points:{ledgerId}`） |
| 7 | `NotificationType` 缺「新评论」「订单完成」 | 已解决（`ARTICLE_COMMENTED`、`ORDER_COMPLETED` 已冻结） |
| 8 | `ReactionToggledEvent` 幂等键不含 `type` | 已解决（仅 `LIKE` 发布，收藏不发积分事件） |
| 9 | `GET /articles/{articleId}/comments` 语义实为 OPTIONAL | 已解决（00 §4 已改 OPTIONAL） |
| 10 | 原生 `EventSource` 无法携带 `Authorization` | 已解决（统一 `fetch` 流式读取） |
| 11 | `notification.link_type` 未在 00 §6.1 冻结 | 已解决（固定 `ARTICLE/COMMENT/GUESTBOOK/ORDER/POINTS/SYSTEM`） |
| 12 | 昵称列宽 50，业务上限 30 | 已解决（API 1~30，DB 50 仅余量） |
| 13 | `ArticleQueryService#getBrief` 未定义字段 | 已解决（M2 §16.1 冻结方法清单与字段） |
| 14 | 00 §6.1 的 `AuditAction` 未覆盖互动对账（本文使用 `INTERACTION_RECONCILE`） | 已解决（00 §6.1 已含 `INTERACTION_RECONCILE`） |