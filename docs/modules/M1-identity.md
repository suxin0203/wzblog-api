# M1 · 身份认证（模块设计文档）

> 负责人 Agent：M1 Identity Agent
> 依赖模块：M0（基础能力、`shared.profile.TitleLookup` 端口）、M5（头像文件引用）
> 对应阶段：阶段 1（认证，4~6 天）
> 状态：已定稿（契约缺口已在 99 报告中关闭）
> 契约版本：以 [00-文档索引与约定](../00-文档索引与约定.md) 1.3 为准

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.modules.identity` |
| 分层 | `api`（AuthController、UserController、AdminUserController）/ `app`（AuthAppService、UserAppService、AdminUserAppService、SessionAppService）/ `domain`（User、RefreshToken、VerificationCode、LoginAudit、枚举、UserLoggedInEvent）/ `infra`（Mapper、Redis） |
| 表 | `users`、`refresh_tokens`、`verification_codes`、`login_audits` |
| 对外事件 | 发布：`UserLoggedInEvent`；订阅：无 |
| 依赖的 app 服务 | M0：`Result/ErrorCode`、Security 过滤器、`RequestContext`、`RateLimiter`、`MailSender`、Redis、`shared.profile.TitleLookup`（端口，M4 实现）；M5：`FileAppService#claimByKey(objectKey, ownerId, bizType)` / `#releaseByKey(objectKey)`（头像，入参对象 key，非 fileId） |
| 路由数 | 21（与 00 第 4 节逐字一致，不得增删） |
| 阶段 | 1 |

## 2. 范围与非目标

- 做：图形验证码、注册、登录、双 Token 刷新、登出/全端登出、找回密码三步、当前用户资料、改密、设备会话、公开用户资料、管理员用户 CRUD、登录审计。
- 不做：积分账户与发放（M4）、微信小程序/扫码登录的**路由与协议**（M8；M1 仅提供 `WechatIdentityService` 身份创建/绑定/匿名化端口）、头像上传与对象存储（M5）、用户文章/订单统计（M2/M4）、管理操作审计 `audit_logs`（M7）、物理删除用户。
- 边界：注册与登录的每日首登只发布事件，不写 `points_ledger`；头像只保存对象 key，不保存 URL。

## 3. 路由清单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/auth/captcha` | PUBLIC | 生成 4 位图形验证码，Redis 存哈希，5 分钟 |
| POST | `/auth/register` | PUBLIC | 注册；图形验证码必填，邮箱选填 |
| POST | `/auth/login` | PUBLIC | 登录；图形验证码 + 失败锁定；返回双 Token |
| POST | `/auth/refresh` | PUBLIC | 轮换 Refresh Token 并换发 Access Token |
| POST | `/auth/logout` | USER | 吊销当前 family，当前 access 的 jti 入黑名单 |
| POST | `/auth/logout-all` | USER | 吊销该用户全部 family |
| POST | `/auth/password/forgot/request` | PUBLIC | 发送邮箱验证码；无论邮箱是否存在均返回成功 |
| POST | `/auth/password/forgot/verify` | PUBLIC | 校验验证码，换取一次性 resetToken |
| POST | `/auth/password/forgot/reset` | PUBLIC | 用 resetToken 重置密码并吊销全部 family |
| GET | `/users/me` | USER | 当前用户资料（含 email/status）；`activeTitle` 经 M0 `shared.profile.TitleLookup` 返回 `{id,name,cssClass}` |
| PUT | `/users/me` | USER | 更新 nickname/bio/avatarKey，角色与状态不可改 |
| PUT | `/users/me/password` | USER | 校验旧密码后改密，吊销全部 family |
| GET | `/users/me/sessions` | USER | 设备会话列表，`sessionId = family_id` |
| DELETE | `/users/me/sessions/{sessionId}` | USER | 下线指定 family，仅限本人 |
| GET | `/users/{id}/profile` | PUBLIC | 公开资料，字段白名单裁剪 |
| GET | `/admin/users` | ADMIN | 分页查询用户（默认排除已注销） |
| POST | `/admin/users` | ADMIN | 创建用户，返回 201 |
| GET | `/admin/users/{id}` | ADMIN | 用户详情 |
| PUT | `/admin/users/{id}` | ADMIN | 更新资料/角色/状态，可选重置密码 |
| DELETE | `/admin/users/{id}` | ADMIN | 注销：软删 + 匿名化 + 吊销全部 family |
| GET | `/admin/users/{id}/login-audits` | ADMIN | 登录审计分页，按时间倒序 |

## 4. DTO 契约

### 4.1 请求 DTO

