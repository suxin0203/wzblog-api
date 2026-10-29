# round-2.md — 队列2：用户资料/会话管理

## 结论：PASS（API 级 9 项 + 页面级 3 项，1 项后端缺陷已修复）

## API 级
- GET /users/me → UserSelfVO 全字段 ✅；GET /users/{id}/profile 公开白名单字段（8 字段、无 email/status）✅
- GET /users/me/sessions → 列表（sessionId/ip/ua/时间）✅
- POST /auth/refresh 轮换：新 token 对 ✅；10s 同 UA 宽限窗内复用返回幂等同对（并发竞态保护）✅；**宽限后复用 → 401 AUTH_REFRESH_REUSED** ✅
- 401 语义：过期 token → AUTH_TOKEN_EXPIRED（触发前端静默刷新）；无效 token → AUTH_TOKEN_INVALID（不刷新）——用自签过期 JWT 实测 ✅
- PUT /users/me 更新生效 ✅

## 缺陷修复（后端，modules/identity）
- **PUT /users/me 昵称误必填**：契约 UpdateProfileRequest.required 为空（nickname minLength 0），后端 @NotBlank 导致仅改 bio 时 400"昵称不能为空"。修复：DTO 去 @NotBlank，服务层空昵称保持原值（DB 非空约束、展示层依赖昵称，不落空昵称——语义选择已登记 pending-decisions）。新增测试 updateProfileAllowsMissingNicknamePerContract，IdentityIntegrationTest 20/20 绿。

## 页面级（Playwright）
- /account 个人中心：头像上传入口、基本资料表单（昵称/简介字数）、修改密码 tab、设备会话 tab（完整会话列表 + 单会话"下线" + "下线其他全部设备"含警示文案）✅
- 401→静默刷新单飞：队列1 已实测（3 并行 401 → 1 次 refresh → 全部重放成功）✅
- token 注入方式：直接写 localStorage wzblog:token（真实登录流程队列1 已验证）

## 备注
- PUT /users/me 为全量更新语义：缺省 avatarKey 会清空头像（契约允许，前端表单全字段提交，不阻塞）
- 登录验证码开启后，curl 级测试用 Redis 覆写 captcha hash 的方式获取 token（等价真实数据，不走 OCR）
