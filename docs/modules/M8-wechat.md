# M8 · 微信与扫码（模块设计文档）

> 负责人 Agent：M8（wechat）
> 依赖模块：M0（Redis / 配置 / 限流）、M1（`WechatIdentityService` 身份创建/绑定、`AuthAppService` 签发与吊销）、M5（头像，可选）
> 对应阶段：7（运营，可选）；优先级 **P2**
> 状态：已定稿（P2 可选模块；砍单时不写代码，但保留表/路由与错误码，入口返回未启用错误）
> 契约版本：[00-文档索引与约定](../00-文档索引与约定.md) 1.6
> 事实来源：00（路由/枚举/常量冻结）、03（表结构）、架构 v2 §7.11/§10、指南 §7/§10；第三方字段与限额一律以微信官方文档为准，未核实项标 `[待核实]`

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.modules.wechat` |
| 表 | 只写 `wechat_qr_sessions`；`users` **不直接读写**，由 M1 通过 `WechatIdentityService` 维护（M8 不注入 `WechatUserMapper`） |
| 对外事件 | 发布：无（`UserLoggedInEvent` 由 M1 在 `issueTokens` 内发布，M8 不得重复发布）；订阅：无 |
| 依赖的 app 服务 | M1 `WechatIdentityService#loginOrCreateByWechat(openid, unionid, nickname, avatarKey)` / `#registerAndBindWechat(username, password, nickname, openid, unionid)` / `#bindWechat(userId, openid, unionid)`；M1 `AuthAppService#issueTokens(userId)`、`#revokeAll(userId)`；M5 `FileAppService`（可选，仅头像） |
| 外部依赖 | Redis（热点状态、ticket、access_token 缓存）；微信 `jscode2session`、`getwxacodeunlimit`、`cgi-bin/token` |
| 配置 | `WECHAT_APP_ID`、`WECHAT_APP_SECRET`、`WECHAT_QR_ENV_VERSION`（见 08）；缺少 AppID/Secret 时模块自动关闭 |

## 2. 范围与非目标

**做**：小程序 `code` 登录与绑定；PC 扫码会话创建、状态轮询、小程序码图片；小程序确认 / 注册并确认 / 绑定并确认；一次性 `loginTicket` 换双 Token。

**不做**：公众号（服务号/订阅号）任何能力；微信支付；订阅消息/模板消息；多 openid 合并与账号合并（`users.unionid` 仅用于唯一性判定与冲突返回，不做账号合并）；小程序端 SSE；`userinfo` 单独接口（旧 `/wechat/userinfo/:openid` 不迁移，资料并入登录响应与 `/users/me`）；**不实现用户创建/绑定/匿名化的 SQL（统一交 M1 `WechatIdentityService`）**。

## 3. 路由清单（9 条，与 00 §4 逐字一致）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/auth/wechat/login` | PUBLIC | code 换 openid，登录或自动创建 USER，返回双 Token |
| POST | `/auth/wechat/bind` | USER | 已登录网页用户绑定小程序 openid |
| POST | `/auth/qr/sessions` | PUBLIC | 创建扫码会话，返回 `sceneId` 与过期时间 |
| GET | `/auth/qr/sessions/{sceneId}` | PUBLIC | 轮询状态；`CONFIRMED` 时带 `ticket` |
| GET | `/auth/qr/sessions/{sceneId}/code` | PUBLIC | 返回小程序码图片二进制（`image/png`） |
| POST | `/auth/qr/sessions/{sceneId}/confirm` | PUBLIC（需 bindToken 或已登录） | 小程序已登录用户确认；成功下发 ticket 与一次性 bindToken |
| POST | `/auth/qr/sessions/{sceneId}/register-and-confirm` | PUBLIC | 用提交的 username/password 创建正式账号并绑定 openid 后确认 |
| POST | `/auth/qr/sessions/{sceneId}/bind-and-confirm` | PUBLIC | 已登录网页用户用 bindToken 绑定微信后确认 |
| POST | `/auth/qr/tickets/exchange` | PUBLIC | 一次性 ticket 换双 Token（PC 端唯一取 Token 入口） |

> `/api/v1` 前缀由 MVC 统一添加，Controller 不写。管理员无 M8 路由。

## 4. DTO 契约

### 4.1 请求

| DTO / 路由 | 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|---|
| `WechatLoginReq` / login | code | string | 是 | 1~128 字符 `[待核实]` 官方 code 长度；同一 code 只用一次 |
| | nickname | string | 否 | 1~30，去首尾空白，纯空白视为未传 |
| | avatarKey | string | 否 | ≤255，`^[0-9A-Za-z][0-9A-Za-z/_.-]*$` 且不含 `..` |
| `WechatBindReq` / bind | code | string | 是 | 同上 |
| `QrSessionCreateReq` / sessions | — | — | — | **无请求体字段**；渠道信息只进日志，不落库 |
| `QrConfirmReq` / confirm | bindToken | string | 否 | 43 字符 Base64URL（32 字节随机）；仅无小程序登录态时必须 |
| | userInfo | object | 否 | `{nickname? 1~30, avatarKey? ≤255}`，见 §9 生效规则 |
| `QrRegisterConfirmReq` / register-and-confirm | bindToken | string | 是 | 同上 |
| | username | string | 是 | 4~20，`^[A-Za-z0-9_]{4,20}$` 且不得以 `deleted_` 开头（与 M1 注册规则一致）；冲突 409 `AUTH_USERNAME_TAKEN` |
| | password | string | 是 | 8~64，可见 ASCII 且同时含字母与数字；BCrypt(10) 存储 |
| | nickname | string | 否 | 1~30，缺省 `微信用户+4位随机` |
| `QrBindConfirmReq` / bind-and-confirm | bindToken | string | 是 | 43 字符 Base64URL；绑定目标为**当前登录用户**（`Authorization` 必带），不接收 username/password |
| `QrTicketExchangeReq` / tickets/exchange | ticket | string | 是 | 43 字符 Base64URL；一次性 |

