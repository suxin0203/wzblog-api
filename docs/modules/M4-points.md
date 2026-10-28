# M4 · 积分商城（模块设计文档）

> 负责人 Agent：M4 负责人
> 依赖模块：M0（审计、配置、事件基础设施）、M1（用户、`UserLoggedInEvent`）、M3（通知事件消费方）、M5（商品图引用）
> 对应阶段：阶段 4
> 状态：已定稿（错误码已登记到 04 §7.6）
> 契约版本：以 [00-文档索引与约定](../00-文档索引与约定.md) 1.6 为准

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.modules.points`（`api / app / domain / infra` 四层，见 02 第 4 节） |
| 表 | `points_accounts`、`points_ledger`、`titles`、`user_titles`、`points_goods`、`points_orders`、`points_order_logs`（字段一律见 [03](../03-数据库设计.md) 3.4） |
| 对外事件 | 发布：`OrderStatusChangedEvent`、`PointsAdjustedEvent`；订阅：`UserLoggedInEvent`、`ArticlePublishedEvent`、`ArticleDeletedEvent`、`CommentApprovedEvent`、`CommentRemovedEvent`、`ReactionToggledEvent` |
| 依赖的 app 服务 | `SiteConfigService`（M0，配置与业务日时区）、`AuditRecorder#record`（M0 shared.audit）、`FileAppService#claimByKey(objectKey, ownerId, actor, bizType)` / `#releaseByKey(objectKey)`（M5，商品图 `biz_type=GOODS`） |
| 实现的端口 | `shared.profile.TitleLookup#findById/#findByIds`（M0 定义，M4 实现，供 M1 展示 `activeTitle`） |
| 被依赖 | M3 消费订单/调分事件生成通知；M7 只读聚合看板 |
| 事务边界 | 写操作全部在 `app` 层开事务；`api` 不开事务、不注入 Mapper |
| 关键指标 | `points_granted_total`、`points_consumed_total`、`order_created_total`（架构 8.3） |

## 2. 范围与非目标

- 做：积分账户与流水查询；积分发放引擎（幂等键、日上限、业务日 `Asia/Shanghai`）；商品 CRUD 与公开浏览；下单、取消、退款回补；订单状态机与后台履约；TITLE 商品授称号（称号系统定为 **P1**）；管理员调分与流水查询；对账修复任务；看板只读口径（供 M7）。
- 不做：现金支付/真实退款、购物车、多商品合并订单（一单一个 `goods_id`，`quantity` 支持 1~10）、优惠券、运费、评价晒单；不做文章/评论业务本身（只消费 M2/M3 事件）；不做文件上传（商品图走 M5）；不新增“我的称号/佩戴”路由——授予关系在 `GET /points/account` 的 `titles` 中返回，`activeTitle` 只读 `users.active_title_id`。
- 边界：除 M4 外任何模块**不得** `INSERT/UPDATE points_ledger`、`points_accounts`、`points_orders`；其他模块只能发事件或调用只读查询接口。

## 3. 路由清单（22 条，与 00 第 4 节逐字一致）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/points/account` | USER | 我的账户快照 + `titles[]` + `activeTitle`；账户行缺失时零值 VO（不写库），首次事件/下单时懒创建 |
| GET | `/points/ledger` | USER | 我的流水，倒序偏移分页，可按 `type` 过滤 |
| GET | `/points/goods` | PUBLIC | 上架商品列表（`ON_SALE`、未软删，按 `sort_order,id` 升序） |
| GET | `/points/goods/{id}` | PUBLIC | 商品详情；非 `ON_SALE`/已软删返回 404 |
| GET | `/points/orders` | USER | 我的订单，倒序，可按 `status` 过滤 |
| GET | `/points/orders/{orderNo}` | OWNER | 订单详情（含状态日志），归属校验 |
| POST | `/points/orders` | USER | 下单兑换；**必须携带 `Idempotency-Key`** |
| POST | `/points/orders/{orderNo}/cancel` | OWNER | 用户取消（`PENDING/APPROVED`），退款 + 回补库存 |
| GET | `/titles` | PUBLIC | 全部未删除称号；**不返回**任何用户授予关系 |
| GET | `/admin/points/goods` | ADMIN | 后台商品列表（含下架与已软删，可按状态筛选） |
| POST | `/admin/points/goods` | ADMIN | 创建商品 |
| PUT | `/admin/points/goods/{id}` | ADMIN | 全量更新商品 |
| DELETE | `/admin/points/goods/{id}` | ADMIN | 软删商品（`deleted_at`），不影响历史订单 |
| GET | `/admin/points/orders` | ADMIN | 全量订单查询（用户/状态/订单号/时间） |
| GET | `/admin/points/orders/{orderNo}` | ADMIN | 订单详情（含日志、完整收货信息） |
| POST | `/admin/points/orders/{orderNo}/transition` | ADMIN | 状态机流转（审核/驳回/发货/完成/取消） |
| GET | `/admin/points/ledger` | ADMIN | 全量流水查询（用户/类型/业务/时间） |
| POST | `/admin/users/{userId}/points` | ADMIN | 管理员调分（必写流水 + 审计 + 事件） |
| GET | `/admin/titles` | ADMIN | 称号列表（含软删标记） |
| POST | `/admin/titles` | ADMIN | 创建称号 |
| PUT | `/admin/titles/{id}` | ADMIN | 更新称号 |
| DELETE | `/admin/titles/{id}` | ADMIN | 软删称号（已持有记录保留） |

> 分页统一 `page/pageSize`（默认 1/20，最大 100），响应 `{list,total,page,pageSize,hasNext}`（00 5.4）。所有 ID 与 `orderNo` 均为字符串（00 5.1、03 1.2）。

## 4. DTO 契约

### 4.1 请求