| DTO | 字段（类型，必填） | 校验与说明 |
|---|---|---|
| RegisterRequest | username:string 必填；password:string 必填；email:string 选填；nickname:string 选填；captchaId:string 必填；captchaCode:string 必填 | 用户名 `^[A-Za-z0-9_]{4,20}$` 且不得以 `deleted_` 开头；密码 8~64 且同时含字母与数字（可见 ASCII）；邮箱 `@Email` ≤150、入库小写且域名不得为 `deleted.invalid`；昵称 1~30，缺省=username；验证码 4 位，忽略大小写 |
| LoginRequest | username:string 必填；password:string 必填；captchaId:string 必填；captchaCode:string 必填 | 用户名 trim 后非空 ≤20；密码 ≤64；验证码 4 位 |
| RefreshRequest | refreshToken:string 必填 | 20~128 字符；不合法按 `AUTH_REFRESH_INVALID` 处理 |
| LogoutRequest | refreshToken:string 必填 | 20~128 字符；找不到或已吊销时幂等返回成功 |
| ForgotRequest | email:string 必填 | 合法邮箱 ≤150，入库小写 |
| ForgotVerifyRequest | email:string 必填；code:string 必填 | 6 位数字 `^\d{6}$` |
| ForgotResetRequest | resetToken:string 必填；newPassword:string 必填 | token 43 位 Base64URL；密码规则同注册 |
| UpdateProfileRequest | nickname:string 必填；bio:string 选填；avatarKey:string 选填 | 昵称 1~30；bio ≤255；`avatarKey` 为 M5 对象 key（≤255），传 null 表示清除头像，服务端调 `FileAppService#claimByKey(avatarKey, userId, AVATAR)` / `#releaseByKey(旧 avatarKey)`；username/email/role/status 不在白名单 |
| ChangePasswordRequest | oldPassword:string 必填；newPassword:string 必填 | 新密码 8~64、可见 ASCII 且字母+数字，且不得与旧密码相同 |
| AdminCreateUserRequest | username、password、nickname 必填；email、role、status 选填 | role 缺省 `USER`，status 缺省 `ACTIVE`；username/密码/昵称/邮箱规则同上 |
| AdminUpdateUserRequest | nickname、role、status 必填；email、newPassword 选填 | 不含 username；email 规则同注册；newPassword 存在时重置密码并吊销全部 family；不能改自己 role/status |
| AdminUserQuery | keyword、role、status、page、pageSize 均可选 | keyword ≤50；role/status 枚举；page≥1 默认 1；pageSize 默认 20、最大 100 |
| LoginAuditQuery | success、from、to、page、pageSize 均可选 | from/to 为带时区 ISO-8601；page/pageSize 同上 |

> 昵称校验口径：API 统一 **1~30 字符**（按 Unicode 码点）；`users.nickname` 的 `VARCHAR(50)` 只是 DB 余量（03 第 3.1.1 节），不代表 API 上限为 50，也不得在任何文档中声称列宽为 30。
> 验证码、锁定与审计口径与 00 第 7 节一致：图形验证码 5 分钟、一次性、错 3 次作废；登录失败 5 次 / 15 分钟（账号维度）；每次登录尝试写 `login_audits`。

### 4.2 响应 VO

| VO | 字段 | 说明 |
|---|---|---|
| CaptchaVO | captchaId:string、imageBase64:string、expiresIn:int | `imageBase64` 含 `data:image/png;base64,` 前缀；expiresIn=300 |
| TokenPairVO | tokenType:"Bearer"、accessToken、expiresIn:900、refreshToken、refreshExpiresIn:1209600 | 刷新宽限期也返回同一结构 |
| UserSelfVO | id、username、nickname、email、emailVerified、avatarUrl、bio、role、status、activeTitle、lastLoginAt、createdAt | BIGINT 为字符串；仅本人接口返回 email/status |
| PublicUserProfileVO | id、username、nickname、avatarUrl、bio、role、activeTitle、createdAt | 白名单，禁止 email/openid/status/lastLogin |
| TitleBriefVO | id、name、cssClass | 来自 M0 `shared.profile.TitleLookup`（M4 提供实现）；无称号时为 null，字段不含授予关系 |
| SessionVO | sessionId、ip、userAgent、createdAt、issuedAt、expiresAt | sessionId 为 `family_id`（UUID 字符串）；`issuedAt` 为当前 refresh token 最近一次轮换时间（`refresh_tokens.issued_at`） |
| AdminUserVO | id、username、nickname、email、avatarUrl、role、status、lastLoginAt、lastLoginIp、createdAt | 仅 ADMIN 可见 |
| LoginAuditVO | id、userId、usernameAttempt、success、failureReason、ip、userAgent、createdAt | userId 可为 null；不返回密码相关字段 |
| ResetTokenVO | resetToken:string、expiresIn:int | expiresIn=300 |
| PageVO\<T\> | list、total、page、pageSize、hasNext | list 永不为 null |

### 4.3 JSON 示例

> 除第一个完整响应壳外，以下均为 `data` 部分；BIGINT 一律为字符串。

```json
{"code":"OK","message":"success","data":{"tokenType":"Bearer","accessToken":"eyJhbGciOiJIUzI1NiJ9...","expiresIn":900,"refreshToken":"q3J...43chars","refreshExpiresIn":1209600},"traceId":"0199a1b2c3"}
```

```json
{"id":"1001","username":"wenzhan","nickname":"文栈","email":"owner@wzblog.com","emailVerified":false,"avatarUrl":"https://cdn.wzblog.com/avatar/2026/10/a.png","bio":"写代码，也写字。","role":"USER","status":"ACTIVE","activeTitle":{"id":"7","name":"初来乍到","cssClass":"title-rookie"},"lastLoginAt":"2026-10-24T04:00:00.000Z","createdAt":"2026-09-01T02:00:00.000Z"}
```

```json
{"id":"1001","username":"wenzhan","nickname":"文栈","avatarUrl":"https://cdn.wzblog.com/avatar/2026/10/a.png","bio":"写代码，也写字。","role":"USER","activeTitle":null,"createdAt":"2026-09-01T02:00:00.000Z"}
```

```json
{"sessionId":"3f2b8c1e-9a4d-4e7b-8c21-5d6e7f809a1b","ip":"203.0.113.8","userAgent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64)","createdAt":"2026-10-20T01:00:00.000Z","issuedAt":"2026-10-24T03:00:00.000Z","expiresAt":"2026-11-07T03:00:00.000Z"}
```

## 5. 数据表

