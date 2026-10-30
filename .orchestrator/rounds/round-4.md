# round-4.md — 队列4：互动（评论树/点赞收藏/留言）

## 结论：PASS（API 级 10 项 + 页面级 3 项；B-1、B-3 均判定为修后端并已修复）

## 裁决执行
- **B-1（必须修）**：AuthorVO.userId 加 ToStringSerializer，评论/留言/通知三处 userId 变字符串。集成测试逐处断言 isTextual；前端已用 String() 归一化比较（CommentSection.vue:446），无需改前端。
- **B-3（判定：修后端）**：评论创建响应的 author 是"数据本可完整但未组装"——登录用户发评论 author 退化为匿名壳、replyToUser 显示"已注销用户"。修后端：create 时 loadUsers 组装完整作者。契约空壳语义不适用于此（后端本就有全部数据）。

## API 级
- 评论树（root/children/parentId 回复/replyToUser）、审核流（PENDING→APPROVED）、删除隐藏语义（软删根评论 like→404、reply→COMMENT_PARENT_INVALID 均正确）✅
- 反应：PUT/DELETE /articles/{id}/reactions/{type}（幂等）+ /reactions/me 聚合 ✅（注意动词是 PUT 不是 POST，首次用错是我方问题）
- 评论点赞：POST /comments/{id}/like（契约即 POST）✅
- 留言：创建/审核/公开列表/作者字段 ✅
- 修复后实测：评论 author.userId="1003"(str)、留言 "str"、通知 actor "str"、replyToUser 完整昵称 ✅
- 全量回归 125/125 绿（含新增 commentAuthorIdIsStringAndCreateReturnsFullAuthor）

## 页面级
- 文章详情评论流：输入→提交→"评论已提交，待审核通过后展示"+本人可见"审核中"标记 ✅
- 留言板页：输入框/游客与登录作者渲染/审核提示 ✅

## 备注
- types/comment.ts 等处的"线上是 number"注释已过时（后端已改 string），功能无影响，留给下次前端文档性整理。