| DTO | 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|---|
| `GoodsCreateRequest` / `GoodsUpdateRequest`（PUT 全量） | name | String | 是 | 去首尾空格后 1~100 字符 |
| | type | GoodsType | 是 | `PHYSICAL` / `TITLE` |
| | titleId | String(BIGINT) | 条件 | `TITLE` 必填且称号存在未软删；`PHYSICAL` 必须为 null |
| | description | String | 否 | ≤500 字符 |
| | imageKey | String | 否 | ≤255；M5 已确认文件的对象 key（`biz_type=GOODS`）；新建/替换时 `claimByKey(imageKey, operatorId, currentUser, GOODS)`，清空或替换旧值时 `releaseByKey(旧 imageKey)` |
| | pointsPrice | Integer | 是 | 1~100000（整数） |
| | stock | Integer | 否 | 0~999999；`null` 或缺省 = 不限量 |
| | status | GoodsStatus | 条件 | 创建可省略（默认 `ON_SALE`）；PUT 必填 |
| | sortOrder | Integer | 否 | -100000~100000，默认 0 |
| `OrderCreateRequest` | goodsId | String(BIGINT) | 是 | 纯数字；商品存在、`ON_SALE`、未软删 |
| | quantity | Integer | 否 | 默认 1，范围 1~10 |
| | receiverName | String | 条件 | `PHYSICAL` 必填，1~50 字符 |
| | receiverPhone | String | 条件 | `PHYSICAL` 必填，`^1[3-9]\d{9}$` |
| | receiverAddress | String | 条件 | `PHYSICAL` 必填，1~255 字符 |
| | userRemark | String | 否 | ≤255 字符 |
| `OrderTransitionRequest` | toStatus | OrderStatus | 是 | 6 值之一；不可达由服务层判 409（非 400） |
| | logisticsCompany | String | 条件 | `toStatus=SHIPPED` 且 `PHYSICAL` 必填，1~50 字符 |
| | logisticsNo | String | 条件 | 同上必填，`^[A-Za-z0-9-]{1,64}$` |
| | adminRemark | String | 条件 | ≤255；`REJECTED` 必填（同步写 `cancelled_reason`） |
| `PointsAdjustRequest` | amount | Integer | 是 | -100000~100000 且 ≠0（正加负扣） |
| | reason | String | 是 | 1~255 字符，写 `points_ledger.remark` 与审计摘要 |
| 查询参数 | `GET /points/ledger` | — | — | `type`（可选）、`page`、`pageSize` |
| | `GET /points/orders` | — | — | `status`（可选）、`page`、`pageSize` |
| | `GET /admin/points/ledger` | — | — | `userId`、`type`、`bizType`、`createdFrom`、`createdTo`（ISO-8601）、分页 |
| | `GET /admin/points/orders` | — | — | `userId`、`status`、`orderNo`、`createdFrom`、`createdTo`、分页 |
| | `GET /admin/points/goods` | — | — | `status`、`type`、`keyword`（name 模糊）、分页 |
| 请求头 | `Idempotency-Key` | String | **是** | 8~64 位可见 ASCII；仅 `POST /points/orders` 使用；同一用户 24 小时内唯一，重复请求返回同一订单；缺失 → 400 `COMMON_VALIDATION_ERROR`，键相同但请求体不同 → 409 `COMMON_IDEMPOTENCY_CONFLICT` |

> `TITLE` 商品下单时传 `receiver*` 一律 400（`COMMON_VALIDATION_ERROR`），避免无意义数据落库。`POST /points/orders/{orderNo}/cancel` 无请求体。
> PUT 为全量更新：`description`/`imageKey` 传 null 即清空，`stock=null` 即改为不限量；只允许改本表字段（Mass Assignment 防护）。

### 4.2 响应 VO

| VO | 字段（全部为出参） |
|---|---|
| `PointsAccountVO` | `userId`、`balance`、`totalEarned`、`totalSpent`、`updatedAt`、`titles`、`activeTitle`；`titles` 为 `[{id,name,cssClass,acquiredAt,active}]`（已授予，按 `acquired_at` 倒序），`activeTitle` 为 `{id,name,cssClass}` 或 null |
| `PointsLedgerVO` | `id`、`amount`、`balanceAfter`、`type`、`bizType`、`bizId`、`remark`、`createdAt`（不返回 `idempotencyKey`/`operatorId` 给普通用户） |
| `GoodsVO` | `id`、`name`、`type`、`titleId`、`titleName`、`description`、`imageKey`、`pointsPrice`、`stock`（null=不限）、`soldCount`、`status`、`sortOrder`、`createdAt` |
| `OrderVO` | `orderNo`、`userId`、`goodsId`、`goodsName`、`goodsType`、`unitPoints`、`quantity`、`totalPoints`、`status`、`receiverName`、`receiverPhone`、`receiverAddress`、`logisticsCompany`、`logisticsNo`、`userRemark`、`adminRemark`、`cancelledReason`、`approvedAt`、`shippedAt`、`completedAt`、`cancelledAt`、`createdAt`、`updatedAt`；详情额外 `logs[]` |
| `OrderLogVO` | `fromStatus`、`toStatus`、`operatorId`、`remark`、`createdAt` |
| `TitleVO` | `id`、`name`、`cssClass`、`description`、`sortOrder` |

> 列表中的 `receiverPhone` 中间四位掩码（如 `138****0000`），详情对 OWNER/ADMIN 返回明文（架构 8.8 字段裁剪）。列表永不为 `null`。
> “我的称号”不新增路由：已授予称号与当前称号统一由 `GET /points/account` 的 `titles`/`activeTitle` 返回；`active` 表示是否等于 `users.active_title_id`。无称号时 `titles=[]`、`activeTitle=null`。

## 5. 数据表使用与关键字段（字段定义只引用 03 3.4）

| 表 | 用途 | 关键字段/索引（本文只用这些做判定） |
|---|---|---|
| `points_accounts` | 余额快照，1:1 用户 | `balance`（≥0）、`total_earned`、`total_spent`、`version`；PK `user_id`。行按需懒创建（`ensureAccount(userId)`），管理员引导不直接写积分表；`total_*` 单调递增，仅统计，不参与余额推算 |
| `points_ledger` | 账本真相，只追加 | `amount`（≠0）、`balance_after`、`type`、`biz_type/biz_id`、`idempotency_key`（UK）、`operator_id`、`remark`、`created_at`（业务日依据） |
| `points_goods` | 商品与库存 | `points_price`、`stock`（NULL=不限）、`sold_count`、`status`、`sort_order`、`version`、`deleted_at` |
| `points_orders` | 订单与快照 | `order_no`（UK `uk_order_no`）、`idempotency_key`（**唯一键 `uk_user_idempotency(user_id, idempotency_key)`**；同一用户 24h 幂等窗口内唯一，窗口外同键仍命中原订单、不新建）、`goods_name_snapshot`、`goods_type_snapshot`、`unit_points`、`quantity`、`total_points`、`status`、收货三件套、物流两字段、`admin_remark`、`cancelled_reason`、四个状态时间、`version` |
| `points_order_logs` | 状态机审计（无外键） | `order_id`、`from_status`（创建为 NULL）、`to_status`、`operator_id`、`remark` |
| `titles` / `user_titles` | 称号定义与授予 | `titles.name`（UK）、`css_class`、`deleted_at`；`user_titles` PK `(user_id,title_id)`、`source_order_id` |

不变式：`balance = 逐笔 amount 累加`；`total_earned = Σ(amount>0)`；`total_spent = Σ|amount<0|`；两者只增不减（退款计入 `total_earned`）。`points_ledger` 禁止 UPDATE/DELETE，冲正一律新增反向流水（03 3.4.2、ADR-07）。