| 表 | 用途 | 关键字段/索引（字段定义见 03 第 3.1 节） |
|---|---|---|
| `users` | 账号、角色、状态、资料、注销 | `uk_username`、`uk_email`、`uk_openid`、`idx_status_created`、`idx_deleted` |
| `refresh_tokens` | 刷新令牌、family、设备会话 | `uk_token_hash`、`idx_user_active(user_id,revoked_at,expires_at)`、`idx_family`、`idx_expires` |
| `verification_codes` | 邮箱重置验证码 | `idx_scene_target(scene,target,created_at)`、`idx_expires` |
| `login_audits` | 登录成功/失败审计 | `idx_user_created`、`idx_ip_created`、`idx_created` |

- `users` **没有** `points`、`is_root`、`refresh_token`：积分在 M4 `points_accounts`，权限只看 `role`，刷新令牌独立成表。
- 注销不物理删除：`username=deleted_{id}`、`email=deleted_{id}@deleted.invalid`、`openid=deleted_{id}`，同时 `unionid=NULL`、`nickname='已注销用户'`、`avatar_key=NULL`、`bio=NULL`、`password_hash=随机BCrypt`、`status='DISABLED'`、`deleted_at=NOW(3)`，释放三个唯一键。
- `avatarUrl` 由配置的公共读域名 + `avatar_key` 拼接；M1 只存 key，不签发临时 URL。头像引用的占用/释放统一调 M5 `FileAppService#claimByKey(objectKey, ownerId, AVATAR)` / `#releaseByKey(objectKey)`，不再使用 `claim(fileId)`。
- 昵称：`users.nickname` 为 `VARCHAR(50)`（DB 余量），API 校验 1~30 字符；两者不可混写。
- 注册保留 `deleted_` 用户名前缀与 `deleted.invalid` 邮箱域，保证注销匿名化不会与真实账号唯一键冲突。

## 6. 状态与业务规则

### 6.1 账号状态

- `ACTIVE`：可登录；`DISABLED`：密码正确也拒绝登录（403），已签发 access 在下次回库校验时拒绝（403），refresh 拒绝并吊销 family。
- `deleted_at IS NOT NULL`：视同不存在；登录返回 `AUTH_BAD_CREDENTIALS`，持旧 access 返回 401，公开资料 404。
- 角色以数据库为准；JWT 中的 `role` 仅作提示，每次请求由 M1 `UserDetailsService` 回库覆盖。
- 登录审计失败原因（`login_audits.failure_reason`）：

| 值 | 场景 | 是否计入 5 次锁定 |
|---|---|---|
| `CAPTCHA_INVALID` | 图形验证码错误/过期/已消费 | 否 |
| `BAD_CREDENTIALS` | 用户不存在或密码错误 | 是 |
| `DISABLED` | 密码正确但账号停用 | 否 |
| `LOCKED` | 处于锁定窗口 | 否 |
| `RATE_LIMITED` | IP 级限流 | 否 |
| `INTERNAL_ERROR` | 登录流程异常 | 否 |

### 6.2 验证码与 Redis 键

| 用途 | Key | 值 | TTL |
|---|---|---|---|
| 图形验证码 | `identity:captcha:{captchaId}` | Hash：`hash`、`fails` | 300s |
| 登录失败计数 | `identity:login:fail:{sha256(username小写)}` | 整数 | 900s |
| 登录锁定 | `identity:login:lock:{sha256(username小写)}` | "1" | 900s |
| 当日首登 | `identity:login:day:{userId}:{yyyyMMdd}` | "1" | 至 Asia/Shanghai 当日 24:00 + 60s |
| access 黑名单 | `identity:token:blacklist:{jti}` | "1" | access 剩余有效期 |
| 重置令牌 | `auth:reset:{sha256(token)}` | userId | 300s（5 分钟，一次性，`GETDEL` 消费） |
| 刷新宽限 | `identity:refresh:grace:{sha256(oldToken)}` | JSON：tokens + uaHash + familyId | 10s |

- 图形验证码：4 位，排除易混字符 `0O1Il`，忽略大小写；成功即删；失败 `fails+1`，第 3 次失败删除。校验用 Lua 保证“比对 + 删除/计数”原子。
- 邮箱验证码：6 位数字，`SHA-256(target + ":" + code + ":" + pepper)` 入库，pepper 来自环境变量；5 分钟、一次性、错 3 次作废。
- 发送限流：同邮箱 5 次/小时、同 IP 10 次/小时；登录 IP 30 次/分钟；注册 IP 10 次/小时。

### 6.3 登录与锁定

- 顺序：IP 限流 → 校验图形验证码 → 检查锁定 → 查用户 → BCrypt 校验 → 检查状态 → 签发 Token → 记审计/更新 last_login。
- 用户不存在时仍对一个固定 dummy BCrypt 哈希执行 `matches`，避免时间侧信道；BCrypt 强度 10，`matches` 兼容 `$2a/$2b/$2y`，旧 `$2b$` 哈希无需改写。
- 密码错误：失败计数 `INCR`（首次设置 900s TTL）；达到 5 次写锁定键 900s；成功登录删除失败键。
- 锁定期间即使密码正确也返回 429 + `Retry-After`（剩余秒数）；不做管理员手动解锁。
- 每次登录尝试都写 `login_audits`；失败审计在独立事务 `REQUIRES_NEW` 写入，避免被主流程回滚。

### 6.4 找回密码

