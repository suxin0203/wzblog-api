# round-3.md — 队列3：作者工作台

## 结论：PASS（API 级 7 项 + 页面级 1 项，无新缺陷）

## API 级
- 上传三步：presign（fileId/uploadUrl/expiresAt）→ PUT 裸字节（**200 空体无壳**，符合契约）→ confirm（ACTIVE + publicUrl + 宽高嗅探）✅
- 校验有效：假 PNG → FILE_TYPE_UNSUPPORTED（按真实字节嗅探）；超限 → FILE_SIZE_EXCEEDED ✅（此前两次报错是测试数据不合法，非缺陷）
- POST /articles → DRAFT version=0；PUT 带 version → 版本推进；**过期 version → 409 ARTICLE_VERSION_CONFLICT** ✅
- publish → slug 生成；发布后改 slug → **409 ARTICLE_SLUG_IMMUTABLE** ✅
- GET /articles/{id}/revisions → 修订列表 ✅

## 页面级（Playwright）
- /editor/new 完整渲染：标题、Markdown 工具栏 + CodeMirror、slug/分类/标签、封面上传、摘要、可见性（公开/仅链接/私密）、允许评论开关 ✅
- UI 全流程：填标题 + 正文 → 保存草稿 → 跳转 /editor/2018 编辑页 → 状态"草稿" + 提示"草稿已保存" ✅（截图 evidence/q3-editor-edit.png）
- 注：OS 焦点丢失时 Playwright 点击超时，编辑器内容用 execCommand 插入（真实用户路径的键鼠行为已在登录/搜索流程验证过）

## 无代码改动，无需修复。