账户创建：`points_accounts` 行按需懒创建（`ensureAccount(userId)` = `INSERT ... ON DUPLICATE KEY UPDATE user_id=user_id`），在注册后首次访问账户、首个积分事件或首次下单时创建；管理员引导（03 §9.2）只建 `users` 行，不直接写积分表。读取账户但无行时返回零值 VO，不落库。

## 6. 积分规则与订单状态机

### 6.1 积分规则表（与 00 第 7 节一致；业务日 `Asia/Shanghai`）

| type | 金额 | 日上限 | 幂等键 | 触发事件 | 备注 |
|---|---|---|---|---|---|
| `DAILY_LOGIN` | +5 | 5（每日 1 次） | `login:{userId}:{businessDate}`（`businessDate=yyyyMMdd`，Asia/Shanghai） | `UserLoggedInEvent`（`firstLoginOfDay=true` 才处理） | 不按 SUM 判断，直接靠幂等键 |
| `ARTICLE_PUBLISH` | +10/篇 | 100 | `publish:{articleId}` | `ArticlePublishedEvent` | 锁内 SUM 求已得，`grant=MIN(10, 100-earned)` |
| `COMMENT_APPROVED` | +5/条 | 50 | `comment_approved:{commentId}` | `CommentApprovedEvent` | 同上，`grant=MIN(5, 50-earned)` |
| `LIKE_RECEIVED` | +1 | 无 | `like:{articleId}:{actorId}` | `ReactionToggledEvent`（`type=LIKE && active=true`） | 每 article+actor 仅首次；**自赞不计分**（G1：authorId==actorId 跳过） |
| `LIKE_REVERSED` | -1 | 无 | `unlike:{articleId}:{actorId}` | `ReactionToggledEvent`（`active=false`） | 仅当存在对应 `LIKE_RECEIVED` 才扣；**按正向流水收款人退**（D9，接管后不退给新作者） |
| `ARTICLE_PUBLISH_REVERSAL` | `-min(收款人当前余额, 该文已发放 ARTICLE_PUBLISH 正流水合计)` | 无 | `article_delete:{articleId}:{userId}` | `ArticleDeletedEvent`（**仅 purge 时发布，D13**） | 按 `biz_type=ARTICLE,biz_id=articleId` 查正向流水的**原收款人**（D9）；余额不足时 `balance_after` 归 0，差额写 `remark` |
| `COMMENT_APPROVED_REVERSAL` | -该评论实际发放 | 无 | `comment_removed:{commentId}` | `CommentRemovedEvent`（**仅评论删除时发布，D13；HIDDEN 不发**） | 未发放过则跳过 |
| `REDEEM` | -`total_points` | 无 | `redeem:{orderNo}`（下单请求幂等由 `points_orders.idempotency_key` 承担） | 下单 | 余额不足拒绝 |
| `REFUND` | +`total_points` | 无 | `order-refund:{orderNo}` | 取消/驳回 | 不删原 `REDEEM` |
| `ADMIN_ADJUST` | 任意 ± | 无 | `admin-adjust:{ULID}`（服务端生成） | `POST /admin/users/{userId}/points` | `operator_id` 必填，必写审计 |

- 反转规则（`ARTICLE_PUBLISH_REVERSAL`）：反转金额 `= -min(收款人当前余额, 该文已发放的 ARTICLE_PUBLISH 正流水合计)`；落账后 `balance_after = 0`，差额 `= 已发放合计 - 实际扣减额` 写入 `remark`（如「余额不足，少扣 X」）并打 WARN 指标；**任何情况下不产生负余额**（满足 `balance >= 0` 约束）。`min(...)=0`（余额已为 0）时不写流水（`amount≠0` 约束），仅 WARN + 指标，差额只记日志；差额进入 `remark` 仅指实际产生流水的情形。其它反转（`LIKE_REVERSED`、`COMMENT_APPROVED_REVERSAL`）金额不超过对应正向流水绝对值，余额不足同样扣至 0 不越界。
- **反转按原流水收款人（D9）**：文章接管后 `author_id` 会变，但历史积分仍属于原作者；`ARTICLE_PUBLISH_REVERSAL` 按 `biz_type/biz_id` 回查正向流水的 `user_id`，`LIKE_REVERSED` 按 `like:{articleId}:{actorId}` 回查，**不按当前 `articles.author_id`** 退钱。
- **D13 软删/隐藏不反转**：文章软删、评论 HIDDEN 不发反转事件；只有文章 purge、评论删除（终态）才反转；恢复/重新通过也不补发（因为没扣过）。
- 日上限查询：`SUM(amount) WHERE user_id=? AND type=? AND amount>0 AND created_at>=? AND created_at<?`，区间为业务日 `[D-1 16:00Z, D 16:00Z)`；禁止 `DATE(created_at)=CURDATE()` 与 `LIKE 'prefix%'`（03 5.1 Q8）。
- 上限值为默认常量，可被 `wzblog.points.*` 覆盖（08 4.2）；文档与测试以默认值为准。

### 6.2 订单状态机

| 当前 | 允许的下一状态 | 触发者 | 附加规则 |
|---|---|---|---|
| `PENDING` | `APPROVED` / `REJECTED` / `CANCELLED` | ADMIN 审核；ADMIN 驳回；用户或 ADMIN 取消 | `REJECTED`/`CANCELLED` 退积分 + 回补库存 |
| `APPROVED` | `SHIPPED` / `CANCELLED` | ADMIN 发货；用户或 ADMIN 取消 | `PHYSICAL` 发货必填物流；取消退款 + 回补 |
| `SHIPPED` | `COMPLETED` | ADMIN 确认（用户确认收货无独立路由，见 16） | 不退款、不回补 |
| `COMPLETED` / `CANCELLED` / `REJECTED` | — | — | 终态；在 `PENDING→APPROVED` 时 `TITLE` 已授予 |

- 全部流转用 `UPDATE ... WHERE id=? AND status=:from`；影响行数 0 → `ORDER_STATE_INVALID`（409），不重试。
- `TITLE` 在 `APPROVED` 授予（`INSERT IGNORE user_titles`，`source_order_id` 记订单）；`PHYSICAL` 在 `SHIPPED` 写物流。`TITLE` 同样按 `APPROVED → SHIPPED → COMPLETED` 流转，`SHIPPED` 不要求物流。
- 每次流转写 `points_order_logs`，并发布 `OrderStatusChangedEvent`；创建订单只写一条 `from_status=NULL,to_status='PENDING'` 日志，不发事件。

### 6.3 商品与称号规则