- `/request` 只接受邮箱；无论是否存在均返回 `{sent:true,expiresIn:300}`，防账号枚举；存在时生成验证码并作废旧码。
- `/verify` 取该邮箱最新未消费 RESET 码；过期/不存在 → `AUTH_VERIFICATION_CODE_EXPIRED`；不匹配则原子累加 `attempt_count`，第 3 次置 `consumed_at` 并返回 `AUTH_VERIFICATION_CODE_LOCKED`。
- 校验成功：条件更新 `consumed_at`（影响 1 行），生成 32 字节随机 resetToken，写入 Redis `auth:reset:{sha256(token)}` → userId，TTL 300s（5 分钟）、一次性（`GETDEL`），明文仅返回一次；**不新增表、DB 不落重置令牌**。
- `/reset` 用 `GETDEL` 原子消费 resetToken；失败即失效。重置后吊销该用户全部 family（`LOGOUT_ALL`），返回 200。
- 邮箱验证默认不强制：注册不要求邮箱验证码，`email_verified_at=NULL`；`verification_codes.scene=REGISTER` 仅为后续开关预留；未验证邮箱同样可作为找回渠道。

### 6.5 刷新轮换与 family

- Access Token：JWT HS256，15 分钟；claims 仅 `sub/role/jti/iss=wzblog/aud=wzblog-web/iat/exp`，`jti` 为 UUID，不放 email/nickname。
- Refresh Token：32 字节随机值 Base64URL，库中只存 SHA-256 hex，明文只在响应中出现一次；每次登录生成新 `family_id`，同一 family 链式轮换，每次新令牌 14 天（滑动）；`client_ip` 截断 45 字符，`user_agent` 截断 255 字符。
- 轮换：条件更新旧行 `revoked_at=NOW(3)`（随后回写 `replaced_by_id`）；只有影响 1 行才插入新行并写宽限缓存。
- 旧令牌再次出现且 `replaced_by_id IS NOT NULL`：10 秒内同 UA 且 family 仍有有效令牌 → 返回缓存中的同一 Token 对（幂等，不新增行）；否则判定复用，吊销整个 family（`REUSE_DETECTED`），返回 `AUTH_REFRESH_REUSED`。
- 宽限期缓存只由轮换成功者写入，TTL 10s；超过 10 秒的重试按复用处理。该策略是误伤与盗用之间的明确取舍。
- 已按 `LOGOUT/LOGOUT_ALL/ADMIN/REUSE_DETECTED` 吊销的令牌一律 `AUTH_REFRESH_INVALID`，不再二次吊销。
- 停用/注销用户刷新：拒绝并吊销 family。

### 6.6 登出

- `logout`：按 refreshToken 找到 family 且校验 `user_id=当前用户`；条件吊销该 family 全部有效行（`LOGOUT`）；当前 access 的 `jti` 写黑名单，TTL=剩余有效期；refreshToken 不存在、已吊销或属于他人时幂等返回 200，不泄露归属。
- `logout-all`：吊销该用户全部有效 family（`LOGOUT_ALL`），当前 jti 入黑名单；其他设备的 access 最迟 15 分钟后自然失效。
- 黑名单校验在 M0 Security 过滤器完成，命中返回 401 `AUTH_TOKEN_INVALID`（语义为登录态已失效）。

### 6.7 改密与重置

- 改密：校验旧密码（`BCrypt.matches`），新密码不得与旧密码相同；更新 `password_hash` 与 `version+1`，吊销全部 family；当前 jti 入黑名单，客户端必须重新登录。
- 重置：同改密，但不需要旧密码；resetToken 一次性。
- 已签发 access 不做全量 jti 追踪，改密/重置后最多残留 15 分钟；这是当前契约下的已知边界。

### 6.8 每日首次登录

- 登录成功后在 `Asia/Shanghai` 业务日上用 `SET NX` 抢占 `identity:login:day:{userId}:{yyyyMMdd}`；首次成功才发布 `UserLoggedInEvent`，载荷 `firstLoginOfDay=true`。
- 事件在事务提交后由 M4 监听发放 +5 积分；M1 绝不直接写 `points_ledger`。M4 以 `login:{userId}:{businessDate}` 幂等。

## 7. 关键时序

### 7.1 注册

1. 客户端取 `/auth/captcha`，提交用户名、密码、可选邮箱/昵称、captchaId/captchaCode。
2. Controller 校验 DTO；`AuthAppService#register` 先 Lua 消费图形验证码，失败即 400。
3. 规范化 username（trim）与 email（trim+小写）；BCrypt(10) 在事务外计算。
4. 事务内插入 `users`（role=USER、status=ACTIVE、nickname 缺省 username）；`uk_username`/`uk_email` 冲突映射 409。
5. 提交后返回 201 + `UserSelfVO`；不自动签发 Token，客户端随后调用登录。

### 7.2 登录

1. 校验图形验证码（消费）→ 查锁定键；锁定则写 `LOCKED` 审计并返回 429。
2. 按 `uk_username` 查用户；不存在则 dummy BCrypt 后写 `BAD_CREDENTIALS` 审计（user_id=NULL），返回 422。
3. BCrypt 不匹配：失败计数 +1，达到 5 写锁定键，写审计，返回 422；匹配但 `DISABLED` 写 `DISABLED` 审计并返回 403。
4. 事务内：更新 `last_login_at/last_login_ip`；插入新 family 的 refresh_tokens；写成功审计。
5. 生成 access JWT（15 分钟）与 refresh 明文；返回 `TokenPairVO`。
6. 若当日首登抢占成功，事务提交后发布 `UserLoggedInEvent`。

### 7.3 刷新（含并发失败）

