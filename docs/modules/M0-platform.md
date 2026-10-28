# M0 · 平台基础设施（模块设计文档）

> 负责人 Agent：M0 Platform Agent
> 依赖模块：无（不依赖任何业务模块）
> 对应阶段：阶段 0（平台底座）
> 状态：已定稿
> 契约版本：以 [00-文档索引与约定](../00-文档索引与约定.md) 1.6 为准
> 定位：本文只定义**接口与边界**；实现骨架见 [02-开发指南](../02-开发指南.md) 第 5 节，不重复。

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.common`（响应/错误/上下文/分页/工具）、`com.wzblog.config`（Jackson/MyBatis/Redis/Security/Web/OpenAPI/Async/Scheduling）、`com.wzblog.shared`（`cache`、`config`、`audit`、`profile`、`event`、`bootstrap`、`ratelimit`、`idempotency`） |
| 表 | 只触碰 `site_configs`（只读，M7 独占写入）与 `audit_logs`（只追加写，M7 只读）；管理员引导一次性写 `users`；字段一律见 [03](../03-数据库设计.md) §3.7.1、§3.7.6、§3.1.1，本文不重复定义 |
| 对外端口 | `SiteConfigService`、`AuditRecorder`、`shared.profile.TitleLookup`（M4 实现）、`DomainEvent`+发布器、`TokenVerifier`（M1 实现）、`RateLimiter`、`IdempotencyStore`、`Result/ErrorCode/BusinessException`、`PageResult/CursorResult/CursorCodec`、`CurrentUser` |
| 被依赖 | M1~M8 全部业务模块 |
| 路由数 | 4（与 00 §4「M0 平台」逐字一致） |
| 不拥有 | 任何业务表、业务枚举、业务码、业务规则 |

## 2. 范围与非目标

- 做：统一响应与错误映射、traceId、请求上下文、分页与游标、Jackson、MyBatis-Plus、Redis、Security 骨架、OpenAPI、异步/调度、审计、站点配置、称号查询端口、管理员引导、健康检查、测试基类与 ArchUnit。
- 不做：登录/令牌签发/刷新/黑名单（M1）；配置管理页面与 `site_configs` 写入（M7）；文件与对象存储（M5）；积分账户与账本（M4）；任何业务 DTO/Entity/路由。
- 硬边界：`common`/`config`/`shared` **不得 import `com.wzblog.modules..`**；`audit_logs` 写入方仅 `AuditRecorder`；`site_configs` 写入方仅 M7，M0 只读 + 缓存失效。

## 3. 平台路由

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/ping` | PUBLIC | 存活探针；`data = {"status":"UP"}`，用于验证响应壳与 traceId |
| GET | `/actuator/health` | PUBLIC | 仅返回 `status`，分组 `liveness`/`readiness` 供编排使用 |
| GET | `/actuator/info` | PUBLIC | 构建版本（`build-info`）、启动时间，不含环境变量与连接串 |
| GET | `/actuator/prometheus` | ADMIN 或内网 | 抓取端点；应用层默认 `hasRole('ADMIN')`，生产以独立管理端口 + 内网绑定实现免认证抓取（08 部署负责），二者不叠加放开 |

> 除上表外 M0 不新增任何路由；`/v3/api-docs`、`/swagger-ui/**`、`/error` 属框架端点，不计入路由数。

## 4. 统一响应与错误

### 4.1 `Result<T>`（`common/api`）

| 字段 | 类型 | 约定 |
|---|---|---|
| `code` | String | 成功固定 `OK`；失败为 `ErrorCode.code()` |
| `message` | String | 成功固定 `success`；失败为面向用户的中文短句，不含 SQL/类名/堆栈 |
| `data` | T | 失败为 `null`；校验失败为 `{"errors":{字段:原因}}` |
| `traceId` | String | 与响应头 `X-Trace-Id` 同值，来自 MDC |

静态工厂：`ok()`、`ok(T)`、`fail(ErrorCode)`、`fail(ErrorCode, String)`、`fail(ErrorCode, String, T)`。创建类接口返回 201 由 Controller 用 `ResponseEntity.status(201)` 包装，壳不变。

### 4.2 `ErrorCode`（`common/error`）

枚举字段：`code`（String，与枚举名逐字相同，便于 grep 对账）、`httpStatus`（int）、`defaultMessage`。平台基础码只列以下 6 个，业务码由各模块按 04 登记后在同一枚举内追加，M0 不新增：