- 公开列表/详情只认 `status='ON_SALE' AND deleted_at IS NULL`；下架/软删不影响历史订单与 `user_titles`。
- 软删商品不出现在公开结果；`DELETE` 幂等，已软删再次删除仍返回成功且不改变 `deleted_at`。
- 称号名唯一（软删后仍占用 UK），创建/更新重名 → 409 `TITLE_NAME_CONFLICT`；称号 CRUD 的错误码统一用 `TITLE_` 前缀（`TITLE_NOT_FOUND`、`TITLE_NAME_CONFLICT`），不塞进 `GOODS_`；`GET /titles` 只返回 `deleted_at IS NULL`，且不含任何用户关联。
- `TITLE` 商品必须绑定未软删的 `title_id`；软删称号时 `points_goods.title_id` 由 FK 置 NULL，对应商品自动转为不可兑换（校验时 409 `GOODS_OFF_SHELF`）。

## 7. 账本统一入口（`PointsAppService`）

### 7.1 唯一入口与签名

```
PointsResult grant(long userId, PointsType type, int amount, String bizType, String bizId,
                   String idempotencyKey, String remark);
PointsResult deduct(long userId, PointsType type, int amount, String bizType, String bizId,
                    String idempotencyKey, String remark);   // amount 为正，落账写负值
record PointsResult(long ledgerId, long userId, int grantedAmount, int balanceAfter, boolean duplicate)
```

- `grant`：内部按 6.1 求 `grantedAmount`（日上限、反转上限），`<=0` 不写流水并返回 `grantedAmount=0`。
- `deduct`：`balance < amount` → `POINTS_INSUFFICIENT`（422），不写流水。
- 任何模块（含 M1/M2/M3）**不得**直接写 `points_ledger`/`points_accounts`；M2/M3 只发事件，M1 只发登录事件。M4 内部也只有 `PointsAppService` 可调用 `PointsLedgerMapper` 的写方法（ArchUnit 约束 `infra` 写方法名 `insert*` 仅允许 `app` 层调用）。

### 7.2 单次记账事务步骤（`@Transactional(REQUIRED)`）

1. 参数校验：`amount>0`、类型与方向匹配（反转/退款为负向类型）、幂等键非空且 ≤128。
2. **幂等快路径**（无锁）：按 `idempotency_key` 查流水；命中直接返回原结果，不进入锁竞争。
3. **锁账户**：`INSERT INTO points_accounts(user_id) VALUES(?) ON DUPLICATE KEY UPDATE user_id=user_id`（懒创建）；再 `SELECT balance,total_earned,total_spent,version FROM points_accounts WHERE user_id=? FOR UPDATE`。
4. **日上限**（仅 `ARTICLE_PUBLISH`/`COMMENT_APPROVED` 需在锁内 SUM；`DAILY_LOGIN` 靠幂等键，`deduct`/`REFUND` 跳过）：计算 `grantedAmount`；为 0 则提交空事务并返回。
5. **算余额**：反转类型（`ARTICLE_PUBLISH_REVERSAL`/`LIKE_REVERSED`/`COMMENT_APPROVED_REVERSAL`）先按 6.1 反转规则收敛金额（`ARTICLE_PUBLISH_REVERSAL` 取 `-min(balance, 已发放合计)`），再算 `newBalance = balance + signedAmount`；非反转的扣减 `newBalance<0` → `POINTS_INSUFFICIENT` 回滚。反转后 `balance_after` 可为 0，差额写 `remark`。
6. **写流水**：`INSERT INTO points_ledger(...) VALUES(...) ON DUPLICATE KEY UPDATE id=id`；影响行数 0 → 并发重复，抛内部 `DuplicateLedgerSignal` 使事务回滚，再按幂等键读已有流水返回（`duplicate=true`）。
7. **更新账户**：`UPDATE points_accounts SET balance=?, total_earned=?, total_spent=?, version=version+1 WHERE user_id=?`。
8. 返回 `PointsResult`；调用方（下单）在同一事务内继续写订单/日志。

### 7.3 硬规则

- 事务隔离用默认 `REPEATABLE READ`；账户行锁是串行化点，同一用户写入串行。
- 锁序：**商品 → 账户 → 订单/流水**；纯加分无商品，直接从账户开始。禁止先锁订单再锁账户。
- 幂等键全局唯一：`uk_idempotency` 是最后防线；快路径与唯一键双保险，重复请求必须返回原结果而不是报错。
- 日上限必须在账户行锁内计算，否则并发可破上限；**禁止**在锁外"先查后加"。
- 禁止 Redis 存余额/订单状态（ADR-04）；日志不打印金额明细以外个人信息。

## 8. 订单流程

### 8.1 下单 6 步（`PointsOrderAppService#create`，单事务）

1. **幂等预检（必须）**：`Idempotency-Key` 缺失/非法（非 8~64 位可见 ASCII）→ 400 `COMMON_VALIDATION_ERROR`；按 `uk_user_idempotency(user_id, idempotency_key)` 查 `points_orders`（同一用户，24h 幂等窗口）：命中且请求体一致（比对 `sha256(规范化 body)`）→ 200 返回**同一订单**，不再扣库存/积分；命中但请求体不同 → 409 `COMMON_IDEMPOTENCY_CONFLICT`；未命中才继续。并发同键由唯一键兜底，捕获 `DuplicateKeyException` 后回读并返回原订单。
2. **条件扣库存**（商品行锁）：
   ```sql
   UPDATE points_goods
   SET stock = CASE WHEN stock IS NULL THEN NULL ELSE stock - :qty END,
       sold_count = sold_count + :qty, version = version + 1
   WHERE id = :goodsId AND status='ON_SALE' AND deleted_at IS NULL
     AND (stock IS NULL OR stock >= :qty);
   ```
   影响 0 行 → 回查区分 `GOODS_OFF_SHELF`（409）与 `GOODS_STOCK_INSUFFICIENT`（422）。
3. **锁账户**：按 7.2 步骤 3~5 计算，`total=unit_points*quantity`；`balance < total` → 422 回滚（库存更新一并回滚）。
4. **写流水**：`REDEEM`，`amount=-total`，`biz_type='ORDER'`、`biz_id=orderNo`、`balance_after=新余额`；键按 6.1。
5. **更新账户**：`balance-=total`、`total_spent+=total`、`version+1`。
6. **写订单 + 状态日志**：`points_orders` 落 `idempotency_key` 与商品名/类型/单价/总额快照，状态 `PENDING`；`points_order_logs(from=NULL,to='PENDING',operator=userId)`；提交。
- `orderNo` 生成：`PO` + `yyyyMMddHHmmss`（`Asia/Shanghai`）+ 8 位大写字母数字 = 24 位；`uk_order_no` 冲突重试 3 次。
- `TITLE` 预校验：用户已持有该 `title_id` → 409 `ORDER_TITLE_ALREADY_OWNED`（避免白扣分）。

### 8.2 取消 / 驳回（退款 + 回补，单事务）