1. 计算旧 token 哈希，按 `uk_token_hash` 查行；不存在/过期 → 401 `AUTH_REFRESH_INVALID`。
2. `SELECT id FROM users WHERE id=? AND deleted_at IS NULL FOR UPDATE`；停用则吊销 family 并 403。
3. 条件更新旧行 `revoked_at=NOW(3), revoke_reason='ROTATED'`。
4. 影响 1 行：插入新 refresh 行（同 family、parent_id=旧 id），回写 `replaced_by_id`；生成新 access；提交后写宽限缓存，返回新 Token 对。
5. 影响 0 行：读旧行。若 `replaced_by_id` 非空且 10 秒内同 UA → 轮询宽限缓存（最多 2s）并返回同一 Token 对；Redis 不可用才返回 409 `AUTH_REFRESH_CONFLICT`（可重试，不吊销 family）。
6. 不同 UA，或超过宽限期 → 吊销整个 family，返回 401 `AUTH_REFRESH_REUSED`。
7. 并发 20 个同 UA 请求：只有 1 次真实轮换（1 条新 `refresh_tokens`），其余返回同一 Token 对，不触发 family 吊销。

### 7.4 找回密码

1. `/request` 校验邮箱格式与限流；存在用户则作废旧 RESET 码、插入新码并发送邮件；始终返回 200。
2. `/verify` 取最新码；比对哈希；成功后条件置 `consumed_at`，生成 resetToken 写 Redis，返回 `ResetTokenVO`。
3. `/reset` `GETDEL` 取 userId；失效 → 410 `AUTH_RESET_TOKEN_EXPIRED`；有效则事务内锁定用户行、更新密码、吊销全部 family。
4. 提交后返回 200；客户端用新密码重新登录。

### 7.5 管理员注销用户

1. ADMIN 调用 `DELETE /admin/users/{id}`；校验非本人、目标存在且未注销。
2. 获取 `identity:admin:guard` 短锁（SET NX PX 5000）；目标为 ADMIN 时统计其他可用 ADMIN，最后一个 → 422 `USER_LAST_ADMIN`。
3. 事务内锁定用户行，匿名化 username/email/openid/unionid/昵称/头像/简介，写随机密码哈希，置 `DISABLED + deleted_at`。
4. 同事务吊销该用户全部有效 family（`ADMIN`）。
5. 提交后释放守卫锁；返回 200。文章/订单/审计保留，作者展示由 M2/M4 按 `deleted_at` 处理。

## 8. 并发与事务

| 场景 | 事务边界 | 并发控制 | 失败处理 |
|---|---|---|---|
| 注册唯一冲突 | app `@Transactional` | 唯一索引兜底，先查后插仅优化提示 | 捕获 `DuplicateKeyException` 映射 409 |
| 刷新轮换 | app 单事务：条件更新 + 插新行 + 回写 replaced_by | 用户行 `FOR UPDATE` 串行化 + 条件更新 | 0 行进宽限/复用分支 |
| 同账号并发刷新 | 同上 | 先锁 `users` 行，再操作 `refresh_tokens`；锁顺序固定 | 仅 1 次真实轮换，其余在 10 秒宽限内返回同一新 Token，不吊销 family |
| 宽限期 | 提交后写 Redis | `SET key value EX 10` | 同 UA 并发者在窗口内短轮询取同一 Token；仅 Redis 故障才 409，不误杀 |
| 登出 vs 刷新 | 两个 app 事务 | 同一用户行锁；登出按 family 条件更新 | 刷新先完成则登出新令牌也被吊销 |
| 验证码一次性消费 | Redis Lua / DB 条件更新 | 比对、删除、计数原子化 | 影响 0 行即失败 |
| 改密/重置 vs 刷新 | app 事务 | 先锁 `users` 行，再 `UPDATE ... WHERE revoked_at IS NULL` | 新 token 不会在吊销后插入 |
| 最后管理员保护 | app 事务 + Redis 短锁 | `identity:admin:guard` + 事务内重查可用 ADMIN | 冲突重试一次，仍冲突返回 429 `COMMON_RATE_LIMITED` |
| 失败审计 | `REQUIRES_NEW` | 无 | 审计失败只记日志，不改变登录响应 |

```sql
-- 轮换：影响行数必须为 1
UPDATE refresh_tokens SET revoked_at = NOW(3), revoke_reason = 'ROTATED'
WHERE id = :oldId AND revoked_at IS NULL;

-- 全端吊销：改密、重置、登出全部、管理员注销
UPDATE refresh_tokens SET revoked_at = NOW(3), revoke_reason = :reason
WHERE user_id = :userId AND revoked_at IS NULL;

-- 邮箱验证码消费：影响 1 行才算成功
UPDATE verification_codes SET consumed_at = NOW(3)
WHERE id = :id AND consumed_at IS NULL AND expires_at > NOW(3);
```

- 事务只标在 `app` 公开用例方法；`api` 不开事务、不注入 Mapper。
- 黑名单/宽限缓存写失败不阻塞主流程：黑名单失败记指标，access 最迟 15 分钟过期；宽限缓存失败只让并发者收到 409，不吊销 family。
- 图形验证码与登录锁定依赖 Redis；Redis 不可用时登录/注册失败关闭（500），不做本地内存降级，避免多实例绕过锁定。

## 9. 权限与归属

| 操作 | 角色 | 归属校验 | 说明 |
|---|---|---|---|
| `/users/me` 读写、改密、会话 | USER | 只使用 `Authentication` 的 userId，不接受路径/请求体 userId | 防 IDOR |
| 删除会话 | USER | `WHERE family_id=? AND user_id=:currentUser` | 他人会话按 404 处理，不泄露存在性 |
| 公开资料 | PUBLIC | 仅按 id 查询未注销用户 | 字段白名单见下 |
| 管理员用户 CRUD/审计 | ADMIN | 路由级 `@PreAuthorize` | 非 ADMIN 403 |
| 管理员自我保护 | ADMIN | 不能改自己 role/status、不能注销自己 | 409/422 |
| 最后管理员 | ADMIN | 事务内统计其他可用 ADMIN | 禁止降级/停用/注销最后一个 |