| code | HTTP | defaultMessage |
|---|---|---|
| `OK` | 200 | success |
| `COMMON_VALIDATION_ERROR` | 400 | 参数校验失败 |
| `COMMON_NOT_FOUND` | 404 | 资源不存在 |
| `COMMON_IDEMPOTENCY_CONFLICT` | 409 | 重复提交，或相同幂等键的请求体不一致 |
| `COMMON_RATE_LIMITED` | 429 | 请求过于频繁，请稍后重试 |
| `COMMON_INTERNAL_ERROR` | 500 | 系统繁忙，请稍后重试 |

### 4.3 `BusinessException`（`common/error`）

字段 `errorCode`、`message`（可覆盖默认文案）、`data`（可选附加载荷，通常为 null）。构造：`(ErrorCode)`、`(ErrorCode, String)`、`(ErrorCode, String, Object)`。业务层只抛它，不抛裸 `RuntimeException`。

### 4.4 `GlobalExceptionHandler` 映射

| 触发来源 | HTTP | code | 处理 |
|---|---|---|---|
| `MethodArgumentNotValidException`、`HandlerMethodValidationException`、`ConstraintViolationException`、`BindException` | 400 | `COMMON_VALIDATION_ERROR` | 全量聚合字段错误到 `data.errors`（`LinkedHashMap`，每字段取首条） |
| `HttpMessageNotReadableException`、`MethodArgumentTypeMismatchException`、`MissingServletRequestParameterException` | 400 | `COMMON_VALIDATION_ERROR` | 协议层格式错误不另设码 |
| 缺令牌 / 令牌无效 / 过期（过滤器链） | 401 | `AUTH_UNAUTHORIZED` / `AUTH_TOKEN_INVALID` / `AUTH_TOKEN_EXPIRED` | 由 `AuthenticationEntryPoint` 输出，不经 Advice；缺失与无效必须区分 |
| 权限不足 / 账号停用 | 403 | `AUTH_FORBIDDEN` / `AUTH_ACCOUNT_DISABLED` | 由 `AccessDeniedHandler` 输出；`@PreAuthorize` 拒绝同样落到该 handler |
| 唯一冲突 / 状态不允许（`BusinessException`） | 409 | 模块码（如 `COMMON_IDEMPOTENCY_CONFLICT`） | 条件更新影响 0 行由 app 层翻译；未翻译的 `DataIntegrityViolationException` 记 ERROR 返 500 |
| 过期/已消费（`BusinessException`） | 410 | 模块码（如 `WECHAT_QR_EXPIRED`） | 二维码、ticket、resetToken |
| 业务规则拒绝（`BusinessException`） | 422 | 模块码（如 `POINTS_INSUFFICIENT`） | 余额、库存等 |
| `BusinessException`(429) 或 `RateLimiter` 超限 | 429 | `COMMON_RATE_LIMITED` | 必须带 `Retry-After`（窗口剩余秒数） |
| 上游错误 / 功能关闭 / 上游超时（`BusinessException`） | 502 / 503 / 504 | 模块码（如 `AI_UPSTREAM_ERROR`、`AI_DISABLED`、`AI_UPSTREAM_TIMEOUT`） | 平台只做状态码通道，不专设 handler |
| `NoResourceFoundException`、`NoHandlerFoundException`、方法不支持 | 404 | `COMMON_NOT_FOUND` | 不引入未登记的 405 码 |
| 其他 `Exception` | 500 | `COMMON_INTERNAL_ERROR` | ERROR + 完整堆栈仅进日志；响应只带 code/message/traceId |

- 校验失败结构固定：`{"code":"COMMON_VALIDATION_ERROR","message":"参数校验失败","data":{"errors":{"title":"标题不能为空"}},"traceId":"..."}`。
- 日志级别：业务拒绝 WARN（无堆栈）、未知异常 ERROR（带堆栈）；响应永不带堆栈、SQL、类名、参数值。
- 401/403/429 复用同一 `ObjectMapper` 与 `Result` 结构，禁止返回 HTML 或纯文本。

## 5. 请求上下文