1. 按 `orderNo` 读订单（不加锁）；`user_id` 不符 → 404；已是 `CANCELLED` → 幂等返回 200 原订单（不重复退款）；其他不可取消状态（`SHIPPED/COMPLETED/REJECTED`）→ 409 `ORDER_STATE_INVALID`。
2. **先锁商品**：`UPDATE points_goods SET stock=CASE WHEN stock IS NULL THEN NULL ELSE stock+:qty END, sold_count=GREATEST(sold_count-:qty,0), version=version+1 WHERE id=:goodsId`。
3. **再锁账户**（`FOR UPDATE`），随后条件更新订单：
   ```sql
   UPDATE points_orders
   SET status=:to, cancelled_at=NOW(3), cancelled_reason=:reason,
       admin_remark=:adminRemark, version=version+1
   WHERE id=:id AND status=:from;
   ```
   影响 0 行 → `ORDER_STATE_INVALID`（409），整个事务回滚（库存回补自动撤销）。
4. 写 `REFUND` 流水（`amount=+total`，键 `order-refund:{orderNo}`）+ 更新账户（`balance+=total`、`total_earned+=total`）。
5. 写 `points_order_logs`（from/to/operator/remark）。
6. 提交后发布 `OrderStatusChangedEvent`。

> 用户取消只能 `PENDING/APPROVED → CANCELLED`，原因固定 `用户取消`；管理员驳回 `PENDING→REJECTED` 必须带 `adminRemark`，并同步写 `cancelled_reason`。

### 8.3 后台 transition 矩阵

| from → to | 操作人 | 必填 | 副作用 |
|---|---|---|---|
| `PENDING → APPROVED` | ADMIN | — | `approved_at`、`admin_remark`；`TITLE` 授予称号；发事件 |
| `PENDING → REJECTED` | ADMIN | `adminRemark` | 退款 + 回补 + `cancelled_at`/`cancelled_reason`；发事件 |
| `PENDING → CANCELLED` | ADMIN | — | 同驳回的退款路径；发事件 |
| `APPROVED → SHIPPED` | ADMIN | `PHYSICAL` 必填物流两字段 | `shipped_at`、物流字段；发事件 |
| `APPROVED → CANCELLED` | ADMIN | — | 退款 + 回补；发事件 |
| `SHIPPED → COMPLETED` | ADMIN | — | `completed_at`；发事件 |
| 其他组合 | — | — | 409 `ORDER_STATE_INVALID`，不落任何日志 |

- `TITLE` 授予：`INSERT IGNORE INTO user_titles(user_id,title_id,source_order_id,acquired_at) VALUES(...)`；重复 approve 由状态机挡住，`INSERT IGNORE` 为第二道幂等。
- 条件更新统一 `WHERE id=:id AND status=:from`（03 7.5），状态时间字段用 `CASE WHEN :to=...` 一次性写入；禁止"读状态→判断→无条件 UPDATE"。

## 9. 并发、事务、幂等与对账

| 场景 | 事务边界 | 锁/条件更新 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| 50 并发抢库存 1 | 单事务 | 商品条件 UPDATE（8.1 步骤 2） | — | 1 成功；49 返回 422 `GOODS_STOCK_INSUFFICIENT`，库存 0 |
| 同 `Idempotency-Key` 重试 10 次 | 各自事务 | `uk_user_idempotency` + 流水快路径 | `idempotency_key` / `redeem:{orderNo}` | 1 单 1 次扣分；其余返回同一 `orderNo`（200）；请求体不同则 409 |
| 并发加分（同用户） | 各事件独立事务 | `SELECT ... FOR UPDATE` 账户行 | 各事件键 | 串行；余额与流水逐笔一致 |
| 日上限并发 | 单事务 | 账户行锁内 SUM | 同 6.1 | 总发放不超过 100/50；超出部分 `grantedAmount=0` |
| 并发取消同一订单 | 单事务 | 商品行锁 → 账户锁 → `WHERE status=:from` | 串行重试返回已取消订单（200）；真并发后者 409，均不重复退款 | 影响 0 行则回滚，库存回补自动撤销 |
| 下单 vs 取消交叉 | 单事务 | 固定 商品 → 账户 → 订单 | — | 无死锁；InnoDB 死锁检测命中时回滚后由调用方**最多重试 1 次**（仅限死锁，不重试状态冲突） |
| 对账修复 | `PointsReconcileJob` 单实例（ShedLock） | 按用户/业务日，差异用户各自事务 | `reconcile:{userId}:{date}` | 追加 `ADMIN_ADJUST` 修复流水 + 审计，不改历史流水 |

- **死锁避免**：所有涉及商品+账户的事务严格按 商品 → 账户 → 订单 加锁；事务内禁止调用外部 IO（M5/MinIO）；批量任务按 `user_id` 升序处理。
- **对账任务**（每日 03:30，ShedLock 单实例）：
  ```sql
  -- 余额 vs 流水分
  SELECT a.user_id, a.balance, COALESCE(SUM(l.amount),0) AS ledger_sum
  FROM points_accounts a LEFT JOIN points_ledger l ON l.user_id=a.user_id
  GROUP BY a.user_id, a.balance HAVING a.balance <> ledger_sum;
  -- 最后一条 balance_after vs 余额
  SELECT l.user_id, l.balance_after, a.balance
  FROM points_ledger l
  JOIN points_accounts a ON a.user_id=l.user_id
  JOIN (SELECT user_id, MAX(id) AS max_id FROM points_ledger GROUP BY user_id) m
    ON m.user_id=l.user_id AND m.max_id=l.id
  WHERE l.balance_after <> a.balance;
  ```
- 差异写 `audit_logs(action='POINTS_RECONCILE', target_type='USER')` 并打告警指标。
- **修复主体固定为 M4 `PointsReconcileJob`**：ShedLock 单实例，按用户/业务日扫描；无差异跳过；有差异时在同一用户事务内追加一笔 `ADMIN_ADJUST` 修复流水（金额 = 账本合计 − 账户余额，可正可负；`remark` 以 `RECONCILE_FIX:` 开头，写差异来源），幂等键 `reconcile:{userId}:{date}`（同用户同日只修一次），并写审计。修复以新增流水收敛 `balance = Σamount`，**不 UPDATE/DELETE 历史流水**，不新增路由。

## 10. 事件

### 10.1 订阅（`@TransactionalEventListener(AFTER_COMMIT)`，监听器自身开新事务）