### 4.2 响应

| VO / 路由 | 字段 | 类型 | 说明 |
|---|---|---|---|
| `TokenPairVO`（login / exchange 共用） | accessToken / refreshToken | string | 由 M1 签发；`expiresIn` 单位秒 |
| | tokenType / expiresIn | string / int | 固定 `Bearer` / `900` |
| | user | `UserBriefVO` | 见下 |
| `UserBriefVO` | id / username / nickname / avatarKey / role / status | string / string / string / string? / string / string | id 为字符串 |
| | openidBound | boolean | **openid 明文永不输出**（01 §5.2） |
| `WechatBindVO` | bound / user | boolean / `UserBriefVO` | 幂等：重复绑同一 openid 返回 `bound=true` |
| `QrSessionVO`（创建） | sceneId / status / expiresAt | string / `QrStatus` / string | `status=PENDING`；客户端按契约拼 `/code` 地址 |
| `QrStatusVO`（轮询） | sceneId / status / ticket / expiresAt | string / `QrStatus` / string? / string | 仅 `CONFIRMED` 返回 ticket（不消费）；`COMPLETED/EXPIRED` 时 ticket 为 `null` |
| `QrConfirmVO`（三个确认接口） | sceneId / status / bindToken / bindTokenExpiresAt | string / `QrStatus` / string? / string? | 仅 `confirm` 下发一次性 bindToken（60s），供后续 register-and-confirm / bind-and-confirm 使用；后两者返回 `null` |
| 二维码图片 | — | 二进制 | `Content-Type: image/png`，**统一响应壳的显式例外**（00 §5.1）；失败仍返回 JSON 错误体（404/410/502） |

## 5. 数据表与存储

> 字段定义只引用 [03-数据库设计](../03-数据库设计.md) §3.1.1、§3.7.7，本文不重复 DDL。

| 表 | 用途 | 关键字段/索引 |
|---|---|---|
| `wechat_qr_sessions`（**M8 独占写**） | 扫码会话审计与补偿 | `scene_id`（`uk_scene`）、`status`+`expires_at`（`idx_status_expires`）、`user_id`（`idx_user`，`ON DELETE SET NULL`）、`temp_openid`、`bind_token_hash`/`bind_expires_at`、`client_ip`/`user_agent`、`expires_at` |
| `users`（**不直接读写**） | 微信身份归属与来源 | 由 M1 `WechatIdentityService` 维护：`openid`（`uk_openid`，NULL 可重复）、`unionid`（`uk_unionid`，NULL 可重复；冲突 409）、`source`（`WEB/WECHAT/QR`）、`password_hash` NOT NULL（微信自动创建账号为随机安全哈希）。M8 只传入口来源参数，**不注入 `WechatUserMapper`、不写任何 `users` SQL**；openid 的写入/释放/匿名化均由 M1 在同一事务内完成 |

**来源（`users.source`）**：注册/后台创建写 `WEB`；微信自动创建写 `WECHAT`；`register-and-confirm` 创建写 `QR`。来源由 **M1 按 M8 传入的入口参数**写入（M8 只传 `WECHAT`/`QR` 入口标识），不接受客户端传参；**不再用 `wx_` 用户名前缀标识来源**（前缀只用于自动生成登录名）。

**Redis 热点键**（前缀规范见 02 §5.7；TTL 必须显式设置；Redis 只做缓存，状态真相在 DB）

| key | 类型 | TTL | 用途 |
|---|---|---|---|
| `wzblog:wechat:qr:{sceneId}` | String(JSON) | 120s | 会话热点状态 `{status,userId,expiresAt}`，减少轮询读库 |
| `wzblog:wechat:qr:ticket:{ticket}` | String(userId) | 60s | 一次性 loginTicket，`GETDEL` 原子消费 |
| `wzblog:wechat:qr:bind:{tokenHash}` | String(sceneId) | 60s | bindToken 快速校验，消费后 `DEL` |
| `wzblog:wechat:qrcode:{sceneId}` | String(bytes) | 120s | 小程序码图片缓存，避免重复调微信 |
| `wzblog:wechat:mp:access_token` | String | `expires_in-300` | 微信 access_token 缓存 |
| `wzblog:wechat:mp:access_token:lock` | String | 5s | 刷新单飞（`SET NX PX`） |
| `wzblog:wechat:qr:poll:{sceneId}:{ip}` | String | 1s | 轮询限流计数 |

## 6. 微信外部 API

统一用 `RestClient`，连接 1s / 读 3s 超时；任何失败都不得阻塞密码登录。只写官方已知字段，其余标 `[待核实]`。
官方文档：`auth.code2Session`、`wxacode.getUnlimited`、`auth.getAccessToken`（见 §18 链接）。

### 6.1 `jscode2session`

`GET https://api.weixin.qq.com/sns/jscode2session`

| 方向 | 字段 | 说明 |
|---|---|---|
| 请求 query | `appid`、`secret`、`js_code`、`grant_type=authorization_code` | 是否必须全部为大写常量见官方文档 `[待核实]` |
| 响应 | `openid`、`session_key`、`unionid`（条件返回）、`errcode`、`errmsg` | `session_key` **不落库、不返回、不入日志**；`openid` 不对外输出 |

### 6.2 `getwxacodeunlimit`

`POST https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=...`