| 组件 | 约定 |
|---|---|
| `TraceIdFilter`（`common/web`，`HIGHEST_PRECEDENCE`） | 读 `X-Trace-Id`，仅接受 `^[A-Za-z0-9_-]{8,64}$`，否则生成 26 位 ULID；写 MDC `traceId` 与响应头；`finally` 清理；`TraceId.current()` 在无上下文时返回 `-` |
| 上下文传递 | 异步线程与 SSE 用 `TaskDecorator` 复制 MDC，禁止子线程丢失 traceId |
| `CurrentUser`（record） | `(Long id, Role role)`；`Role` 枚举定义在 `common/security`，取值 `USER/EDITOR/ADMIN`（00 §6.1），M1 的 `users.role` 复用之 |
| `CurrentUserArgumentResolver` | 支持 Controller 方法参数 `@CurrentUser CurrentUser`；从 `SecurityContextHolder` 的 principal 读取；未登录时注入 `null`（仅 OPTIONAL 路由），必登录路由已由过滤器拦截，故不抛异常 |
| 访问方式 | `CurrentUserContext.current()` → `Optional<CurrentUser>`、`currentId()` → `Optional<Long>`、`role()` → `Optional<Role>`；业务层用 `currentId().orElse(null)` 表达匿名 |
| 角色判定 | 路由级只用 `@PreAuthorize`（见 §10），业务层不按角色写分支；归属校验（OWNER）在 app 层按资源所有者判断，ADMIN 可越过 |

## 6. 分页与游标（`common/api`）

| 类型 | 字段 | 工厂/规则 |
|---|---|---|
| `PageResult<T>` | `list`、`total`、`page`、`pageSize`、`hasNext` | `of(list,total,page,pageSize)` 计算 `hasNext = page*pageSize < total`；`empty(page,pageSize)`；`list` 永不为 null |
| `CursorResult<T>` | `list`、`nextCursor`、`hasNext` | `of(list,nextCursor)`；末页 `nextCursor=null` |
| `PageLimits` | 常量 + 校验注解 | 偏移分页 `page>=1` 默认 1、`pageSize` 默认 20 最大 100；游标 `size` 默认 20 最大 50（00 §5.4） |

- `PageResult.total` 是**计数而非 ID**，用 `long` 并局部 `@JsonFormat(shape=NUMBER)` 输出数字；`list` 内 ID 仍为字符串。
- `CursorCodec`：载荷 `{"t":<ISO-8601 排序值>,"id":<Long>}` → JSON → Base64URL（无 padding）；`encode(Instant, Long)` / `decode(String)`；解码校验：长度 ≤512、Base64URL 合法、`t` 可解析、`id` 为正，失败抛 `BusinessException(COMMON_VALIDATION_ERROR)`，不落到 SQL。
- 防篡改可选：`wzblog.cursor.sign=true` 时输出 `payload.signature`（HMAC-SHA256，密钥 `WZBLOG_CURSOR_SECRET`），校验失败同样 400；默认关闭（游标不含敏感信息）。
- 游标只用于 `(published_at,id)`、`(created_at,id)` 这类有索引的稳定排序，禁止用于可任意排序的管理列表。

## 7. 序列化（`config/JacksonConfig`）

| 项 | 配置 |
|---|---|
| Long → String | 全局注册 `ToStringSerializer`（`Long` 与 `long`），满足 00 §5.1；计数类字段用 `int`/`@JsonFormat(NUMBER)` 局部覆盖 |
| 时间 | `WRITE_DATES_AS_TIMESTAMPS` 关闭、`ADJUST_DATES_TO_CONTEXT_TIME_ZONE` 关闭；`ZoneId=UTC`；VO 用 `Instant`，输出 `2026-10-24T04:00:00.000Z` |
| 未知字段 | `FAIL_ON_UNKNOWN_PROPERTIES` 关闭（旧客户端多传字段不报错） |
| 空值 | **不启用** `non_null`（失败响应必须保留 `data:null`）；空集合输出 `[]` |
| 数字严格性 | 关闭 `ACCEPT_FLOAT_AS_INT`，积分/计数不得被小数污染 |
| 枚举 | JSON 用 `name()`；落库值由实体字段上的 `@EnumValue` 指定，两者取值一致（00 §6.1），禁止序号与魔法数字 |
| 其他 | DTO 禁止 `java.util.Date`；`spring.jackson.time-zone=UTC`、`default-property-inclusion=always` |

## 8. 数据访问（`config/MyBatisConfig`）

