# round-1.md — 队列1：公开阅读链路 + 登录注册

## 结论：PASS（API 级 16 项 + 页面级 9 项，1 项后端缺陷已修复）

## API 级（vs openapi.yaml v1.6.3 + contract-samples）
- GET /articles?size=3 / pinned / top / archive / detail(by slug) / search / categoryId / tagId → 200 OK，包络正确，ID 全 string ✅
- 搜索 keyword=Java/MySQL 空列表与样例 04 行为一致 ✅
- 404 ARTICLE_NOT_FOUND（隐藏资源语义）✅
- GET categories / tags / banners / friend-links / public/configs → 字段结构与样例一致 ✅
- POST /auth/login ×3 账号 → TokenPairVO 5 字段 ✅
- POST /auth/register → UserSelfVO；非法请求 400 + data.errors 映射（e3 一致）✅
- 注册限流/验证码：AUTH_CAPTCHA_INVALID 生效（本机重启后验证码默认开启；此前运行实例为 shell 带 WZBLOG_CAPTCHA_ENABLED=false 启动，非缺陷）

## 缺陷修复（后端，modules/interaction）
- **SSE 通知流错误序列化**：`GET /notifications/stream` 抛 NOTIFY_STREAM_LIMITED 时，因请求 `Accept: text/event-stream` 导致全局异常处理器内容协商失败 → 实际返回 **500 空体**（契约应为 429 + JSON 包络）。修复：NotificationController 同步捕获 BusinessException 直接写 JSON（含 Retry-After）。新增集成测试 `sseStreamLimitReturns429JsonEvenWithEventStreamAccept`。InteractionIntegrationTest 20/20 通过。

## 页面级（Playwright，证据在 evidence/）
- 首页：信息流/置顶/热榜/分类/标签/轮播图 ✅；401→静默刷新→重放成功（users/me、unread-count、stream 均重放 200）✅
- 文章详情：正文/点赞收藏评论数/评论列表/目录 ✅ 无控制台错误
- 文章列表：分类过滤（置顶不进信息流语义正确）/标签下拉/关键词 ✅
- 搜索：头部搜索框回车 → /articles?keyword=MySQL ✅；hero 搜索框逻辑经 DOM 事件派发验证可跳转 ✅
- 归档页：年月分组 ✅；登录页：验证码 UI + 完整登录流程（读图输入 PW39 成功）✅；注册页渲染 ✅

## 环境注意
- 调试 Chrome 无 OS 焦点时 CDP 键盘/鼠标事件被丢弃（交互超时）——环境限制非前端缺陷；降级用 DOM 事件派发验证。

## 遗留观察（不阻塞）
- SSE 每用户 3 连接：客户端 abort 后连接在服务端滞留至超时/写失败才摘除，期间重连可能 429；前端有指数退避（1s→30s）可自愈。已记 pending-decisions（前端可考虑 429 时读 Retry-After，小改进）。