| 方向 | 字段 | 说明 |
|---|---|---|
| 请求 body | `scene`（必填，短 ID，≤32 可见字符 `[待核实 V4]`） | 只允许数字/大小写字母与部分符号，本模块只用 `[0-9A-Za-z]` |
| | `page` | 已发布的小程序页面路径，不带前导 `/` `[待核实 V4]` |
| | `width` / `env_version` / `check_path` / `auto_color` / `line_color` / `is_hyaline` | 可选；`width` 默认 430、范围上限 `[待核实]`；`env_version` 取 `release`（配置项） |
| 响应 | 成功：图片二进制（`Content-Type: image/jpeg`） | 判定：Content-Type 含 `image`；**服务端转码为 PNG 后对外返回 `image/png`**（契约要求），转码失败按 502 `WECHAT_API_ERROR` 返回 JSON |
| | 失败：JSON `{errcode, errmsg}` | 判定：Content-Type 含 `json`；按 §6.4 映射 |

限额（每日生成数量、未发布小程序是否可用、码有效期）均 `[待核实 V4]`，实现前实测；命中限额时降级为「手填 scene 的纯文本码 + 小程序手动输入」，不阻塞 PC 会话。

### 6.3 access_token

`GET https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=...&secret=...` → `access_token`、`expires_in`（秒）。
缓存 TTL = `expires_in - 300`；过期前单飞刷新（`SET NX PX 5000` + 双重检查）；刷新失败沿用旧值 30 秒后重试，仍失败返回 `WECHAT_API_ERROR`。是否启用稳定版 token 接口 `[待核实]`。

### 6.4 错误码映射（上游 → 本模块）

| 上游 errcode | 含义（官方口径） | 本模块 code | HTTP |
|---|---|---|---|
| `-1` | 系统繁忙 | `WECHAT_API_ERROR` | 502 |
| `40029` | js_code 无效 | `WECHAT_CODE_INVALID` | 400 |
| `40163` | code 已被使用 `[待核实]` | `WECHAT_CODE_INVALID` | 400 |
| `45011` | 频率限制 | `WECHAT_RATE_LIMITED` | 429 |
| `40226` | 高风险用户被拦截 | `WECHAT_RISK_REJECTED` | 403 |
| `40164` | 调用 IP 不在白名单 `[待核实]` | `WECHAT_API_ERROR`（启动自检告警） | 502 |
| 其他 / 未知 | — | `WECHAT_API_ERROR`（原文只进 DEBUG 日志，不回前端） | 502 |

## 7. 小程序 code 登录与绑定规则

| 场景 | 调用 M1 | 结果 |
|---|---|---|
| `openid` 存在、`status=ACTIVE`、`deleted_at IS NULL` | `loginOrCreateByWechat(openid, unionid, nickname, avatarKey)` → `{userId, status=ACTIVE, created=false}` | M1 更新 `last_login_at/last_login_ip`（`unionid` 有值则写）→ M8 `issueTokens(userId)`（M1 内部发布 `UserLoggedInEvent`） |
| `openid` 不存在 | 同一服务，M1 创建正式 USER：`username = wx_` + 12 位随机 `[a-z0-9]`（仅登录名，唯一冲突重试 3 次）；`nickname = 入参或 微信用户+4 位随机`；`password_hash = BCrypt(32 字节随机)`；`source='WECHAT'`；`role=USER`、`status=ACTIVE`；返回 `created=true` | M8 随后签发 Token |
| `unionid` 已属于**其他**用户 | M1 抛 `WECHAT_UNIONID_CONFLICT` | 409，不创建/不绑定、不签发 Token |
| `openid` 存在但 `status=DISABLED` | 返回 `{userId, status=DISABLED}` | 403 `AUTH_ACCOUNT_DISABLED`，不签发 Token |
| `openid` 存在但 `deleted_at` 非空 | M1 抛 `WECHAT_ACCOUNT_DELETED` | 409（唯一键仍占用，不做自动复活） |
| 绑定：当前用户 `openid IS NULL` 且该 openid 未被占用 | `bindWechat(userId, openid, unionid)` | 绑定成功，保留原 `source`，返回 `bound=true` |
| 绑定：该 openid 已属于**其他**用户 | M1 抛 `WECHAT_ALREADY_BOUND` | 409 |
| 绑定：当前用户已绑同一 openid | M1 幂等返回 | 200 `bound=true`（不重复写） |
| 绑定：当前用户已绑**另一个** openid | M1 抛 `WECHAT_ACCOUNT_ALREADY_BOUND` | 409（不支持换绑，见 §18） |

- 登录响应 `UserBriefVO.openidBound` 仅表示"是否已绑定"，不回显 openid。
- **无「占位账号转正」语义**：未知 openid 由 M1 创建**正式随机密码账号**（可直接用小程序身份登录）；`register-and-confirm` 由 M1 **另建正式账号并释放/匿名化**同 openid 的自动账号，不修改自动账号的用户名/密码。
- 首次微信登录同样触发 M1 的每日登录积分事件（`UserLoggedInEvent`），M8 不重复调用 M4。
- 绑定成功后不吊销既有会话（同一用户，语义不变）。
- `/auth/wechat/bind` 不做 openid 迁移：目标 openid 已属任何账号（含微信自动创建账号）→ M1 返回 `WECHAT_ALREADY_BOUND`；需要从自动账号迁移时走扫码 `bind-and-confirm`（登录态 + bindToken）。
- 并发下的 `uk_openid` 冲突由 M1 `loginOrCreateByWechat` 内部捕获 `DuplicateKeyException` 后回查并走登录分支，M8 不重试创建、不产生双账号。

## 8. 扫码状态机

```
PENDING ──confirm──► CONFIRMED ──exchange──► COMPLETED
   │                     │
   └──── 2min TTL ───────┴──► EXPIRED
```

