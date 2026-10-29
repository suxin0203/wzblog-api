# round-6.md — 队列6：通知（轮询 + SSE）

## 结论：PASS（API 级 7 项 + 页面级 2 项；核心修复在队列1已完成）

## API 级
- SSE Last-Event-ID 续传：带 Last-Event-ID: 4622 连接 → 按序回放 4623/4624（event:notification + id 帧）→ ready 帧带 unreadCount ✅
- 非法 Last-Event-ID 不 500（代码按无续传处理）✅（代码走查）
- unread-count / read{ids}（updated+unreadCount）/ read-all（含跨页 9 条）→ 计数一致 ✅
- 每用户 3 连接上限：超限 → **429 NOTIFY_STREAM_LIMITED + JSON 包络 + Retry-After**（队列1 修复并带集成测试）✅
- 401 后 stream 自动刷新重放（队列1 浏览器实测）✅
- 通知 actor.userId 字符串（B-1）✅

## 页面级
- /notifications 消息中心：计数、全部已读按钮禁用态、通知列表 ✅
- 顶栏铃铛未读角标随 SSE ready/unread-count 更新（队列1 观测）✅

## 无新缺陷。