- 公开资料只返回：`id`、`username`、`nickname`、`avatarUrl`、`bio`、`role`、`activeTitle`、`createdAt`；**绝不返回** `email`、`openid`、`unionid`、`status`、`lastLoginAt`、`lastLoginIp`。
- `PUT /users/me` 的 DTO 不含 role/status/username/email，杜绝 Mass Assignment。
- 管理端详情可返回 email/status/lastLogin，但不得返回 `password_hash`、openid、unionid。

## 10. 错误码

> 通用错误沿用 `COMMON_VALIDATION_ERROR`(400)、`COMMON_NOT_FOUND`(404)、`COMMON_RATE_LIMITED`(429)、`COMMON_INTERNAL_ERROR`(500)。下表为 M1 专用码，命名符合 00 第 5.3 节；已登记到 04 §7.3。

| code | HTTP | 触发条件 | 前端提示 |
|---|---|---|---|
| `AUTH_CAPTCHA_INVALID` | 400 | 图形验证码错误/已消费 | 验证码错误或已失效 |
| `AUTH_CAPTCHA_EXPIRED` | 400 | captchaId 不存在或超 5 分钟 | 验证码已过期，请刷新 |
| `AUTH_BAD_CREDENTIALS` | 422 | 用户不存在或密码错误 | 用户名或密码错误 |
| `AUTH_ACCOUNT_LOCKED` | 429 | 连续 5 次密码错误，锁定 15 分钟 | 登录失败次数过多，请稍后再试 |
| `AUTH_ACCOUNT_DISABLED` | 403 | 密码正确但 status=DISABLED | 账号已停用，请联系管理员 |
| `AUTH_TOKEN_INVALID` | 401 | 签名错/黑名单/用户已注销 | 登录状态无效，请重新登录 |
| `AUTH_UNAUTHORIZED` | 401 | 缺少 Authorization 头（由 M0 Security 输出） | 请先登录 |
| `AUTH_TOKEN_EXPIRED` | 401 | access 过期 | 登录状态已过期 |
| `AUTH_REFRESH_INVALID` | 401 | refresh 未知/过期/已登出吊销 | 登录已失效，请重新登录 |
| `AUTH_REFRESH_REUSED` | 401 | 旧 refresh 复用，family 已吊销 | 登录凭证已失效，请重新登录 |
| `AUTH_REFRESH_CONFLICT` | 409 | 同 UA 并发刷新且宽限缓存不可用（Redis 故障） | 刷新冲突，请稍后重试 |
| `AUTH_VERIFICATION_CODE_INVALID` | 400 | 邮箱验证码不匹配 | 验证码错误 |
| `AUTH_VERIFICATION_CODE_EXPIRED` | 400 | 验证码不存在/过期/已消费 | 验证码已过期，请重新获取 |
| `AUTH_VERIFICATION_CODE_LOCKED` | 429 | 同一验证码错 3 次 | 验证码错误次数过多，请重新获取 |
| `AUTH_RESET_TOKEN_EXPIRED` | 410 | resetToken 不存在/已用/超 5 分钟 | 重置凭证已失效，请重新获取验证码 |
| `AUTH_OLD_PASSWORD_MISMATCH` | 422 | 改密时旧密码错误 | 原密码错误 |
| `AUTH_PASSWORD_UNCHANGED` | 422 | 新密码与旧密码相同 | 新密码不能与原密码相同 |
| `AUTH_FORBIDDEN` | 403 | 角色或归属不符 | 无权访问 |
| `USER_USERNAME_TAKEN` | 409 | username 唯一键冲突 | 用户名已被占用 |
| `USER_EMAIL_TAKEN` | 409 | email 唯一键冲突 | 邮箱已被使用 |
| `USER_NOT_FOUND` | 404 | 用户不存在或已注销 | 用户不存在 |
| `USER_SESSION_NOT_FOUND` | 404 | 会话不属于本人/已失效 | 会话不存在或已失效 |
| `USER_CANNOT_DELETE_SELF` | 422 | 管理员注销当前登录账号 | 不能注销当前登录账号 |
| `USER_LAST_ADMIN` | 422 | 降级/停用/注销最后一个可用 ADMIN | 系统至少保留一个可用的管理员 |