- `PENDING`：PC 已创建；`CONFIRMED`：小程序已确认，Redis 已放 ticket；`COMPLETED`：ticket 已被兑换（只作审计）；`EXPIRED`：超时。
- 确认：`UPDATE wechat_qr_sessions SET status='CONFIRMED', user_id=?, updated_at=NOW(3) WHERE scene_id=? AND status='PENDING' AND expires_at>NOW(3)`；影响行数 0 → 读库区分 404 / `WECHAT_QR_EXPIRED` / `WECHAT_QR_ALREADY_CONFIRMED`。
- `scene_id`：16 位随机 `[0-9A-Za-z]`（约 95 bit），不可枚举、不含时间戳、全局唯一（`uk_scene` 冲突重试 3 次）。**应用层长度校验 1~32**（与微信 `scene` 上限一致）；DDL `VARCHAR(64)` 只是余量，不表示可传 64 位。
- 二维码图片：以 `sceneId` 为缓存键，TTL 与会话对齐；缓存未命中才调微信，且**按会话加单飞锁**，同一 scene 并发只调一次上游。
- 轮询只读状态，`CONFIRMED` 时附带 ticket（**不消费**），`COMPLETED/EXPIRED` 时 `ticket=null`。
- **禁止轮询接口签发 JWT**：任何状态轮询都不得调用 `issueTokens`，PC 唯一取 Token 入口是 `/auth/qr/tickets/exchange`。
- 过期：懒判定（读时 `expires_at<=NOW(3)` 视同 `EXPIRED`）+ 每分钟清理任务把 `PENDING` 且过期者置 `EXPIRED`（走 `idx_status_expires`，ShedLock 防并发）。
- DB 只做审计与补偿；`COMPLETED` 不回写 ticket（ticket 只在 Redis，60s，`GETDEL`）。

## 9. 小程序确认三步

前置：小程序端扫到 `scene` 后先调 `/auth/wechat/login` 拿到小程序登录态（JWT），或持有上一轮下发的 bindToken。三个接口都要求 `status='PENDING'` 且未过期（条件更新）。

| 接口 | 前置身份 | 行为 |
|---|---|---|
| `confirm` | 小程序登录态（`Authorization`）**或** bindToken | 校验用户 ACTIVE → 条件更新 `user_id`、`CONFIRMED`、放 ticket（60s）；带小程序 JWT 时把其 openid 写入 `temp_openid`；下发一次性 `bindToken`（60s）。不修改账号用户名/密码，不写 `users`；`userInfo` 仅按下方规则合并 |
| `register-and-confirm` | bindToken | 取 bindToken 对应 openid/unionid，调 M1 `registerAndBindWechat(username, password, nickname, openid, unionid)` → userId：M1 创建 `source='QR'` 正式账号、绑定 openid，并将同 openid 的自动账号匿名化（`username='deleted_{id}'`、`openid` 改写、`unionid=NULL`、`deleted_at=NOW(3)`）以释放唯一键；openid 已属其他正式账号 → 409 `WECHAT_ALREADY_BOUND`；username → 409 `AUTH_USERNAME_TAKEN`；unionid → 409 `WECHAT_UNIONID_CONFLICT`。M8 只做会话条件更新与 ticket |
| `bind-and-confirm` | **登录态（`Authorization`）+ bindToken** | 调 M1 `bindWechat(currentUserId, openid, unionid)`：仅绑定到当前登录用户（要求其 `openid IS NULL`），必要时匿名化同 openid 的自动账号；openid 属其他正式账号 → 409 `WECHAT_ALREADY_BOUND`；当前用户已绑其他 openid → 409 `WECHAT_ACCOUNT_ALREADY_BOUND`。不接收 username/password、不切换登录账号；随后确认会话 |

- `bindToken`：32 字节随机 Base64URL，库中只存 SHA-256(hex)，60 秒一次性；消费 = Redis `DEL` + DB `UPDATE ... SET bind_token_hash=NULL, bind_expires_at=NULL WHERE scene_id=? AND bind_token_hash=?`，行数 0 → 已用/过期。
- `userInfo` 生效规则：仅在 `register-and-confirm` 且 M1 本次新建账号时生效，作为 `registerAndBindWechat` 的 `nickname`（头像 key 由登录/资料接口维护）；其余情况忽略，不覆盖已有资料。
- 确认成功后小程序端只得到 `QrConfirmVO`，**不签发 Token**（避免一次扫码产生双份会话）；PC 端凭 ticket 换取。自动账号不参与"转正"，`register-and-confirm` 是 M1 新建账号 + 迁移 openid。

## 10. 关键时序

**A 小程序 code 登录**：小程序 → `code` → M8 `jscode2session` → openid/unionid → M1 `WechatIdentityService#loginOrCreateByWechat(openid, unionid, nickname, avatarKey)` → `{userId, status, created}`（M1 内处理已注销/`unionid` 冲突/`uk_openid` 并发）→ `status=ACTIVE` 后 `AuthAppService#issueTokens(userId)` → 返回 TokenPair + UserBrief（IP/UA 由 M1 从请求上下文取，`UserLoggedInEvent` 由 M1 发布）。

**B 创建扫码会话 + 取码**：PC → `POST /auth/qr/sessions`（无请求体字段）→ 生成 `sceneId`（1~32 位，重试至唯一）→ 写 `wechat_qr_sessions(PENDING, expires_at=+120s, client_ip, user_agent)` → 写 Redis 状态 → 返回 `sceneId/expiresAt`；PC 再请求 `/code` → 命中 PNG 缓存直接返回，否则取 access_token（单飞）→ `getwxacodeunlimit` → 转码为 PNG → 缓存并返回 `image/png` 二进制。

**C 扫码确认**：小程序带 scene → `confirm`（JWT 或 bindToken）→ 条件更新 → Redis 写 ticket（60s）→ 返回 `CONFIRMED` + 一次性 bindToken。**D 注册并确认**：用户在小程序填 username/password → `register-and-confirm` → M1 `registerAndBindWechat(...)` 创建 `source='QR'` 正式账号、绑定 openid 并匿名化同 openid 自动账号 → M8 确认会话 + ticket。**E 绑定并确认**：网页已登录用户凭 `Authorization` + bindToken → `bind-and-confirm` → M1 `bindWechat(currentUserId, openid, unionid)` 绑定（必要时匿名化自动账号）→ M8 确认会话 + ticket。

