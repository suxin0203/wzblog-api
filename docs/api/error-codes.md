# 错误码清单（T-014 校验口径）

口径（D70/D74）：必填校验与防爆硬上限由 Bean Validation 统一返回 `COMMON_VALIDATION_ERROR` + `data.errors`；
业务上限由领域层抛字段业务码。主 API 统一字符串 code；legacy 兼容路由不在此表范围。

| 字段 | 业务上限 | 业务码 | DTO 硬上限 | 超硬上限码 |
| --- | --- | --- | --- | --- |
| AI 提问 `message` | 600 字符 | `AI_MESSAGE_TOO_LONG` | 6000 | `COMMON_VALIDATION_ERROR` |
| 留言 `content` | 1000 字符 | `GUESTBOOK_CONTENT_TOO_LONG` | 10000 | `COMMON_VALIDATION_ERROR` |
| 评论 `content` | 2000 字符 | `COMMENT_CONTENT_TOO_LONG` | 20000 | `COMMON_VALIDATION_ERROR` |
| 留言昵称 `nickname` | 30 字符 | 无（`COMMON_VALIDATION_ERROR` + errors.nickname） | 30 | `COMMON_VALIDATION_ERROR` |
| 评论游客昵称 `guestNickname` | 30 字符 | 无（`COMMON_VALIDATION_ERROR` + errors.guestNickname） | 30 | `COMMON_VALIDATION_ERROR` |
| 用户昵称 `nickname`（资料/注册/后台） | 30 字符 | 无（`COMMON_VALIDATION_ERROR` + errors.nickname） | 30 | `COMMON_VALIDATION_ERROR` |
| 称号名 `name` | 50 字符 | 无（`COMMON_VALIDATION_ERROR`；重名 `TITLE_NAME_CONFLICT`） | 50 | `COMMON_VALIDATION_ERROR` |
| 调分金额 `amount` | ±100000 | `POINTS_ADJUST_INVALID` | ±100000 | `COMMON_VALIDATION_ERROR` |
| 调分原因 `reason` | 255 字符（列宽） | 无（`COMMON_VALIDATION_ERROR` + errors.reason） | 255 | `COMMON_VALIDATION_ERROR` |

补充：
- 必填（`@NotBlank`/`@NotNull`）缺失或空串 → `COMMON_VALIDATION_ERROR` + `data.errors`。
- `MethodArgumentNotValid` / `BindException` / `ConstraintViolationException` 三条路径响应同构（400 + errors）。
- 三字段超过硬上限时由 Bean Validation 拦截，不进入业务层。