| 事件 | 幂等键 | 处理逻辑 |
|---|---|---|
| `UserLoggedInEvent` | `login:{userId}:{businessDate}` | `firstLoginOfDay=false` 直接忽略；否则 `grant(DAILY_LOGIN,+5)` |
| `ArticlePublishedEvent` | `publish:{articleId}` | `grant(ARTICLE_PUBLISH,+10)`，日上限 100；重发/重复投递由键挡住 |
| `ArticleDeletedEvent` | `article_delete:{articleId}:{userId}` | 按正向流水收款人：求该文 `ARTICLE_PUBLISH` 已发额 X（按 `biz_id` 回查原收款人），X>0 才 `grant(ARTICLE_PUBLISH_REVERSAL, -min(收款人当前余额, X))`；余额归 0，差额记 `remark`（D9/D13：仅 purge 触发） |
| `CommentApprovedEvent` | `comment_approved:{commentId}` | `grant(COMMENT_APPROVED,+5)`，日上限 50 |
| `CommentRemovedEvent` | `comment_removed:{commentId}` | 查该评论 `COMMENT_APPROVED` 是否存在；存在才 `grant(COMMENT_APPROVED_REVERSAL,-X)` |
| `ReactionToggledEvent` | `like:{articleId}:{actorId}` / `unlike:{articleId}:{actorId}` | 仅 `type=LIKE`；`active=true` → 给 `authorId` +1；`active=false` → 仅当 like 流水存在时 -1 |

- 监听器异常：捕获后 `WARN` 日志 + `points_grant_failed_total{type}`，**不得向发布方抛异常**；`AFTER_COMMIT` 已提交，失败即丢失，靠对账与人工调分兜底（P1 再评估 outbox）。
- 业务日由 `loginAt` 按 `Asia/Shanghai` 计算；跨日边界（UTC 16:00）以事件时间为准。

### 10.2 发布

| 事件 | 时机 | 载荷 | 消费方 | 去重 |
|---|---|---|---|---|
| `OrderStatusChangedEvent` | 每次成功流转（含取消/驳回）提交后 | orderId, orderNo, userId, fromStatus, toStatus | M3 通知 | `order:{orderNo}:{toStatus}` |
| `PointsAdjustedEvent` | 管理员调分成功后 | ledgerId, userId, amount, balanceAfter, reason | M3 通知 | `points:{ledgerId}` |

> 事件类放 `modules.points.domain.event`；只允许载荷含标量，不放 Entity。订单创建不发事件。

## 11. 权限与归属

| 操作 | 角色 | 归属校验 | 说明 |
|---|---|---|---|
| 账户/个人流水/个人订单 | USER | 仅 `sub` 本人 | 不接受 body/path 传 `userId`，一律取 Token |
| 订单详情/取消 | OWNER | `orders.user_id == currentUserId`，ADMIN 可越过 | 越权按 404（不泄露存在性） |
| 商品/称号公开读取 | PUBLIC | — | 仅上架/未软删；`/titles` 不含授予关系 |
| 商品/称号管理 | ADMIN | — | 写操作必记审计（`GOODS_CREATE/GOODS_UPDATE/GOODS_DELETE/TITLE_*`） |
| 订单查询/流转 | ADMIN | — | 每次流转写 `points_order_logs` + 审计 `ORDER_TRANSITION` |
| 调分 | ADMIN | — | 必写 `ADMIN_ADJUST` 流水 + `audit_logs(action='POINTS_ADJUST')` + `PointsAdjustedEvent` |
| 全量流水 | ADMIN | — | 只能读，不提供修改/删除 |

- 控制器统一：用户侧 `@PreAuthorize("hasRole('USER')")` 语义（实现用 00 3.2 标记），管理侧独立 `/admin/**` 鉴权；业务层再做一次归属判断（双保险，架构 8.8 IDOR）。
- 用户停用（`status=DISABLED`）按 M1 统一拦截，本模块不重复实现。

## 12. 错误码（已登记到 04 §7.6）

| code | HTTP | 触发条件 | 前端提示 |
|---|---|---|---|
| `POINTS_INSUFFICIENT` | 422 | 扣分后余额 <0 | 积分不足 |
| `POINTS_ADJUST_INVALID` | 400 | `amount=0`/超范围 | 调整值不合法 |
| `GOODS_OFF_SHELF` | 409 | 商品下架/软删/称号失效 | 商品已下架 |
| `GOODS_STOCK_INSUFFICIENT` | 422 | 条件扣库存影响 0 行且商品仍上架 | 库存不足 |
| `GOODS_TITLE_REQUIRED` | 422 | `TITLE` 商品未绑定有效称号，或 `PHYSICAL` 传了 `titleId` | 商品配置错误 |
| `TITLE_NOT_FOUND` | 404 | 称号不存在/已软删（含绑定校验） | 称号不存在 |
| `TITLE_NAME_CONFLICT` | 409 | 称号名重复（含已软删占用） | 称号名已存在 |
| `ORDER_STATE_INVALID` | 409 | 条件更新影响 0 行 / 不可达流转 | 订单状态已变化 |
| `ORDER_RECEIVER_REQUIRED` | 422 | `PHYSICAL` 缺收货三件套 | 请填写收货信息 |
| `ORDER_LOGISTICS_REQUIRED` | 422 | `PHYSICAL` 发货缺物流 | 请填写物流信息 |
| `ORDER_TITLE_ALREADY_OWNED` | 409 | 下单时已持有该称号 | 已拥有该称号 |
| `COMMON_IDEMPOTENCY_CONFLICT` | 409 | `Idempotency-Key` 相同但请求体不同 | 重复提交内容不一致 |
| `COMMON_NOT_FOUND` | 404 | 订单/商品不存在或越权 | 资源不存在 |
| `COMMON_VALIDATION_ERROR` | 400 | 字段校验失败、缺失 `Idempotency-Key`、`TITLE` 传收货信息 | 参数不合法 |

> 称号 CRUD 统一使用 `TITLE_` 前缀（`TITLE_NOT_FOUND`、`TITLE_NAME_CONFLICT`），不借用 `GOODS_`；称号“已拥有”沿用订单侧 `ORDER_TITLE_ALREADY_OWNED`，不另设 `GOODS_TITLE_OWNED`（该名称未登记、不采用）。以上代码 04 已登记。