**F PC 换 Token**：轮询到 `CONFIRMED` 带 ticket → `POST /auth/qr/tickets/exchange {ticket}` → Redis `GETDEL` 取 userId → 校验用户 ACTIVE → `UPDATE ... SET status='COMPLETED' WHERE scene_id=? AND status='CONFIRMED'` → `issueTokens` → 返回 TokenPair（ticket 已删，重放必然失败）。

**G 过期**：用户不扫 → 120s 后轮询返回 `EXPIRED`；清理任务把 DB 置 `EXPIRED`；此时确认一律 `WECHAT_QR_EXPIRED`。

失败流：

| 场景 | 检测点 | HTTP | 错误码 | 事务 |
|---|---|---|---|---|
| 模块未配置 | AppID/Secret 为空 | 503 | `WECHAT_NOT_ENABLED` | 无事务 |
| code 无效/已用 | 上游 errcode | 400 | `WECHAT_CODE_INVALID` | 无事务 |
| 上游超时 | RestClient 超时 | 502 | `WECHAT_API_ERROR` | 无事务 |
| 会话不存在 | 读 `uk_scene` 为空 | 404 | `WECHAT_QR_NOT_FOUND` | 无事务 |
| 会话过期 / 已确认 | 条件更新 0 行 | 410 / 409 | `WECHAT_QR_EXPIRED`（过期，410） / `WECHAT_QR_ALREADY_CONFIRMED`（已确认，409） | 回滚 |
| bindToken 错/过期/已用 | Redis 未命中或哈希不匹配 | 400 | `WECHAT_BIND_TOKEN_INVALID` | 回滚 |
| ticket 无效/已用/过期 | `GETDEL` 为 null | 401 | `WECHAT_TICKET_INVALID` | 回滚 |
| 用户名冲突 | M1 `registerAndBindWechat`（`uk_username`） | 409 | `AUTH_USERNAME_TAKEN` | 回滚（M8 会话不变） |
| unionid 冲突 | M1 `WechatIdentityService`（`uk_unionid`） | 409 | `WECHAT_UNIONID_CONFLICT` | 回滚 |
| openid 已属其他正式账号 | M1 `bindWechat` / `registerAndBindWechat` | 409 | `WECHAT_ALREADY_BOUND` | 回滚 |
| 账号已停用 / 已注销 | M1 `loginOrCreateByWechat` | 403 / 409 | `AUTH_ACCOUNT_DISABLED` / `WECHAT_ACCOUNT_DELETED` | 不签发 Token |

## 11. 并发、事务、幂等

| 场景 | 事务边界 | 锁/条件更新 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| 多端同时确认同一 scene | 单事务（会话条件更新 + M1 身份写入 + 提交后放 ticket） | `WHERE status='PENDING' AND expires_at>NOW(3)` | `scene_id` | 仅 1 成功，其余 409；不引入悲观锁，纯条件更新 |
| 过期与确认竞态 | 同事务 | 条件含 `expires_at>NOW(3)`，不用应用时钟判过期 | `scene_id` | 0 行 → 409，不落 ticket |
| ticket 重放 | 无 DB 事务（Redis 原子） | `GETDEL` | `ticket` | 二次调用 `WECHAT_TICKET_INVALID`；DB 已 COMPLETED 时直接拒绝 |
| ticket 兑换与状态回写不一致 | 短事务 | `WHERE status='CONFIRMED'`；0 行时**不签发** Token，先查状态 | `scene_id` | 已 COMPLETED → 401 |
| openid 唯一冲突（并发新建） | M1 单事务（M8 会话同事务） | M1 内依赖 `uk_openid`，捕获 `DuplicateKeyException` 后**回查并走登录分支** | `openid` | M1 不重试创建，避免双账号；M8 原样返回结果 |
| bindToken 并发消费 | 单事务 | Redis `DEL` 返回值 + DB 哈希条件更新 | `scene_id+tokenHash` | 二者任一失败即整体失败 |
| 注册/绑定并发（username） | M1 单事务（M8 会话同事务） | M1 内 `uk_username` 唯一约束，捕获 `DuplicateKeyException` | `username` | 409 `AUTH_USERNAME_TAKEN`，不重试创建、不消费 bindToken |
| 注册/绑定并发（unionid） | M1 单事务（M8 会话同事务） | M1 内 `uk_unionid` 唯一约束 | `unionid` | 409 `WECHAT_UNIONID_CONFLICT`，整体回滚 |
| openid 迁移并发（自动账号 → 新账号/当前用户） | M1 单事务（M8 会话同事务） | M1 内先 `UPDATE ... WHERE id=? AND openid=?` 释放，再 `WHERE id=? AND openid IS NULL` 写入 | `openid` | 任一 0 行整体回滚，不留"两个账号抢同一 openid" |
| access_token 刷新 | 无事务 | Redis `SET NX PX 5000` 单飞 + 双重检查 | 固定锁键 | 未抢到锁者短暂重试读缓存 |
| 会话清理任务 | 单批事务 | `UPDATE ... WHERE status='PENDING' AND expires_at<=NOW(3) LIMIT 500` | 幂等（重复执行结果一致） | 记录影响行数指标 |

锁顺序固定：`users`（**M1 内部事务**，先目标账号再自动账号，如有）→ `wechat_qr_sessions`；M8 不直接对 `users` 加锁，禁止反序，避免死锁。

## 12. 权限