## 11. 验收用例

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M1-001 | 无 | GET `/auth/captcha` | 200，返回 captchaId/PNG base64；Redis TTL≤300s |
| TC-M1-002 | 有效验证码 | 注册合法用户 | 201，users 新增一行，role=USER/status=ACTIVE，返回无 email 泄露给他人 |
| TC-M1-003 | 已存在 username | 再次注册同 username | 409 `USER_USERNAME_TAKEN` |
| TC-M1-004 | 已存在 email | 注册同 email | 409 `USER_EMAIL_TAKEN` |
| TC-M1-005 | 有效验证码 | 注册密码 `abc123`、用户名 `ab` | 400 `COMMON_VALIDATION_ERROR`，errors 含字段 |
| TC-M1-006 | 已注册 ACTIVE 用户 | 正确登录 | 200，双 Token；login_audits success=1；last_login_at/ip 更新 |
| TC-M1-007 | 同上 | 连续 5 次错密码后第 6 次用正确密码 | 第 5 次起 429 `AUTH_ACCOUNT_LOCKED`，Retry-After≈900；审计含 BAD_CREDENTIALS/LOCKED |
| TC-M1-008 | 取一次验证码 | 连续 3 次提交错误 captchaCode | 前 2 次 400，第 3 次后验证码删除；再提交返回 `AUTH_CAPTCHA_EXPIRED` |
| TC-M1-009 | DISABLED 用户 | 正确密码登录 | 403 `AUTH_ACCOUNT_DISABLED`，审计 failure_reason=DISABLED |
| TC-M1-010 | 已登录 | POST `/auth/refresh` | 200 新 Token 对；旧行 revoked_at 非空、replaced_by_id 指向新行；family 不变 |
| TC-M1-011 | 同一 refresh | 20 并发刷新（同 UA） | 仅 **1 条**新 refresh_tokens（1 次真实轮换）；其余 19 个在 10 秒宽限内返回**同一新 Token 对**，无 409、family 未被吊销 |
| TC-M1-012 | 旧 refresh 已被轮换 | 10 秒后用不同 UA 重放 | 401 `AUTH_REFRESH_REUSED`；family 内全部 revoked_at 非空 |
| TC-M1-013 | 已登录 | POST `/auth/logout` 后使用旧 access | 200；refresh 已吊销；旧 access 立即 401（jti 黑名单） |
| TC-M1-014 | 两个设备登录 | POST `/auth/logout-all` | 两个 family 全部吊销；两设备 refresh 均 401 |
| TC-M1-015 | A、B 两用户各有会话 | A 查会话并删除 B 的 sessionId | 列表只有 A；删除 B 返回 404 `USER_SESSION_NOT_FOUND` |
| TC-M1-016 | 两个 family | 删除其中一个 sessionId | 该 family 全部 revoked_at 非空，另一 family 不受影响 |
| TC-M1-017 | 任意 | 对不存在邮箱调用 forgot/request | 200 `{sent:true}`，不暴露邮箱是否存在 |
| TC-M1-018 | 已发码 | 连续 3 次输错 code | 前 2 次 400；第 3 次 429 `AUTH_VERIFICATION_CODE_LOCKED`，码已消费 |
| TC-M1-019 | 有效 resetToken | 重置成功后再次使用同一 token | 第一次 200 且全 family 吊销；第二次 410 `AUTH_RESET_TOKEN_EXPIRED` |
| TC-M1-020 | 已登录 | 旧密码错误改密；再用正确旧密码改密 | 第一次 422 `AUTH_OLD_PASSWORD_MISMATCH`；第二次 200，全部 family 吊销，当前 access 401 |
| TC-M1-021 | 已登录 | PUT `/users/me` 携带 role=ADMIN/status=DISABLED | 200 但字段被忽略；回查 role/status 不变 |
| TC-M1-022 | 任意 | GET `/users/{id}/profile` | 仅 8 个白名单字段；断言无 email/openid/status/lastLogin |
| TC-M1-023 | USER 与 ADMIN | USER 调 `/admin/users`；ADMIN 创建/更新用户 | USER 403；ADMIN 创建 201、更新 200，DB 一致 |
| TC-M1-024 | ACTIVE 用户已登录 | ADMIN 将其停用，再用旧 access/refresh | access 403 `AUTH_ACCOUNT_DISABLED`；refresh 403 且 family 吊销 |
| TC-M1-025 | ADMIN 与普通用户 | 注销普通用户；注销自己；注销最后一个 ADMIN | 普通用户被匿名化且 deleted_at 非空、无法登录；后两者 422 |
| TC-M1-026 | 有多次登录记录 | ADMIN 查 login-audits | 200 分页按 createdAt 倒序；USER 访问 403；不含密码字段 |

## 12. 性能与索引

| 查询 | 索引 | 预期与说明 |
|---|---|---|
| 登录按用户名 | `uk_username` | `type=const`，单行；`utf8mb4_0900_ai_ci` 天然忽略大小写，不写 `LOWER()` 以保索引 |
| 刷新按令牌 | `uk_token_hash` | `type=const`，单行；随后按主键条件更新 |
| 会话列表 | `idx_user_active(user_id,revoked_at,expires_at)` | `type=ref`，仅扫该用户有效令牌；窗口函数取每 family 最新一行 |
| 全端吊销 | `idx_user_active` | `type=range`，按 user_id 前缀更新 |
| 登录审计 | `idx_user_created(user_id,created_at)` | `type=ref`，倒序扫描，无 filesort；保留 180 天 |
| 同 IP 审计 | `idx_ip_created` | `type=ref`，用于风控排查 |
| 管理端用户列表 | `idx_status_created` + 主键 | 有 status 时 `type=ref`；keyword 用 `username LIKE 'kw%' OR nickname LIKE 'kw%'`，数据量 <10 万时配合 LIMIT 可接受；不清扫全表大字段 |
| access 校验 | `PRIMARY` + Redis | 主键单行 + 黑名单 GET，单次 <1ms；停用必须立即生效，故不做用户缓存 |

- BCrypt 成本 10：单次校验约 50~100ms CPU，是登录的主要耗时；登录接口按 IP 30 次/分钟限流，避免打满线程池。
- 登录失败锁与验证码均在 Redis，不增加数据库写放大；`login_audits` 为唯一每次登录必写的表，按 180 天清理。

## 13. 事件与跨模块调用