| 组件 | 约定 |
|---|---|
| 分页插件 | `PaginationInnerInterceptor(DbType.MYSQL)`，`maxLimit=100`、`overflow=false`（越界返空列表，不回第一页） |
| 乐观锁 | `OptimisticLockerInnerInterceptor`；仅带 `version` 列的表使用（03 §1.4） |
| 逻辑删除 | 按实体注解 `@TableLogic(...)`，不做全局配置；无软删列的表不得标注；以集成测试断言 SQL 片段 |
| 枚举 | `default-enum-type-handler=MybatisEnumTypeHandler`，配合 `@EnumValue` |
| 驼峰 | `map-underscore-to-camel-case=true`；SQL 不写列别名 |
| 扫描 | `@MapperScan({"com.wzblog.modules.**.infra","com.wzblog.shared.**.persistence"})` |
| SQL 日志 | 共享配置不开 `StdOutImpl`；仅 `local` 用 `logging.level.com.wzblog=debug`；`SlowSqlInterceptor` 记录 >200ms 语句（耗时、脱敏参数、traceId） |
| `${}` 禁令 | 一律 `#{}`；动态排序列必须来自白名单枚举 `SortField.toColumn()`；禁止 `select *`；批量写 ≤500 行/批且 `rewriteBatchedStatements=true` |

## 9. Redis 与公共工具（`config/RedisConfig`、`shared/*`）

- Key 规范：`wzblog:{module}:{biz}:{id}`，全小写冒号分隔；花括号只是文档占位符，**实际 key 不含花括号**（Cluster hash tag）。常量集中在 `shared/cache/RedisKeys`，例：`wzblog:identity:refresh:{jti}`、`wzblog:content:article:123`、`wzblog:config:value:{key}`、`wzblog:config:public`、`wzblog:config:invalidate`（频道）。
- 客户端：`StringRedisTemplate` 存计数/令牌/验证码/配置字符串；`RedisTemplate<String,Object>` 存结构体（复用 §7 的 `ObjectMapper`）。所有 key 必须显式 TTL；删除用 `SCAN` + `UNLINK`，禁止 `KEYS *`、禁止 `FLUSHDB`（测试用独立 DB index）。
- Lua：`src/main/resources/lua/`（`rate_limit_sliding_window.lua` 等），`DefaultRedisScript` 单例加载；所有 key 通过 `List` 传入（Cluster 安全，脚本内不读系统时间、不拼 key）。
- 公共工具边界：

| 工具 | 能力 | 边界 |
|---|---|---|
| `RateLimiter`（`shared/ratelimit`） | `Decision tryAcquire(String key, int limit, Duration window)` → `{allowed, remaining, resetAfter}`；超限由调用方抛 `BusinessException(COMMON_RATE_LIMITED)` 或映射为模块码 | 只负责计数；key 由模块按规范拼（如 `wzblog:ai:rl:ip:{ip}`）；窗口语义（业务日/分钟）由调用方换算成 `Duration` |
| `IdempotencyStore` | `boolean reserve(String scope, String key, String requestHash, Duration ttl)`；重复且 hash 不同 → `COMMON_IDEMPOTENCY_CONFLICT` | 只管幂等键（默认 24h），不落业务结果；键值格式由模块传入 |
| 缓存 | 模块自有缓存直接注入 `StringRedisTemplate`，key 必须带自身模块前缀 | 禁止读写他模块 key；Redis 不得作为积分/订单/文章内容的唯一存储（ADR-04） |

## 10. Security 骨架（`config/SecurityConfig`）

- 无状态：`csrf`/`formLogin`/`httpBasic` 关闭，`SessionCreationPolicy.STATELESS`，`NullSecurityContextRepository`，不创建 `HttpSession`。
- `SecurityFilterChain` 顺序：`TraceIdFilter` → CORS → `BearerTokenAuthenticationFilter` → 授权 → `@PreAuthorize`。
- `TokenVerifier` 端口（`common/security`，**M1 实现**）：

| 项 | 约定 |
|---|---|
| 方法 | `Optional<AuthenticatedUser> verify(String accessToken)` |
| 返回 | `AuthenticatedUser(Long userId, Role role, String jti, Instant expiresAt)`；空表示无效/过期/已撤销 |
| 失败 | 不抛异常、不写响应；过滤器视为匿名，受保护路径由 entry point 输出 401 |
| 实现缺省 | M0 阶段 0 无实现时装配 `RejectAllTokenVerifier`（始终 empty），保证骨架可启动、受保护路径恒 401 |
| 额外校验 | 回库校验 `status=ACTIVE`、`jti` 黑名单在 M1 的 `TokenVerifier` 内完成；M0 不查 `users` |