## 13. 验收用例

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M4-001 | 新用户无流水 | `GET /points/account` | 200，`balance=0`，三累计值为 0，`titles=[]`、`activeTitle=null`，不产生流水、不创建 `points_accounts` 行 |
| TC-M4-002 | 10 条流水 | `GET /points/ledger?page=1&pageSize=5` | 倒序、`hasNext=true`、`total=10`；`Σamount == balance` |
| TC-M4-003 | 当日未登录 | 投递 `UserLoggedInEvent(firstLoginOfDay=true)`，再重复 10 次 | 仅 1 条 `DAILY_LOGIN +5`（`uk_idempotency`）；`false` 时不写流水 |
| TC-M4-004 | 业务日边界 | `loginAt=2026-10-23T16:00:00Z`（上海 10-24 00:00） | 业务日算 10-24，与 `15:59Z` 分属两日 |
| TC-M4-005 | 已发 9 篇（90 分） | 并投 5 个 `ArticlePublishedEvent` | 仅 +10 达 100，其余 `grantedAmount=0`；重复同 `articleId` 只 1 条 |
| TC-M4-006 | 已发 10 评论（50 分） | 再投 2 个 `CommentApprovedEvent` | 不再加分（日上限 50） |
| TC-M4-007 | 作者被赞 | 投递 `ReactionToggledEvent(active=true,type=LIKE)` | 作者 +1，键 `like:{articleId}:{actorId}` |
| TC-M4-008 | 同上 | 投递 `active=false` | 追加 `LIKE_REVERSED -1`，余额回落；未点赞过时投递不写流水 |
| TC-M4-009 | 文章已发放 10 分，当前余额 5（已消费 5） | 投递 `ArticleDeletedEvent`（仅 purge 触发，D13） | 追加 1 条 `ARTICLE_PUBLISH_REVERSAL -5`（`-min(余额 5, 已发 10)`），`balance_after=0`，`remark` 含「余额不足，少扣 5」；余额不为负；重复投递同 `articleId` 不再扣 |
| TC-M4-010 | 评论过审 +5 | 投递 `CommentRemovedEvent` | 追加 `COMMENT_APPROVED_REVERSAL -5`；未发放过则跳过 |
| TC-M4-011 | 商品 stock=1，余额充足 | 50 并发 `POST /points/orders` | 1 个 201，49 个 422；`stock=0`、`sold_count=1`，无负库存 |
| TC-M4-012 | 商品 stock=NULL | 并发下单 5 次 | 全部成功，`stock` 仍为 NULL，`sold_count+5` |
| TC-M4-013 | 余额 50，商品 100 | 下单 | 422 `POINTS_INSUFFICIENT`；无订单、无流水、库存不变 |
| TC-M4-014 | 余额 1000 | 同 `Idempotency-Key`、同请求体提交 10 次 | 1 个 201 + 9 个 200，同一 `orderNo`；1 条 `REDEEM`，扣 1 次 |
| TC-M4-015 | 未带 `Idempotency-Key` | 提交下单 | 400 `COMMON_VALIDATION_ERROR`，不建单、不扣分 |
| TC-M4-016 | 订单 `PENDING` | `transition` 到 `SHIPPED` 或 `COMPLETED` | 409 `ORDER_STATE_INVALID`，无日志新增、余额不变 |
| TC-M4-017 | `PENDING` 实物单 | 用户 cancel | 200；`CANCELLED`、`REFUND +total`、库存回补、1 条日志 |
| TC-M4-018 | `PENDING` 实物单 | 管理员 `REJECTED` 带 `adminRemark` | `REJECTED`；退款 + 回补；`cancelled_reason=adminRemark` |
| TC-M4-019 | 已取消订单 | 再次 cancel | 200 返回原订单；不产生第二条 `REFUND` |
| TC-M4-020 | `TITLE` 订单 | `APPROVED` | `user_titles` 新增 1 行；重复 approve 被 409 挡住 |
| TC-M4-021 | 已持有称号 | 再次下单同称号 | 409 `ORDER_TITLE_ALREADY_OWNED`，不扣分 |
| TC-M4-022 | `PHYSICAL` 订单 `APPROVED` | `transition SHIPPED` 不带物流 | 422 `ORDER_LOGISTICS_REQUIRED`，状态仍 `APPROVED` |
| TC-M4-023 | A 的订单 | B 调 `GET /points/orders/{orderNo}` 或 cancel | 404，不泄露订单存在 |
| TC-M4-024 | ADMIN | `POST /admin/users/{id}/points` amount=+50 | `ADMIN_ADJUST +50`、账户 +50、`audit_logs(POINTS_ADJUST)`、`PointsAdjustedEvent(ledgerId)`；amount=0 时 400 且无副作用 |
| TC-M4-025 | 商品 `OFF_SHELF` | 公开列表/详情/下单 | 列表不含；详情 404；下单 409 `GOODS_OFF_SHELF` |
| TC-M4-026 | 下架商品有历史 `SHIPPED` 订单 | 完成订单 | 成功，状态 `COMPLETED`，不受下架影响 |
| TC-M4-027 | 普通用户 | 访问任意 `/admin/points/*` | 403 `AUTH_FORBIDDEN` |
| TC-M4-028 | 人为把某用户 `balance` 改大 | 跑 `PointsReconcileJob` | 追加 1 条 `ADMIN_ADJUST` 修复流水（键 `reconcile:{userId}:{date}`），`balance` 回到账本合计，写 `audit_logs(POINTS_RECONCILE)`；同日重跑不再追加 |
| TC-M4-029 | 单用户并发 50 次 +1（不同业务键） | 并发发放 | 最终余额精确 +50，流水 50 条，无死锁失败 |
| TC-M4-030 | 20 个商品、若干称号已授予 | `GET /points/goods`、`GET /titles` | 商品仅 `ON_SALE` 按 `sortOrder,id` 升序；称号返回全部未删且无 `userId`/授予信息 |
| TC-M4-031 | 用户已获 2 个称号，其中 1 个为 active | `GET /points/account` | `titles` 返回 2 项（含 `acquiredAt` 与 `active` 标记），`activeTitle` 与 active 项一致；无新增路由 |
| TC-M4-032 | 用户下单成功 | 检查 `orderNo` | 长度 24：`PO` + 上海时区 `yyyyMMddHHmmss` + 8 位大写字母数字 |
| TC-M4-033 | 商品更换/清空图 | 调 `PUT /admin/points/goods/{id}` | 新 key `claimByKey(GOODS)`、旧 key `releaseByKey`；不影响历史订单 |
| TC-M4-034 | 同一 `Idempotency-Key`、不同 `goodsId` | 提交 2 次 | 第 2 次 409 `COMMON_IDEMPOTENCY_CONFLICT`，不建单、不扣分 |

## 14. 性能与索引

| 查询 | 索引 | EXPLAIN 预期 | 验收 |
|---|---|---|---|
| 幂等快路径 / 重复单 | `uk_idempotency`、`uk_user_idempotency(user_id,idempotency_key)` | `type=const, key=uk_user_idempotency` | P95 < 10ms |
| 我的称号 | `user_titles` PK `(user_id,title_id)` | `type=ref/range`，按 `user_id` 前缀，回表 `titles` | P95 < 30ms |
| 日上限 SUM | `idx_user_type_created` | `type=range, key=idx_user_type_created`，无全表扫描 | 锁内 < 5ms（单用户日流水 < 100 行） |
| 我的流水 | `idx_user_created` | `type=ref, key=idx_user_created, Extra=Backward index scan` | P95 < 100ms（10 万流水） |
| 订单号查单 | `uk_order_no` | `type=const` | P95 < 20ms |
| 我的订单 | `idx_user` | `type=ref`，`filesort` 不出现 | P95 < 100ms |
| 后台按状态查单 | `idx_status` | `type=range, key=idx_status` | P95 < 150ms |
| 商品公开列表 | `idx_status_sort` | `type=range, key=idx_status_sort`，无 `filesort` | P95 < 100ms |
| 商品订单统计 | `idx_goods` | `type=ref, key=idx_goods` | 看板离线聚合 |
| 订单日志 | `idx_order` | `type=ref, key=idx_order` | 详情 < 20ms |