| 事件/调用 | 方向 | 载荷/签名 | 消费方 | 幂等 |
|---|---|---|---|---|
| `UserLoggedInEvent` | M1 → M4 | `Long userId, Instant loginAt, String ip, boolean firstLoginOfDay` | M4 发每日登录积分 | `login:{userId}:{businessDate}` |
| `AuthAppService#issueTokens` | M8 → M1 | `TokenPairVO issueTokens(Long userId)`：生成新 family、读 `RequestContext` 的 ip/ua、更新 last_login、写成功审计、首登发事件 | M8 小程序登录 | 每次调用生成新 family |
| `AuthAppService#revokeAll` | M8 → M1 | `void revokeAll(Long userId)`：吊销全部有效 family，reason=`LOGOUT_ALL` | M8 解绑/风控 | 影响 0 行不报错 |
| `WechatIdentityService#loginOrCreateByWechat` | M8 → M1 | `WechatLoginResult loginOrCreateByWechat(String openid, String unionid, String nickname, String avatarKey)` → `{userId, status, created}`；处理停用/注销/`uk_openid` 并发 | M8 小程序 code 登录 | `uk_openid` 冲突回查后按登录处理 |
| `WechatIdentityService#registerAndBindWechat` | M8 → M1 | `Long registerAndBindWechat(String username, String password, String nickname, String openid, String unionid)`；创建 `source=QR` 的正式账号、绑定 openid、同事务匿名化同 openid 的自动账号 | M8 扫码注册并确认 | `uk_username`/`uk_openid`/`uk_unionid` |
| `WechatIdentityService#bindWechat` | M8 → M1 | `BindResult bindWechat(Long userId, String openid, String unionid)`；仅当前用户 `openid IS NULL` 可绑；冲突/已绑他人/换绑返回对应错误 | M8 扫码绑定并确认 | 重复绑同一 openid 幂等 |
| `FileAppService#claimByKey/#releaseByKey` | M1 → M5 | `String claimByKey(String objectKey, Long ownerId, FileBizType bizType)`、`void releaseByKey(String objectKey)` | 头像占用/释放（入参对象 key） | 同一 key 幂等 |
| `shared.profile.TitleLookup#findById` / `#findByIds` | M1 → M0 端口（M4 实现） | `TitleBrief findById(Long)` / `List<TitleBrief> findByIds(Collection<Long>)` → `{id,name,cssClass}` | 本人/公开资料的 activeTitle | 只读，缺失返回 null |
| `UserDetailsService#loadUserByUsername` | M0 → M1（接口） | 以 userId 字符串回库，返回 authorities + status | 每请求鉴权 | 只读 |

- `issueTokens` 是 M8 唯一登录签发入口；M8 不得直接写 `refresh_tokens` 或 `users.last_login_*`。
- M1 不订阅任何事件；积分、通知全部由 M4/M3 消费事件完成。
- M1 不直接依赖 M4：当前称号只保存 `users.active_title_id`，展示时经 M0 `shared.profile.TitleLookup` 读取，`GET /users/me` 与公开 profile 口径一致。
- 头像引用直接调用 00 §9.2 允许的 `FileAppService#claimByKey/releaseByKey`，不引入额外端口；M5 未就绪时头像字段按 null 处理。

## 14. 待确认事项

| 事项 | 默认方案 | 状态 |
|---|---|---|
| 注册是否强制邮箱验证 | 不强制；`email_verified_at=NULL`，不发验证码；`REGISTER` 场景预留 | 仍待产品确认 |
| 注册后是否自动登录 | 否；返回 `UserSelfVO`，客户端显式调用 `/auth/login` | 仍待产品确认 |
| 登录图形验证码是否每次必填 | 是；每次尝试消费一个新验证码，失败后需刷新 | 仍待产品确认 |
| 登录失败 HTTP | 422 `AUTH_BAD_CREDENTIALS`（锁定 429、停用 403），避免前端 401 拦截器误跳登录页 | 已登记（04 §7.3） |
| resetToken 存储位置 | Redis：`auth:reset:{sha256(token)}` → userId，TTL 300s（5 分钟）、`GETDEL` 一次性；**不新增表**、DB 不落任何重置令牌 | 已解决 |
| 错误码登记 | 第 10 节清单已逐条登记到 04 §7.3，不新增前缀 | 已完成 |
| 头像 claim 接口 | 统一 `FileAppService#claimByKey(objectKey, ownerId, AVATAR)` / `#releaseByKey(objectKey)`；入参为对象 key，不再用 `claim(fileId)` | 已解决 |
| refresh family 绝对有效期 | 滑动 14 天，不设绝对上限；如需上限在 V3 增加 family 过期字段 | 仍待产品确认 |
| 旧 refresh 复用与宽限 | 20 并发只发生 1 次真实轮换，其余在 10 秒宽限内返回同一新 Token；窗口外或不同 UA 一律吊销 family | 已解决 |
| 登录事件发布时机 | 仅每日首次登录发布 `firstLoginOfDay=true`；其余登录不发事件 | 已解决 |
| 宽限期判定 | 仅比较 UA SHA-256，10 秒；不绑定 IP，避免移动网络切换误伤 | 已解决 |
| 公开资料 activeTitle | 仅存 `active_title_id`，经 M0 `shared.profile.TitleLookup`（M4 实现）返回 `{id,name,cssClass}`；缺失返回 null；M1 不直接依赖 M4 | 已解决 |
| 昵称长度 | API 1~30 字符；DB `VARCHAR(50)` 仅余量 | 已解决 |
| 无邮箱账号找回 | 不支持自助找回；ADMIN 可在 `PUT /admin/users/{id}` 传 `newPassword` 重置 | 仍待产品确认 |
| 注销有内容的用户 | 保留用户壳并匿名化，不阻止、不物理删除；文章/订单外键不悬空 | 已解决 |
| 会话列表“当前会话”标记 | 不返回；JWT 无 family/sid claim，按 `issued_at` 倒序展示 | 仍待前端确认 |
| 代理场景客户端 IP | 仅信任配置代理网段的 `X-Forwarded-For`，否则取 remoteAddr | 已解决 |