- `AuthenticationEntryPoint` / `AccessDeniedHandler`：输出 §4 的 JSON 壳（401 `AUTH_TOKEN_EXPIRED`、403 `AUTH_FORBIDDEN`/`AUTH_ACCOUNT_DISABLED`），响应头保留 `X-Trace-Id`。
- 权限标记 → 实现（00 §3.2）：`PUBLIC`/`OPTIONAL` → `permitAll()`（后者 principal 可空）；`USER` → `authenticated()`；`EDITOR` → `@PreAuthorize("hasAnyRole('EDITOR','ADMIN')")`；`ADMIN` → `@PreAuthorize("hasRole('ADMIN')")`；`OWNER` → app 层归属校验，不用注解。统一 `ROLE_` 前缀，只用 `hasRole/hasAnyRole`。
- 公开路径（`PublicPaths` 常量，M0 独占维护，模块提 PR）：`/api/v1/ping`、`/actuator/health`、`/actuator/info`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`、`/error`，其余按 00 §4 逐条 `permitAll`；`anyRequest().authenticated()`，禁止整体放行 `/api/v1/**`。
- CORS：来源白名单取 `wzblog.cors.allowed-origins`（生产必填，禁止 `*`）；方法 `GET/POST/PUT/DELETE/OPTIONS`；允许头 `Authorization/Content-Type/Idempotency-Key/X-Trace-Id/X-AI-Session-Token`；暴露头 `X-Trace-Id/Retry-After`；`allowCredentials=false`（Bearer 认证，不用 Cookie）；`maxAge=3600`。
- `/api/v1` 前缀由 `WebMvcConfigurer#configurePathMatch` 统一添加，Controller 不写前缀；Security 规则里写含前缀的完整路径。
- OpenAPI：`OpenAPI` bean（title/version/`bearerAuth`）+ 按模块 `GroupedOpenApi`（`00-platform`、`01-identity`…）；`pathsToMatch` 写含 `/api/v1` 的全路径；`@Schema/@Operation` 只在 DTO/VO/Controller 上；公开接口用 `@SecurityRequirement(name="")` 覆盖。

## 11. 共享服务

### 11.1 `SiteConfigService`（`shared/config`）

| 项 | 约定 |
|---|---|
| 读取 | `getString(key, def)`、`getInt(key, def)`、`getBool(key, def)`、`getJson(key, TypeReference<T>, def)`；键常量与公开白名单在 `SiteConfigKeys`（以 00 §7.1 为准） |
| 缓存 | Caffeine（最大 500，写后 10 分钟）+ Redis `wzblog:config:value:{key}`（TTL 10 分钟）；未命中回表 `uk_config_key`；不存在的键缓存空标记 60 秒 |
| 失效 | `refresh(String key)`：本地 `evict` → Redis `DEL` 值键 + `config:public` → `PUBLISH wzblog:config:invalidate {key}`；监听器收到后按 key 逐出本地缓存；发布失败只 WARN，靠 TTL 兜底 |
| 调用方 | M7 在配置更新事务提交后调 `refresh(key)`（全量传 `null`）；其他模块只读不写 |
| 类型 | 依 `value_type`（STRING/NUMBER/BOOLEAN/JSON）解析，`config_value` 本身是 JSON；类型不符 WARN 一次（5 分钟内去重）并返回默认值，不抛异常、不阻断请求 |
| 安全 | 敏感键（`comment.*`、`ai.*_quota`、`sensitive_words`、名含 `secret/token/password`）不进日志、不入 trace；公开白名单 = `is_public=1` 且不在敏感集合，由 M7 的 `/public/configs` 使用 |

### 11.2 `AuditRecorder`（`shared/audit`）

| 项 | 约定 |
|---|---|
| 方法 | `record(AuditAction action, AuditTargetType targetType, String targetId, Object before, Object after)`；重载末位 `String remark`（≤200，写入 `after_json` 的 `remark` 键，不新增列） |
| 落库 | 追加写 `audit_logs`（字段见 03 §3.7.6）；`operatorId` 取 `CurrentUserContext.currentId()`，系统任务为 `null`；`ip` 取请求上下文（代理头仅在受信代理时采信） |
| 快照 | before/after 序列化为 JSON，写入前脱敏：键名不区分大小写命中 `password/passwordHash/token/refreshToken/secret/apiKey/openid/unionid/phone/email/sensitive_words` 替换 `"***"`，字符串命中邮箱/手机号正则同样替换；单条 ≤8KB，超出截断并标记 |
| 事务 | 存在事务时注册 `afterCommit` 用独立事务（`REQUIRES_NEW`）写入；无事务时立即独立事务写入；任何异常 WARN + `wzblog_platform_audit_failed_total`，**永不抛出、不影响主事务** |
| 枚举 | `action`/`targetType` 只接受 00 §6.1 的 `AuditAction`/`AuditTargetType`，非法值在编译期即失败 |

### 11.3 `TitleLookup`（`shared/profile`，M4 实现）

| 项 | 约定 |
|---|---|
| 端口 | `interface TitleLookup { Optional<TitleBrief> findById(Long titleId); Map<Long, TitleBrief> findByIds(Collection<Long> titleIds); }`；**不提供 `findBrief`**（方法名以此为准） |
| `TitleBrief` | `(Long id, String name, String cssClass)`，与 M1 `TitleBriefVO` 同构；不存在返回 `empty`，不抛异常 |
| 实现 | M4 提供 `@Component` 实现（只读 `titles`）；M0 阶段提供返回 `empty` 的降级实现，`@ConditionalOnMissingBean` 保证 M4 可覆盖 |
| 调用方 | M1（`/users/me`、公开资料）；M0 不查 `user_titles` |

### 11.4 领域事件（`shared/event`）

- `abstract class DomainEvent`：`eventId`（26 位 ULID，构造时生成）、`occurredAt`（`Instant`，取自注入的 `Clock`）；子类只用 record/不可变字段。
- `DomainEventPublisher#publish(DomainEvent)`：薄封装 `ApplicationEventPublisher`，在业务事务内调用。
- 监听器约定：`@TransactionalEventListener(phase=AFTER_COMMIT, fallbackExecution=true)` + `@Async("eventExecutor")`；必须幂等（幂等键见 00 §9.1）；异常由监听器自己吞并记 ERROR，**不得回传主事务**。
- 事件类归属各模块 `domain.event`；M0 只提供基类与发布器，不定义业务事件。

### 11.5 异步与调度（`config/AsyncConfig`、`config/SchedulingConfig`）

- `eventExecutor`：核心 2 / 最大 8 / 队列 1000 / `CallerRunsPolicy`，包装 MDC；`@EnableAsync`。
- 调度线程池 4；`@EnableScheduling` + ShedLock（`@EnableSchedulerLock(defaultLockAtMostFor="PT30M")`）；cron 显式写 `zone="Asia/Shanghai"` 或 `UTC`，不依赖 JVM 默认时区。
- 调度任务不得读取请求上下文（无 `CurrentUser`、无 MDC），审计 `operatorId` 为 `null`。

### 11.6 `AdminBootstrap`

见 §15。

## 12. 配置与启动

| 命名空间 | 关键项 | 默认 |
|---|---|---|
| `wzblog.api` | `prefix=/api/v1` | 固定 |
| `wzblog.security` | `public-paths`、`token-verifier-enabled` | 见 §10 |
| `wzblog.cors` | `allowed-origins` | 本地 `http://localhost:3000`，生产必填 |
| `wzblog.page` | `default-size=20`、`max-size=100`、`cursor-max-size=50` | 00 §5.4 |
| `wzblog.cursor` | `sign=false`，`secret` 取 `WZBLOG_CURSOR_SECRET` | 关闭 |
| `wzblog.cache` | `config.ttl=PT10M`、`config.max-size=500` | 见 §11.1 |
| `wzblog.audit` | `snapshot-max-bytes=8192` | 8KB |
| `wzblog.sql` | `slow-threshold=PT0.2S` | 200ms |
| `wzblog.async` | `core=2`、`max=8`、`queue=1000` | 见 §11.5 |
| `wzblog.observability` | `prometheus-internal-only=false` | 见 §3 |
| `wzblog.admin` | `bootstrap.username/password/email`（映射 `WZBLOG_ADMIN_*`） | 见 §15 |
| `management` | `endpoints.web.exposure.include=health,info,prometheus`、`health.probes.enabled=true` | 仅此三项 |

- 环境变量：`DB_URL/DB_USERNAME/DB_PASSWORD`、`REDIS_HOST/REDIS_PORT/REDIS_PASSWORD`、`WZBLOG_CURSOR_SECRET`（签名开启时）；`JWT_SECRET`（M1）、`MINIO_*`（M5）、`AI_API_KEY`（M6）由对应模块校验，M0 不读取其值。
- Profile：`local` 允许缺失项用默认值；`prod` 无任何默认密码/密钥，缺失即启动失败（`@ConfigurationProperties` + `@Validated`，`JWT_SECRET` <256bit 直接失败由 M1 校验）。
- 启动失败策略：配置绑定校验失败、Flyway 迁移失败、MySQL/Redis 连接不可用 → 直接退出（非零码），不做静默降级；可选组件（MinIO、微信、AI）不可用只影响自身健康项，不阻断启动。
- 禁止散落 `@Value`；配置集中在 `config/properties/*Properties`。

## 13. 可观测

- Actuator 只暴露 `health/info/prometheus`；`health.show-details=never`（生产），`readiness` 含 db/redis，MinIO 作为独立 `HealthIndicator`（`wzblogStorage`）不参与 readiness。
- Micrometer 命名：内置 `http_server_requests_seconds` 直接使用；平台自埋 `wzblog_platform_audit_failed_total`、`wzblog_platform_rate_limited_total{scope}`、`wzblog_platform_config_refresh_total{result}`；业务指标由模块按 `wzblog_{module}_{name}_total` 命名并自带 tag，不抢注全局前缀。
- 日志字段固定：`ts, level, traceId, userId, module, action, costMs`；`%X{traceId}` 进 pattern；禁止密码、token、openid、手机号、邮箱明文。
- 慢 SQL：>200ms WARN，含 mapper 方法、耗时、脱敏参数、traceId；不在日志里拼完整 SQL 值。
- 每个请求出口（`TraceIdFilter` 收尾）记录一条 `method/path/status/costMs`，SSE 与上传接口按完成时间计。

## 14. 测试基类与架构约束

| 资产 | 约定 |
|---|---|
| `AbstractIntegrationTest` | `@SpringBootTest(RANDOM_PORT)` + `@ActiveProfiles("test")` + `@Testcontainers(disabledWithoutDocker=true)`；静态单例容器：MySQL `mysql:8.0`（`utf8mb4`）、Redis `redis:7`、MinIO（`GenericContainer` + `/minio/health/live` 等待）；`@DynamicPropertySource` 注入连接参数；Flyway 从零建库 |
| 清理 | 每个测试方法前清空业务表（关闭外键检查后 `TRUNCATE`），保留 V2 种子表与 `flyway_schema_history`；Redis 使用独立 DB index（`spring.data.redis.database=15`）并 `FLUSHDB`；MinIO 每类重建桶 |
| 时间 | `TestClock`（可推进的 `Clock`）以 `@Primary` 注入，基线 `2026-10-24T00:00:00Z`；业务代码只经 `common/util/Times` 取时间，禁止裸 `now()` |
| `AbstractWebMvcTest` | `@WebMvcTest` + `@Import(SecurityConfig, GlobalExceptionHandler, TraceIdFilter)` + `@MockBean TokenVerifier`，断言壳/状态码/`X-Trace-Id` |
| ArchUnit | ① `api` 不依赖 `infra`；② `domain` 不依赖 `web`/`infra`；③ `common`/`config`/`shared` 不依赖 `modules..`；④ 模块 `mX` 不依赖 `mY` 的 `api`/`infra`/`domain`（仅 `app` 公开接口与 `domain.event` 可跨模块引用） |

## 15. 管理员引导与种子

- 种子数据内容以 [03 §9.1](../03-数据库设计.md) 为准（`categories`/`tags`/`titles`/`site_configs` 14 键，`ON DUPLICATE KEY UPDATE id=id`，不覆盖运营已改值，不写用户）。
- `AdminBootstrap`（`ApplicationRunner`，`@Order(1)`）严格按 03 §9.2 执行：读 `WZBLOG_ADMIN_USERNAME`（缺省 `admin`，仅变量存在时使用）、`WZBLOG_ADMIN_PASSWORD`（缺失则 WARN 跳过）、`WZBLOG_ADMIN_EMAIL`（可选）；用户不存在 → 插入 `users`（`role=ADMIN`、`status=ACTIVE`、BCrypt(10) 密码，列定义见 03 §3.1.1）；已存在且非 ADMIN → 仅提升角色，**不重置密码**。
- M0 不写积分表：`points_accounts` 由 M4 `ensureAccount` 懒创建（M4 §5，已与 03 §9.2 对齐）。
- 幂等：以 `uk_username` 兜底，重复执行无副作用；不写 `login_audits`、不写 `audit_logs`、不发事件；密码不落日志；引导只做一次，运行期不再校验环境变量。

## 16. 验收用例

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M0-001 | 应用启动 | `GET /api/v1/ping` | 200，`{code:"OK",message:"success",data:{status:"UP"},traceId}` |
| TC-M0-002 | — | 请求带 `X-Trace-Id: abc12345` | 响应体 `traceId` 与响应头同值 `abc12345` |
| TC-M0-003 | — | 请求带 `X-Trace-Id: <非法 100 字符>` | 服务生成新 traceId，响应头为 26 位 ULID |
| TC-M0-004 | 无令牌 | `GET /api/v1/users/me` | 401，JSON 壳 `AUTH_UNAUTHORIZED`，非 HTML，含 traceId |
| TC-M0-005 | USER 令牌 | 调 `@PreAuthorize("hasRole('ADMIN')")` 接口 | 403，JSON 壳 `AUTH_FORBIDDEN` |
| TC-M0-006 | — | POST 合法路径 + 缺必填字段 | 400，`data.errors` 聚合全部字段错误 |
| TC-M0-007 | — | 请求体 JSON 语法错误 | 400 `COMMON_VALIDATION_ERROR`，不含类名/堆栈 |
| TC-M0-008 | — | 抛出未知 `RuntimeException` | 500 `COMMON_INTERNAL_ERROR`；日志有堆栈，响应无 |
| TC-M0-009 | — | 访问不存在的路径 | 404 `COMMON_NOT_FOUND` |
| TC-M0-010 | 10 条数据 | 偏移分页 `page=1&pageSize=5` | `list.size=5`、`total` 为数字、`hasNext=true`；末页 `hasNext=false` |
| TC-M0-011 | 25 条数据 | 游标分页连续翻页 | 无重复无遗漏；非法 `cursor` → 400；`nextCursor` 末页为 `null` |
| TC-M0-012 | — | 返回含 `id=9007199254740993` 的 VO | JSON 中 `"id":"9007199254740993"`，时间以 `Z` 结尾 |
| TC-M0-013 | 已写入配置 | 连续两次 `getString("site.name")` | 第二次命中 Caffeine（无 SQL）；`refresh` 后重新回表 |
| TC-M0-014 | Redis 清空 | 读同一配置三次 | 第三次命中 Redis；DB 只被查 1 次 |
| TC-M0-015 | 审计表不可写（模拟异常） | 业务事务内调用 `AuditRecorder` | 业务提交成功；日志 WARN；`wzblog_platform_audit_failed_total` +1 |
| TC-M0-016 | 未提供 `TokenVerifier` 实现 | 启动并访问受保护路径 | 启动成功，受保护路径恒 401；公开路径 200 |
| TC-M0-017 | — | `GET /actuator/health`、`/actuator/info`、`/actuator/prometheus` | 前两者 200 且无 details 泄露；prometheus 无令牌 401、ADMIN 令牌 200 |
| TC-M0-018 | — | 运行 ArchUnit | `api` 无 `infra` 依赖、`common/config/shared` 无 `modules` 依赖、模块无越界依赖，全部通过 |
| TC-M0-019 | 清库 | 连续两次启动应用 | `AdminBootstrap` 只插入一次；二次启动仅校正角色；无积分表写入 |

## 17. 待确认事项

| 事项 | 默认方案 | 需谁确认 |
|---|---|---|
| `TitleLookup` 方法名：M1 §13 曾写作 `findBrief(Long)`，本文为 `findById/findByIds` | **已解决**：端口仅含 `Optional<TitleBrief> findById(Long)` 与 `Map<Long,TitleBrief> findByIds(Collection<Long>)`，返回 `TitleBrief{id,name,cssClass}`；M1 已同步删除 `findBrief` | M1 / M4 |
| 审计与主事务关系：M7 §8 曾写「配置更新与审计同事务」 | **M0 为最终口径：`afterCommit` 独立事务，失败不影响主事务**——业务事务提交后由 `AuditRecorder` 以 `REQUIRES_NEW` 写入，异常只 `WARN` + 指标，永不抛出；M7 §6.1/§7.1/§8 同步为「配置更新事务 + afterCommit 审计」 | M7（已同步） |
| 401 细分码（缺令牌/无效/过期） | 统一 `AUTH_TOKEN_EXPIRED`；M1 若在 04 登记更细码，由 `TokenVerifier` 失败原因映射 | M1 / 04 |
| `/actuator/prometheus` 放行方式 | 应用层 `hasRole('ADMIN')`；生产按 08 用独立管理端口内网抓取，二者择一 | 08 |
| 游标是否签名 | 默认不签名（Base64URL + 结构校验），需要防爬时开 `wzblog.cursor.sign` | 架构 |
| 方法不支持（405）语义 | 归入 404 `COMMON_NOT_FOUND`，不引入未登记码 | 04 |
| 管理员引导是否强制改密 | 不在引导中强制；首次登录后由前端提示，后端不拦 | M1 / 运营 |
| 平台自定义指标是否只留 3 个 | 保留 `audit_failed`/`rate_limited`/`config_refresh`，业务指标由模块注册 | 架构 / 08 |