| 操作 | 角色 | 归属校验 | 说明 |
|---|---|---|---|
| 创建 / 轮询 / 取码 / 确认 / 注册确认 / 绑定确认 / 换票 | PUBLIC | — | 凭 `sceneId`（不可枚举）、`bindToken`（一次性）、`ticket`（一次性）授权 |
| `confirm` | PUBLIC | 需小程序 JWT **或** bindToken，二者皆无 → 401 `AUTH_UNAUTHORIZED` | 路由标 `PUBLIC`，鉴权在应用层 |
| `bind-and-confirm` | PUBLIC | 需登录 JWT **且** bindToken（绑定到当前登录用户），缺一 → 401 | 路由标 `PUBLIC`，鉴权在应用层 |
| `/auth/wechat/bind` | USER | 只能绑当前登录用户 | 不接受 `userId` 参数，杜绝越权 |
| 管理端 | — | — | 本模块无管理路由 |

## 13. 错误码（`WECHAT_*` / `AUTH_*`）

| code | HTTP | 触发条件 | 前端提示 |
|---|---|---|---|
| `WECHAT_NOT_ENABLED` | 503 | 未配置 AppID/Secret 或模块关闭 | 微信登录未启用，请用账号密码登录 |
| `WECHAT_CODE_INVALID` | 400 | 上游 40029/40163 或 code 为空 | 登录凭证已失效，请重新进入小程序 |
| `WECHAT_RATE_LIMITED` | 429 | 上游 45011 | 操作过于频繁，请稍后再试 |
| `WECHAT_RISK_REJECTED` | 403 | 上游 40226 | 当前环境存在风险，请更换网络 |
| `WECHAT_API_ERROR` | 502 | 上游超时/未知 errcode | 微信服务暂不可用，请稍后再试 |
| `WECHAT_ACCOUNT_DELETED` | 409 | openid 属于已注销账号（M1 `loginOrCreateByWechat` 抛出） | 该微信已绑定已注销账号 |
| `WECHAT_ALREADY_BOUND` | 409 | openid 已绑定其他账号（M1 `bindWechat`/`registerAndBindWechat` 抛出） | 该微信已绑定其他账号，请直接登录 |
| `WECHAT_ACCOUNT_ALREADY_BOUND` | 409 | 当前账号已绑其他 openid（M1 `bindWechat` 抛出） | 当前账号已绑定其他微信，暂不支持换绑 |
| `WECHAT_UNIONID_CONFLICT` | 409 | unionid 已属于其他账号（M1 内 `uk_unionid`） | 该微信已关联其他账号 |
| `WECHAT_QR_NOT_FOUND` | 404 | `sceneId` 不存在 | 二维码无效，请刷新页面重试 |
| `WECHAT_QR_EXPIRED` | 410 | 会话过期 | 二维码已过期，请刷新 |
| `WECHAT_QR_ALREADY_CONFIRMED` | 409 | 会话已确认/已完成 | 该二维码已被使用 |
| `WECHAT_BIND_TOKEN_INVALID` | 400 | bindToken 缺失/过期/已用/哈希不匹配 | 操作已超时，请重新扫码 |
| `WECHAT_TICKET_INVALID` | 401 | ticket 缺失/过期/已兑换 | 登录凭证已失效，请重新扫码 |
| `AUTH_UNAUTHORIZED` | 401 | 未带 JWT 且无 bindToken | 请先登录或重新扫码 |
| `AUTH_ACCOUNT_DISABLED` | 403 | 用户 `status=DISABLED` | 账号已停用 |
| `AUTH_USERNAME_TAKEN` | 409 | `register-and-confirm` 用户名冲突（M1 `registerAndBindWechat` 返回） | 用户名已被占用 |
| `COMMON_RATE_LIMITED` | 429 | 轮询超过 1 次/秒 | 操作过于频繁 |
| `COMMON_VALIDATION_ERROR` | 400 | 参数校验失败 | 见 `errors` 字段 |

> **登记状态**：04 已生成，下表错误码均已登记到 04 §7.9；新增错误码必须先改 00 前缀规则与 04 目录（00 §5.3 禁止未登记代码）。
> **错误来源**：与身份创建/绑定相关的 `AUTH_USERNAME_TAKEN`、`WECHAT_ALREADY_BOUND`、`WECHAT_ACCOUNT_ALREADY_BOUND`、`WECHAT_UNIONID_CONFLICT`、`WECHAT_ACCOUNT_DELETED`、`AUTH_ACCOUNT_DISABLED` 由 M1 `WechatIdentityService` 抛出，M8 原样透传为对应 HTTP 状态，不新增错误码、不改写 message。