- 目标：下单 P95 < 150ms（行锁竞争下允许更高，架构 8.1）；账户/流水查询 P95 < 100ms；50 并发压测用 k6，报告入 07。
- 禁止在 `points_ledger` 上做 `LIKE`/函数包裹列过滤；后台时间范围查询用 `created_at >= ? AND < ?`。
- 对账全表聚合在 03:30 低峰执行，单实例 ShedLock；数据量大时按 `user_id` 分片（`MOD(user_id,16)`）分批。

## 15. 看板统计口径（供 M7，只读）

业务日 `Asia/Shanghai`，区间 `[D-1 16:00Z, D 16:00Z)`；M7 用自有 `AnalyticsMapper` 只读，不调用 M4 写接口（00 9.2 只读例外）。

| 指标 | 口径 |
|---|---|
| 发放积分 | `SUM(amount) FROM points_ledger WHERE amount>0 AND created_at∈区间`（含 `REFUND`，按发生日计） |
| 消耗积分 | `-SUM(amount) FROM points_ledger WHERE amount<0 AND created_at∈区间` |
| 净变化 | 发放 − 消耗 |
| 兑换消耗 | `-SUM(amount) WHERE type='REDEEM' AND created_at∈区间` |
| 订单数 | `COUNT(*) FROM points_orders WHERE created_at∈区间`，可按 `status` 分组（下单日口径） |
| 商品销量 | `SUM(quantity) FROM points_orders WHERE status IN ('APPROVED','SHIPPED','COMPLETED') AND created_at∈区间`，按 `goods_id` 分组 |
| 履约中 | `COUNT(*) WHERE status IN ('PENDING','APPROVED','SHIPPED')`（存量，不带日期） |

> `daily_stats.points_issued/points_consumed` 由 M7 每日任务按上述口径回填；M4 不写 `daily_stats`。

## 16. 待确认（均给默认方案）

| 事项 | 默认方案 | 状态 |
|---|---|---|
| 称号系统优先级 | **P1**（保留 00 冻结的 5 条称号路由：`GET /titles` + `/admin/titles*`），与 05 最终口径一致 | 已解决 |
| `SHIPPED` 7 天自动完成 | **不做**，保留管理员手动 `COMPLETED`（00 6.4 标注“可选”） | 仍待产品确认 |
| 用户“确认收货”入口 | 复用 `transition` 仅 ADMIN，用户侧不提供（无对应路由） | 仍待契约确认 |
| `TITLE` 订单是否跳过 `SHIPPED` | 不跳过，按 6.2 走 `APPROVED→SHIPPED→COMPLETED`，`SHIPPED` 不校验物流 | 已解决 |
| 自己给自己点赞是否加分 | 不加分，`actorId==authorId` 时忽略事件 | 仍待产品确认 |
| 反转时余额不足 | 已解决：`ARTICLE_PUBLISH_REVERSAL` 金额 = `-min(收款人当前余额, 该文 ARTICLE_PUBLISH 正流水合计)`，`balance_after` 归 0，差额写 `remark`（「余额不足，少扣 X」）并告警；不产生负余额 | 已解决 |
| 反转收款人 | 已解决（D9）：接管后 `author_id` 变化，但反转按原流水收款人（按 `biz`/正向 key 回查），不按当前 `articles.author_id` | 已解决 |
| 站主积分收益 | P1（D11）：站主文章不计发布/被赞积分，当前实现仍会发放 | P1 |
| 无 `Idempotency-Key` 的重复下单 | 直接 400；必须带键，24 小时内同键同体返回同一订单 | 已解决 |
| 读取账户时行缺失 | 返回零值 VO；首个事件/下单时 `ensureAccount` 懒创建 | 已解决 |
| 对账差异修复 | M4 `PointsReconcileJob` 自动追加 `ADMIN_ADJUST` 修复流水（`reconcile:{userId}:{date}`）并写审计 | 已解决 |
| 订单号格式 | `PO` + `yyyyMMddHHmmss`（Asia/Shanghai）+ 8 位大写字母数字，共 24 位 | 已解决 |
| “我的称号”出口 | 不新增路由；`GET /points/account` 返回 `titles` 与 `activeTitle` | 已解决 |
| 商品图引用 | M4 → M5 `claimByKey(objectKey, ownerId, actor, GOODS)` / `releaseByKey`（owner=actor=当前管理员） | 已解决 |

### 16.1 契约问题（只报告，本文不改契约）

1. `05-功能范围` 曾把称号系统列为 P2，与 00 冻结的 5 条称号路由不一致 → **已解决**：称号定为 P1，05 由契约 Agent 同步。
2. 03 曾写对账“只告警不自动改账”，与差异修复要求不一致 → **已解决**：修复主体固定为 M4 `PointsReconcileJob`，追加 `ADMIN_ADJUST` 流水并审计。
3. 00 §9.2 曾未列 M4 → M5 → **已解决**：1.2 已允许 M4 调用 `claimByKey/releaseByKey`。
4. `Idempotency-Key` 与 `points_orders` 列缺失 → **已解决**：00 §5.1 明确必填；DDL 已有 `idempotency_key` 与 `UNIQUE(user_id,idempotency_key)`。
5. 错误码前缀无 `TITLE_` → **已解决**：00 §5.3 已列入，称号 CRUD 使用 `TITLE_NOT_FOUND`/`TITLE_NAME_CONFLICT`。
6. 03 §9.2 管理员引导直接插入 `points_accounts` → **已解决**：改为 M4 `ensureAccount` 懒创建，引导只建用户。
7. `order_no` 生成规则未定义 → **已解决**：按 00 §7 常量（Asia/Shanghai + 8 位大写字母数字）。
8. 无“我的称号”读取路由 → **已解决**：并入 `GET /points/account`，不新增路由。
9. 第 12 节错误码已逐条登记到 04 §7.6 → **已解决**。
10. 00 §6.1 的 `AuditAction` 已覆盖商品新增/更新/删除、称号 CRUD 与两类对账（`GOODS_CREATE/GOODS_UPDATE/GOODS_DELETE`、`TITLE_CREATE/TITLE_UPDATE/TITLE_DELETE`、`POINTS_RECONCILE`/`INTERACTION_RECONCILE`） → **已解决**：按 00 §6.1 取值写审计，不新增路由/枚举。