## 14. 验收用例

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M8-001 | openid 已绑 ACTIVE 老用户 | `POST /auth/wechat/login` 有效 code | 200；同一 userId；`last_login_at` 更新；refresh_tokens 新增 1 个 family |
| TC-M8-002 | openid 未注册 | 同上 | 200；M1 `loginOrCreateByWechat` 新建 USER，`source='WECHAT'`、`role=USER`、`password_hash` 非空且不可猜；`created=true`；M8 未注入 `WechatUserMapper`、未直写 `users` |
| TC-M8-003 | 用户 `status=DISABLED` | 同上 | 403 `AUTH_ACCOUNT_DISABLED`；无 Token；无 refresh_tokens 落库 |
| TC-M8-004 | 同一 code 重复提交 | 连续两次 login | 第二次 400 `WECHAT_CODE_INVALID`（上游 40163 或本地一次性校验） |
| TC-M8-005 | 用户已绑 openid A，另一用户持 openid A | `POST /auth/wechat/bind` | 409 `WECHAT_ALREADY_BOUND`；双方 `users.openid` 不变 |
| TC-M8-006 | 已登录用户 `openid IS NULL` | bind 有效 code | 200 `bound=true`；`openid` 写入；重复调用幂等 |
| TC-M8-007 | 未登录 | 调 `/auth/wechat/bind` | 401 `AUTH_UNAUTHORIZED`（M1 鉴权链） |
| TC-M8-008 | 创建会话 | `POST /auth/qr/sessions` | 201；`sceneId` 16 位 `[0-9A-Za-z]`；`expiresAt-now≈120s`；DB 一行 PENDING |
| TC-M8-009 | 已有会话 | `GET .../code` 两次 | 200，`Content-Type: image/png`；上游 `getwxacodeunlimit` 只调用 1 次（日志计数） |
| TC-M8-010 | 会话 2 分钟后 | 轮询 | `status=EXPIRED`，`ticket=null` |
| TC-M8-011 | 过期会话 | `confirm` 带小程序 JWT | 410 `WECHAT_QR_EXPIRED`；DB 仍 `EXPIRED`，无 ticket |
| TC-M8-012 | PENDING 会话，两个小程序请求并发 | 同时 `confirm` | 恰好 1 个 200 CONFIRMED，另 1 个 409；Redis 仅 1 个 ticket |
| TC-M8-013 | 已确认会话 | 再次 `confirm` | 409 `WECHAT_QR_ALREADY_CONFIRMED` |
| TC-M8-014 | 小程序已登录用户 | `confirm`（JWT） | 200；`CONFIRMED`；返回 `ticket` 与一次性 `bindToken`；DB 写 `bind_token_hash`（64 hex）与 `bind_expires_at`（+60s）；`temp_openid` 非空 |
| TC-M8-015 | 持上述 bindToken | `register-and-confirm`（新用户名/密码） | 200；M1 `registerAndBindWechat` 创建新账号 `source='QR'` 并绑定 openid，原微信自动账号被匿名化且 `openid` 释放；新账号可用密码登录；会话 `CONFIRMED`；M8 只写 `wechat_qr_sessions` |
| TC-M8-016 | 同一 bindToken | 第二次 `register-and-confirm` | 400 `WECHAT_BIND_TOKEN_INVALID`；DB 无二次变更、无重复账号 |
| TC-M8-017 | 网页已登录用户（`openid IS NULL`）+ bindToken | `bind-and-confirm` | 200；M1 `bindWechat(currentUserId, openid, unionid)` 绑定到当前登录用户；若原属自动账号则由 M1 匿名化；`uk_openid` 冲突 0 次；不接收账号密码 |
| TC-M8-018 | 未登录调用 `bind-and-confirm` | 仅带 bindToken | 401 `AUTH_UNAUTHORIZED`；bindToken 未被消费 |
| TC-M8-019 | 会话 CONFIRMED 且已取到 ticket | `POST /auth/qr/tickets/exchange` | 200 TokenPair；DB `COMPLETED`；Redis ticket 被删 |
| TC-M8-020 | 同上 ticket | 再次 exchange | 401 `WECHAT_TICKET_INVALID`；不产生第二个 refresh family |
| TC-M8-021 | 伪造/他人 `sceneId` | 轮询或确认 | 404 或 409，不泄露会话内容；不返回其他用户信息 |
| TC-M8-022 | 上游返回 40226 / 超时 | login | 403 `WECHAT_RISK_REJECTED` / 502 `WECHAT_API_ERROR`；响应不含微信原文 |
| TC-M8-023 | access_token 缓存为空，10 并发取码 | 并发 `/code` | 上游 token 接口仅 1 次调用；缓存 TTL ≈ `expires_in-300` |
| TC-M8-024 | 轮询频率 10 次/秒 | 连续轮询 | 超出部分 429 `COMMON_RATE_LIMITED`，带 `Retry-After` |
| TC-M8-025 | 日志与响应抓取 | 全流程 | 无 `openid` 明文、无 `session_key`、无 `bindToken`/`ticket` 明文 |
| TC-M8-026 | 已存在用户名 `alice` | `register-and-confirm` 用 `alice` | 409 `AUTH_USERNAME_TAKEN`（M1 抛出）；不创建账号、不消费 bindToken、会话仍 `CONFIRMED` 可重试 |
| TC-M8-027 | unionid 已属其他账号 | 微信登录 / register-and-confirm | 409 `WECHAT_UNIONID_CONFLICT`（M1 抛出）；不创建/不绑定、不签发 Token |
| TC-M8-028 | 代码与依赖静态检查 | 扫描 M8 模块 | 无 `WechatUserMapper` 注入、无 `users` 表 SQL、无 `@Transactional` 内直写 `users`；身份写入只经 `WechatIdentityService` |
| TC-M8-029 | openid 已属已注销账号 | 微信登录 | 409 `WECHAT_ACCOUNT_DELETED`（M1 抛出）；不自动复活、不新建账号 |

## 15. 性能与索引

| 查询/操作 | 索引 | 预期 | 验收 |
|---|---|---|---|
| 轮询读会话 | `uk_scene` 唯一等值 | 命中 Redis 时 P95 < 10ms；未命中回库 < 15ms | EXPLAIN `type=const` |
| 会话清理 | `idx_status_expires(status, expires_at)` | 单批 500 行 < 50ms | EXPLAIN `range`，不出现全表扫描 |
| openid 登录/绑定查用户（M1 执行） | `uk_openid` | 等值 1 行 | EXPLAIN `type=const` |
| 按 user_id 反查会话（审计） | `idx_user` | 走索引 | — |
| 二维码图片 | Redis 缓存 + 单飞 | 首次 ≤ 1 次上游调用；后续 0 次 | 上游调用计数 |

- 轮询限流：`sceneId+IP` 1 次/秒（超限 429）；同一 IP 同时活跃会话 ≤ 5；`GET /code` 每会话 ≤ 3 次/分钟。
- 不引入 SSE（P2 用长轮询即可）；若后续加 `?wait=` 长轮询，最长 25s，且必须单实例连接计数限流。

## 16. 安全

- **code 一次性**：服务端不缓存 code；上游 40163 视为无效；同一请求重试不得复用 code。
- **session_key 不落库、不外传、不入日志**；本模块不解密用户信息。
- **bindToken / ticket**：只存 SHA-256 或不存（ticket 明文仅存在于 Redis 值中，60s）；日志只打印前 4 位 + 长度。
- **scene 不可枚举**：16 位随机、无自增、无时间戳；`sceneId` 出现在 URL 中，禁止写入 Referer 敏感页面、禁止第三方埋点采集。
- **日志脱敏**：openid 打印为 `sha256(openid)[:8]`；`client_ip` 全量保留（审计需要），UA 截断 255。
- **来源校验**：小程序 code 由微信服务端换取，等价于来源校验，不额外信任客户端传的 openid（**禁止接收前端直传 openid**）；管理端无入口。
- **防劫持**：换票接口只接受 ticket，不接受 `sceneId`；ticket 绑定 userId，兑换后再校验 `status=ACTIVE`。
- **账号接管防护**：`bind-and-confirm` 必须"登录态 + 一次性 bindToken"双因子；`register-and-confirm` 必须持一次性 bindToken；openid 迁移由 M1 `WechatIdentityService` 以 `WHERE id=? AND openid=?` 条件更新完成（**M8 不写 `users`**），自动账号只做匿名化、不改写为正式账号（无「转正」路径）；成功后旧 ticket/bindToken 全部失效。

## 17. 事件与跨模块调用

| 事件/调用 | 方向 | 载荷 | 消费方 | 幂等 |
|---|---|---|---|---|
| `WechatIdentityService#loginOrCreateByWechat` | M8 → M1 | openid, unionid, nickname, avatarKey → `{userId, status, created}` | M1 创建/登录微信身份 | `uk_openid`；并发冲突回查登录 |
| `WechatIdentityService#registerAndBindWechat` | M8 → M1 | username, password, nickname, openid, unionid → userId | M1 新建账号、绑定、释放自动账号 | `uk_username`/`uk_openid`/`uk_unionid` |
| `WechatIdentityService#bindWechat` | M8 → M1 | userId, openid, unionid | M1 绑定到当前用户 | `openid` 条件更新 |
| `AuthAppService#issueTokens(userId)` | M8 → M1 | userId（IP/UA 由 M1 从请求上下文取） | M1 签发双 Token 并发布 `UserLoggedInEvent` | 由 M1 保证 |
| `UserLoggedInEvent` | M1 → M4 | userId, loginAt, ip, firstLoginOfDay | M4 每日登录积分 | `login:{userId}:{date}` |
| `FileAppService`（可选） | M8 → M5 | 头像引用 | M5 文件归属 | 默认**不调用**（见 §18） |

> M8 禁止直接写 `refresh_tokens`、`points_ledger`、`users`，禁止注入 M1 的 Mapper（含 `WechatUserMapper`）；身份写操作只能经 `WechatIdentityService`，Token 只能经 `AuthAppService`。

## 18. 待确认事项

| 事项 | 默认方案 | 需谁确认 |
|---|---|---|
| 小程序主体资质与可用接口范围（01 V3） | 未取得资质则整模块砍单，路由返回 `WECHAT_NOT_ENABLED` | 项目所有者 |
| `getwxacodeunlimit` 对未发布小程序的可用性、`scene` 上限、码有效期（01 V4） | 实测前按 `scene≤32` 设计；不可用则降级为"手动输入 scene" | 项目所有者 |
| 官方错误码全集与文案 | 只映射 §6.4 已知码，其余统一 `WECHAT_API_ERROR` | 实测补充 |
| `bind-and-confirm` 的身份凭证形态（00 未给 DTO） | 登录态 `Authorization` + 一次性 bindToken；不接受账号密码，绑定目标固定为当前登录用户 | 04 契约 |
| 微信自动创建账号与注册的关系 | 未知 openid 由 M1 `loginOrCreateByWechat` 直接创建正式 USER（`source='WECHAT'`、随机密码）；`register-and-confirm` 由 M1 `registerAndBindWechat` 另建 `source='QR'` 新账号并释放/匿名化自动账号；**不存在"占位账号转正"** | 已解决 |
| M8 与 `users` 表的边界 | 已解决：M8 只写 `wechat_qr_sessions` 与 Redis；`users` 的创建/绑定/匿名化全部经 M1 `WechatIdentityService`（00 §9.2 已允许），M8 不注入 `WechatUserMapper` | 已解决 |
| `channel` 请求字段 | **已删除**：`wechat_qr_sessions` 无该列，创建会话无请求体字段 | 已解决 |
| `avatarKey` 与 M5 的关系 | 作为 `loginOrCreateByWechat` 入参交 M1 校验并写入 `users.avatar_key`；M8 不调 `FileAppService`（避免注册时跨模块事务） | M5 |
| `unionid` 是否唯一 | `users.unionid` 唯一索引 `uk_unionid`（NULL 可重复）；由 M1 写入前判定，冲突 409 `WECHAT_UNIONID_CONFLICT` | 已解决 |
| 是否支持换绑微信 | 不支持，返回 `WECHAT_ACCOUNT_ALREADY_BOUND` | 产品 |
| 扫码登录是否默认开启 | 默认关闭；`WECHAT_APP_ID/SECRET` 缺失即关闭，密码登录不受影响；**P2 可整体砍** | 项目所有者 |

**官方文档链接**（字段与限额以原文为准）：
`jscode2session` <https://developers.weixin.qq.com/miniprogram/dev/api-backend/open-api/login/auth.code2Session.html>；
`getwxacodeunlimit` <https://developers.weixin.qq.com/miniprogram/dev/api-backend/open-api/qr-code/wxacode.getUnlimited.html>；
`access_token` <https://developers.weixin.qq.com/miniprogram/dev/api-backend/open-api/access-token/auth.getAccessToken.html>；
服务端 OpenAPI 总览 <https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/>。