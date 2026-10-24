# M2 · 内容模块（文章 / 分类 / 标签 / Markdown / 搜索）

> 负责人 Agent：M2
> 依赖模块：M1（作者与角色，`UserQueryService#getBriefs(ids)` 批量补作者信息）、M5（文件引用 `claimByKey/releaseByKey`）；M0 仅公共壳（响应/异常/缓存），不依赖站点配置与敏感词
> 对应阶段：阶段 2
> 状态：已定稿
> 契约版本：[00-文档索引与约定](../00-文档索引与约定.md) 1.3（路由/枚举/权限/事件以该文为准）

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.modules.content`（controller / app / domain / mapper / infra） |
| 表 | `categories`、`tags`、`articles`、`article_tags`、`article_revisions`；只读 `files`（通过 M5，不直连） |
| 对外事件 | 发布：`ArticlePublishedEvent`、`ArticleDeletedEvent`；订阅：无 |
| 依赖 app 服务 | `UserQueryService#getBriefs(ids)`（M1，批量取作者昵称/头像）；`FileAppService#claimByKey(objectKey, ownerId, bizType)` / `#releaseByKey(objectKey)`（M5，同步、同事务；入参为对象 key） |
| 对外端口 | `ArticleQueryService`（M3/M6/M7，方法清单见 §16.1）；`ArticleCounterService#increment/#decrement`（M3，同事务计数，见 §16.2）；`ArticleStatsService#drainDailyViews(date)`（M7） |
| 缓存 | Redis：详情 `content:article:{id}:v{version}`；阅读去重 `content:view:*`；日阅读计数 `content:views:{yyyyMMdd}`；热门/归档短缓存 |

## 2. 范围与非目标

做：

- 分类、标签：公开查询 + `EDITOR` 后台 CRUD（物理删除，文章 `category_id` 由外键置 NULL）。
- 文章：创建/更新/软删、发布/下架、置顶/取消、回收站恢复、`ADMIN` 彻底删除。
- 修订历史（每篇保留 20 版）与回滚。
- Markdown 写入流水线：渲染、清洗、TOC、纯文本、字数/阅读时长、摘要、图片引用差量。
- 全文检索、信息流/归档/热门/置顶、阅读计数、作者文章列表、编辑详情。

不做：

- 评论、点赞/收藏、通知（M3）；`like_count/favorite_count/comment_count` 仅展示，由 M3 通过 §16.2 `ArticleCounterService` 维护，M2 不直接改。
- AI 摘要/标签（M6 的 `/articles/{id}/ai/*`）；M2 只提供只读文章读取方法。
- 媒体上传/确认/清理实现（M5）；M2 只登记引用与差量。
- 正文敏感词过滤、站点配置读取（M0/M3 职责）。

## 3. 路由清单（33 条）

> 与 00 第 4 节逐字一致；不得新增/改名；前缀 `/api/v1` 由框架统一添加。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/articles` | PUBLIC | 信息流，游标分页；筛 keyword/categoryId/tagId/year/month |
| GET | `/articles/search` | PUBLIC | 全文检索，单页 `size` |
| GET | `/articles/pinned` | PUBLIC | 置顶文章，独立接口，不混入信息流 |
| GET | `/articles/top` | PUBLIC | 热门榜 `type=view/like/favorite/comment&limit` |
| GET | `/articles/archive` | PUBLIC | 归档聚合（年/月计数） |
| GET | `/articles/{slug}` | OPTIONAL | 详情，按 slug；返回 html/toc，零渲染 |
| POST | `/articles/{id}/view` | OPTIONAL | 阅读 +1，Redis 去重 |
| GET | `/categories` | PUBLIC | 启用分类列表（含文章数） |
| GET | `/categories/{slug}` | PUBLIC | 分类详情 |
| GET | `/tags` | PUBLIC | 启用标签列表（含文章数） |
| GET | `/tags/{slug}` | PUBLIC | 标签详情 |
| GET | `/me/articles` | USER | 作者文章列表，偏移分页 |
| POST | `/articles` | EDITOR | 创建文章（DRAFT） |
| GET | `/articles/{id}/edit` | EDITOR | 编辑详情（含 contentMd/version） |
| PUT | `/articles/{id}` | EDITOR | 全量更新，乐观锁 + 修订 |
| DELETE | `/articles/{id}` | EDITOR | 软删进回收站 |
| POST | `/articles/{id}/publish` | EDITOR | 发布 / 重新发布 |
| POST | `/articles/{id}/unpublish` | EDITOR | 下架 → ARCHIVED |
| POST | `/articles/{id}/pin` | EDITOR | 置顶 |
| DELETE | `/articles/{id}/pin` | EDITOR | 取消置顶 |
| GET | `/articles/{id}/revisions` | EDITOR | 修订列表（不含 content_md） |
| POST | `/articles/{id}/revisions/{revisionNo}/restore` | EDITOR | 回滚到指定修订 |
| GET | `/admin/articles` | EDITOR | 后台列表（含回收站/状态/作者筛选） |
| POST | `/admin/articles/{id}/restore` | EDITOR | 从回收站恢复 |
| DELETE | `/admin/articles/{id}/purge` | ADMIN | 彻底删除（物理） |
| GET | `/admin/categories` | EDITOR | 后台分类（含 DISABLED） |
| POST | `/admin/categories` | EDITOR | 新建分类 |
| PUT | `/admin/categories/{id}` | EDITOR | 更新分类 |
| DELETE | `/admin/categories/{id}` | EDITOR | 删除分类（物理） |
| GET | `/admin/tags` | EDITOR | 后台标签（含 DISABLED） |
| POST | `/admin/tags` | EDITOR | 新建标签 |
| PUT | `/admin/tags/{id}` | EDITOR | 更新标签 |
| DELETE | `/admin/tags/{id}` | EDITOR | 删除标签（物理，级联 article_tags） |

> **路由匹配优先级**：字面路由优先于 `{slug}`；`/articles/search`、`/articles/pinned`、`/articles/top`、`/articles/archive` 是固定接口，不会被当作 slug 解析。`GET /articles/{id}/edit` 是两段路径（`{id}` 为文章 ID），与单段 `GET /articles/{slug}` 互不冲突。
> 用户可自行创建 slug 时仍拒绝保留字 `search/pinned/top/archive`（400 `COMMON_VALIDATION_ERROR`），避免与前端直链混淆。

## 4. DTO 契约

### 4.1 请求

| DTO | 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|---|
| `ArticleCreateReq` | title | string | 是 | 1~150 字符，去首尾空格 |
| | slug | string | 否 | `^[a-z0-9]+(?:-[a-z0-9]+)*$`，3~180；不传则自动生成 |
| | summary | string | 否 | ≤300 字符；空则自动摘要 |
| | coverKey | string | 否 | ≤255，必须是本站对象 key |
| | contentMd | string | 是 | UTF-8 字节 ≤204800（200 KB） |
| | categoryId | string | 否 | 存在的分类 ID；空串视为 null |
| | tagIds | string[] | 否 | ≤10 个，去重，均需存在 |
| | visibility | enum | 否 | `PUBLIC`（默认）/`PRIVATE`/`UNLISTED` |
| | allowComment | boolean | 否 | 默认 `true` |
| `ArticleUpdateReq` | 同创建 + version | int | 是 | 乐观锁版本；`slug` 仅 `DRAFT` 时可改 |
| | changeNote | string | 否 | ≤255，写入修订 |
| `PublishReq` | — | — | — | 无请求体（空 JSON `{}` 亦可） |
| `ListQuery` | keyword | string | 否 | ≤50，标题/摘要前缀匹配 |
| | categoryId / tagId | string | 否 | 存在的 ID |
| | year / month | int | 否 | year 2000~2100；month 1~12 且必须同时给 year |
| | cursor / size | string / int | 否 | size 1~50，默认 20；cursor 为不透明 Base64URL |
| `SearchQuery` | keyword | string | 是 | 1~50；<2 字走降级 |
| | categoryId / tagId | string | 否 | 同上 |
| | size | int | 否 | 1~20，默认 10；搜索不提供深翻页 |
| `ArchiveQuery` | year | int | 否 | 2000~2100；不传返回按年聚合 |
| `MeArticlesQuery` | status | enum | 否 | `DRAFT/PUBLISHED/ARCHIVED` |
| | page / pageSize | int | 否 | page≥1；pageSize 1~100，默认 20 |
| `AdminArticlesQuery` | page / pageSize | int | 否 | 同上；偏移分页仅后台 |
| | status / visibility | enum | 否 | 精确过滤 |
| | deleted | boolean | 否 | true 只看回收站，false 只看正常，缺省全部 |
| | keyword / categoryId / authorId | string | 否 | keyword ≤50 |
| | sort | enum | 否 | `updatedAt`（默认）/`createdAt`/`publishedAt` |
| `CategoryUpsertReq` | name | string | 是 | 1~50，唯一 |
| | slug | string | 是 | 同上正则，3~60，唯一 |
| | description | string | 否 | ≤255 |
| | sortOrder | int | 否 | 默认 0，升序 |
| | status | enum | 否 | `ENABLED`（默认）/`DISABLED` |
| `TagUpsertReq` | name / slug / status | string/enum | 是/是/否 | name 1~50、slug 3~60、默认 ENABLED |
| `RevisionRestoreReq` | changeNote | string | 否 | ≤255，默认 `restore from r{n}` |
| `ViewReq` | source | enum | 否 | `internal` / `external`；缺省按 Referer 判定 |

> 所有 ID 入参按字符串接收并校验为无符号整数；JSON 出参 ID 一律字符串。
> **媒体路径约定**：`coverKey` 传对象 key（不含域名/前缀，来源为 M5 上传确认返回的 `objectKey`）；Markdown 正文图片统一写相对路径 `![alt](/media/{objectKey})`，不写绝对域名。服务端提取引用时识别 `/media/` 前缀并去掉，得到对象 key 后交给 `FileAppService#claimByKey/releaseByKey`；渲染输出保留 `/media/{objectKey}`，由前端/网关按公开前缀拼接。

### 4.2 响应 VO

| VO | 字段 | 说明 |
|---|---|---|
| `ArticleListItemVO` | id,title,slug,summary,coverKey,category,tags,author,viewCount,likeCount,favoriteCount,commentCount,isPinned,readingSeconds,publishedAt | **不含 content_* / html / text** |
| `ArticleDetailVO` | 列表项字段 + html,toc,allowComment,visibility,createdAt,updatedAt | html 为库中清洗后结果；读取零渲染 |
| `ArticleEditVO` | 列表项字段 + contentMd,categoryId,tagIds,visibility,allowComment,status,version,publishedAt,deletedAt | 仅作者/EDITOR/ADMIN |
| `CategoryVO` / `TagVO` | id,name,slug,description(仅分类),sortOrder(仅分类),status,articleCount | 公开列表只含 ENABLED |
| `RevisionVO` | revisionNo,editor{id,nickname},title,summary,changeNote,createdAt | 列表不含 contentMd |
| `CursorPage<T>` | list,nextCursor,hasNext | 前台信息流 |
| `OffsetPage<T>` | list,total,page,pageSize,hasNext | 仅后台/我的文章 |
| `ViewResultVO` | counted,viewCount | 去重命中时 counted=false |

> **作者字段来源**：`ArticleListItemVO`/`ArticleDetailVO`/`ArticleEditVO` 的 `author`、`RevisionVO` 的 `editor` 一律在列表/详情装配阶段经 M1 `UserQueryService#getBriefs(ids)` 批量获取（一次调用覆盖整页），**禁止 JOIN `users` 表、禁止注入 M1 的 Mapper**；`getBriefs` 未命中的作者字段输出 `null`，不阻断列表。

```json
// POST /articles 201
{"code":"OK","message":"success","data":{"id":"1024","slug":"hello-world","status":"DRAFT","version":0,"publishedAt":null},"traceId":"0199a1b2c3"}
// GET /articles/hello-world 200（节选）
{"code":"OK","message":"success","data":{"id":"1024","title":"Hello","slug":"hello-world","html":"<h2 id=\"markdown\">Markdown</h2><p><img src=\"/media/articles/2026/10/demo.png\" alt=\"demo\"></p>","toc":[{"level":2,"text":"Markdown","anchor":"markdown"}],"readingSeconds":180,"category":{"id":"3","name":"后端开发","slug":"backend"},"tags":[{"id":"7","name":"Java","slug":"java"}],"author":{"id":"1","nickname":"wz","avatarKey":null},"allowComment":true,"publishedAt":"2026-10-24T04:00:00.000Z"},"traceId":"0199a1b2c3"}
```

## 5. 数据表

| 表 | 用途 | 关键字段/索引 |
|---|---|---|
| `categories` | 分类 | `uk_name`、`uk_slug`、`idx_status_sort`；物理删除，无 `deleted_at` |
| `tags` | 标签 | `uk_name`、`uk_slug`、`idx_status`；物理删除 |
| `articles` | 文章 | `uk_slug`、`idx_feed`、`idx_category`、`idx_author`、`idx_pinned`、`idx_deleted`、`ft_articles_search` |
| `article_tags` | 文章-标签 | PK(article_id,tag_id)、`idx_tag`；保存时全量替换 |
| `article_revisions` | 修订 | `uk_article_revision(article_id,revision_no)`、`idx_article_created`；保留 20 版 |
| `files` | 文件（只读） | 经 M5 按 `object_key` 解析；M2 不写 |

- `articles` 四轴独立：`status`（DRAFT/PUBLISHED/ARCHIVED）× `visibility`（PUBLIC/PRIVATE/UNLISTED）× `is_pinned` × `deleted_at`。
- `content_md` 为唯一事实源；`content_html/content_text/toc_json/word_count/reading_seconds` 只由写入流水线更新。
- `summary` 可手改；`published_at` 仅首次发布写入；`version` 每次正文更新 +1。
- 列表 SQL **禁止 SELECT `content_md/content_html/content_text`**；详情才取派生列。

## 6. 状态机与可见性

| 动作 | 前置 | 条件更新 | 结果 |
|---|---|---|---|
| 发布 | `deleted_at IS NULL`，status∈{DRAFT,ARCHIVED} | `SET status='PUBLISHED', published_at=COALESCE(published_at,NOW(3)), version=version+1 WHERE id=? AND status IN (...)` | 已是 PUBLISHED 时幂等 no-op（200） |
| 下架 | status=PUBLISHED | `SET status='ARCHIVED', is_pinned=0, version=version+1 WHERE status='PUBLISHED'` | 影响 0 行 → `ARTICLE_STATE_INVALID` |
| 置顶 | status=PUBLISHED 且未删 | `SET is_pinned=1` | 幂等；DRAFT/ARCHIVED → `ARTICLE_PIN_NOT_ALLOWED` |
| 取消置顶 | 任意未删 | `SET is_pinned=0` | 幂等 |
| 软删 | `deleted_at IS NULL` | `SET deleted_at=NOW(3), is_pinned=0` | 保留原 status；已删幂等 |
| 恢复 | `deleted_at IS NOT NULL` | `SET deleted_at=NULL` | 保留原 status，不恢复置顶 |
| 彻底删除 | 仅 ADMIN，且 `deleted_at IS NOT NULL` | `DELETE FROM articles WHERE id=?` | 级联 tags/revisions/comments/daily_stats |

可见性矩阵（`✓`=可见，`404`=按不存在处理，`管理侧`=EDITOR/ADMIN）：

| 状态 | 公开列表/搜索/置顶/归档 | 直链详情 | EDITOR/ADMIN | 后台列表 |
|---|---|---|---|---|
| DRAFT | ✗ | 404（管理侧 200） | ✓ | ✓ |
| PUBLISHED + PUBLIC | ✓ | ✓ | ✓ | ✓ |
| PUBLISHED + UNLISTED | ✗ | ✓ | ✓ | ✓ |
| PUBLISHED + PRIVATE | ✗ | 404（管理侧 200） | ✓ | ✓ |
| ARCHIVED | ✗ | 404（管理侧 200） | ✓ | ✓ |
| 任意 + `deleted_at≠NULL` | ✗ | 404（含管理侧） | 仅后台列表可见 | ✓（deleted=true） |

- slug：创建可传；未传则标题 ASCII 词转 kebab，失败用 `post-{8位随机}`；**PUBLISHED 后 PUT 带不同 slug → `ARTICLE_SLUG_IMMUTABLE`（409）**。
- 隐藏状态统一 404，避免泄露存在性；`PRIVATE/UNLISTED` 不参与搜索与推荐。
- 公开分类/标签过滤 `status='ENABLED'`；分类被禁用不影响文章访问。

## 7. Markdown 流水线（写入一次，读取零渲染）

```
校验 DTO → 生成/校验 slug → CommonMark(禁用 raw HTML) → HTML → OWASP Sanitizer
→ content_text / toc_json / word_count / reading_seconds / 自动摘要
→ 图片 key 新旧差量（`claimByKey/releaseByKey`，同事务）→ 保存 md+派生列 → 修订记录
→ 提交后清缓存（version 已变即自然失效）
```

- 扩展：表格、脚注、任务列表、自动链接、标题锚点。
- 禁用原始 HTML（`escapeHtml=true`），先转义再解析，杜绝 `onerror`/`<script>`。
- 清洗白名单：标签 `h1-h6,p,a,img,ul,ol,li,blockquote,pre,code,table,thead,tbody,tr,th,td,hr,strong,em,del`；属性仅 `href,src,alt,title,class`，`id` 只允许出现在 `h1~h6` 上（供 TOC 锚点，值必须匹配 `^[a-z0-9-]+$`，其他标签的 `id` 一律剥离）；协议 `http/https/mailto` 及相对路径；`img.src` 只允许 `/media/{objectKey}`（渲染时可带配置的公开访问前缀）；外链强制 `rel="noopener noreferrer"`。
- TOC：AST 阶段生成锚点（ASCII slug，空则 `heading-{序号}`），重复追加 `-2/-3`；`toc_json` 为 `[{level,text,anchor}]` 扁平数组。
- 纯文本：去标签、解码实体、折叠空白、保留段落 `\n`，写 `content_text`。
- 字数：CJK 字符数 + 拉丁词数；`reading_seconds = ceil(cjk/300 + words/200)`，最小 60。
- 摘要：用户未填时取 `content_text` 前 200 字符，在 120~200 区间最后一个 `。！？.!?` 处截断，否则硬截 200；≤300。
- 图片引用差：从 md 的 `![...](/media/{objectKey})` 提取 key 集合（识别并剥离 `/media/` 前缀，非该前缀的图片直接 422）+ `coverKey`；`new-old` 逐个 `claimByKey(objectKey, authorId, bizType)`，`old-new` 逐个 `releaseByKey(objectKey)`；**先 claim 后 release**，避免同文复用图片 ref_count 归零。
- 封面与正文差异：封面 bizType=`ARTICLE_COVER`，正文=`CONTENT_IMAGE`；软删 release 全部，恢复重新 claim；purge 时若已软删则不再 release。
- 读取：详情直接返回库中 `content_html` 与 `toc_json`，不解析 Markdown、不清洗、不重算；`img.src` 保持 `/media/{objectKey}` 相对路径。

## 8. 搜索与分页

- 信息流（游标 `(published_at,id)`，使用行构造器，已实测命中 `idx_feed`）：

```sql
WHERE status='PUBLISHED' AND visibility='PUBLIC' AND deleted_at IS NULL
  AND (published_at, id) < (?, ?)
ORDER BY published_at DESC, id DESC LIMIT ?;
```

- 全文检索：`MATCH(title,content_text) AGAINST (? IN BOOLEAN MODE)`，ngram；分类/年份走普通列，标签用 `EXISTS`：

```sql
AND EXISTS (SELECT 1 FROM article_tags at WHERE at.article_id=a.id AND at.tag_id=?)
```

- 排序：先按 `MATCH` 分数；应用层 `score = score + 3×标题命中次数`，再 `score/(1+days/365)` 时间衰减，最终 `published_at DESC` 兜底。
- 关键词 <2 字：降级 `title LIKE CONCAT('%',?,'%')`，响应 `degraded=true`（不报 400）。
- 游标：`Base64URL("{published_at_epoch_ms}:{id}")`；首页不传；非法游标 400 `COMMON_VALIDATION_ERROR`。
- offset 仅用于 `/admin/articles`、`/me/articles`；前台信息流/搜索不用 offset。
- 排序白名单：信息流固定 `published_at DESC,id DESC`；后台 `sort∈{updatedAt,createdAt,publishedAt}`，其余 400。
- 置顶单独接口 `/articles/pinned`，`LIMIT 10`；信息流不重复返回置顶（不混排）。

## 9. 阅读计数

- `POST /articles/{id}/view`：仅 `PUBLISHED` 且 `visibility∈{PUBLIC,UNLISTED}`，否则 404；`PRIVATE` 不计。
- 去重键：`content:view:{articleId}:{yyyyMMdd}:{visitor}`，业务日按 `Asia/Shanghai`，TTL 25 小时；`SET NX` 成功才计数。
- visitor：登录 `u{userId}`；游客 `h{sha256(ip+"|"+ua)前16位}`（IP 取 `X-Forwarded-For` 首段）。
- 来源：body `source` 优先；缺省按 Referer 主机 ∈ 本站域名 → `internal`，否则（含无 Referer）`external`。
- 计数原子更新，不使用 version：`UPDATE articles SET view_count=view_count+1, view_internal_count=view_internal_count+1 WHERE id=? AND deleted_at IS NULL`（external 时改加 `view_external_count`）；影响 0 行 → 404，并删除去重键允许重试。
- **日计数器（必须写）**：同一事务提交后，除更新 `articles` 累计列外，还要写 Redis 日计数：
  - `content:views:{yyyyMMdd}`：Hash，field=`articleId`，`HINCRBY 1`；
  - `content:views:source:{yyyyMMdd}`：Hash，field=`internal` / `external`，`HINCRBY 1`；
  - 两个 key 的 TTL 均为 **25 小时**（覆盖跨日补采），`yyyyMMdd` 按 `Asia/Shanghai`。
- 向 M7 暴露 `ArticleStatsService#drainDailyViews(LocalDate date)`：Lua 原子读取并删除当日两个 Hash，返回 `DailyViews{articleViews: Map<Long,Integer>, internal:int, external:int}`；重复调用返回空，保证 M7 聚合幂等。
- Redis 不可用：降级为直接计数（去重与日计数失效），打 WARN；DB 失败：删除去重键并返回 500。
- `article_daily_stats`：M2 **不写**，只维护 `articles` 累计列与上述 Redis 日计数；日统计由 M7 通过 `drainDailyViews` 回填（见 §16）。

## 10. 关键时序

| 场景 | 流程 |
|---|---|
| 发文 | 校验 → 生成 slug → 渲染流水线 → `INSERT articles(DRAFT)` + 全量 tags + 修订 #1 → `claimByKey` 图片 → 提交 → 201（不发事件、不发积分） |
| 更新 | 读文章 → 归属/可见性校验 → `version` 比对 → 渲染 → 图片差量 `claimByKey/releaseByKey` → `UPDATE ... version=version+1 WHERE version=?` → 插入修订 → 提交（缓存 key 天然换版本） |
| 发布 | 归属校验 → 条件更新 status/published_at → 提交 → `ArticlePublishedEvent`（仅 `visibility=PUBLIC` 首发发；AFTER_COMMIT，M4 发积分）→ 200 |
| 删除 | 条件置 `deleted_at`、清置顶 → 释放图片引用 → 提交 → `ArticleDeletedEvent`（AFTER_COMMIT，M4 按 `article_delete:{articleId}` 反转一次发布奖励） |
| 恢复 | 条件清 `deleted_at` → 重新 claim 图片 → 提交 → 200（不重发事件、不重新发发布奖励） |
| 彻底删除 | ADMIN 校验 → 要求已软删 → `DELETE`（级联）→ 写审计 → 提交（不额外发事件，软删时已发） |
| 搜索 | 解析关键词 → <2 字降级 → `MATCH` + EXISTS + 过滤 → 应用层加权/衰减 → `getBriefs` 批量补作者 → 单页返回 |
| 详情 | slug 查 `uk_slug` → 可见性判定 → 读缓存（miss 回源，单飞）→ `getBriefs` 批量补作者 → 返回 html/toc（零渲染） |

失败流：校验失败 400（`data.errors`）；无权限 403；不可见/不存在 404；slug/状态/版本冲突 409；图片 key 非法 422；以上均整体回滚。

## 11. 并发、事务、幂等

| 场景 | 事务边界 | 锁/条件更新 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| 更新正文 | 单事务（文章+tags+修订+文件引用） | `WHERE id=? AND version=?` | version | 影响 0 行 → `ARTICLE_VERSION_CONFLICT` |
| 发布 | 单事务 | `WHERE status IN ('DRAFT','ARCHIVED')` | `publish:{articleId}` | 已发布幂等返回 |
| 发文积分 | AFTER_COMMIT 事件 | 无 | `publish:{articleId}` | 监听器失败不影响主事务，M4 重试去重 |
| 删除扣回 | AFTER_COMMIT 事件 | `WHERE deleted_at IS NULL` | `article_delete:{articleId}` | 重复投递不重复扣 |
| slug 冲突 | 创建/更新事务 | `uk_slug` | — | 自动 slug 追加重试 ≤3 次（`-{4位随机}`）；用户指定冲突 → 409 |
| 修订号 | 与正文同事务 | `uk_article_revision(article_id,revision_no)` | 唯一键 | 冲突重试一次（重读 max+1）；保留 20 版由日任务清理 |
| 阅读计数 | 独立短事务 | `col=col+1` + Redis `HINCRBY` 日计数 | Redis 去重键 | 见 §9 |
| 文章计数（M3 调用） | 调用方事务（M3 单事务，`REQUIRED` 加入） | 单条原子 `UPDATE`；递减带 `WHERE col>0` | 由 M3 业务键保证 | 影响 0 行视为已到目标态，不抛异常（§16.2） |
| 缓存 | 提交后 | `content:article:{id}:v{version}` | version | 旧 key TTL 自然过期，不依赖删除 |

锁顺序：文章 → article_tags → article_revisions → files（claim/release）；禁止反向。

## 12. 权限与归属

| 操作 | 角色 | 归属校验 | 说明 |
|---|---|---|---|
| 公开列表/搜索/详情/分类/标签/阅读 | PUBLIC/OPTIONAL | — | 隐藏状态统一 404 |
| `/me/articles` | USER | 仅本人 `author_id` | 任意登录用户都可调用；普通 USER 通常没有文章（列表为空），不因此获得写权限 |
| 创建/更新/删除/发布/下架/置顶/修订/回滚 | EDITOR | ADMIN 全部；EDITOR 可编辑全部 | 路由门禁为 EDITOR（含 ADMIN）；USER 一律 403，不存在“USER 作者” |
| 后台文章列表/恢复 | EDITOR | 全部 | 含回收站 |
| 彻底删除 | ADMIN | 全部 | 必须已软删 |
| 分类/标签 CRUD | EDITOR | — | 物理删除 |
| PRIVATE | EDITOR/ADMIN | 直链仅管理侧可见 | 不进列表/搜索 |
| UNLISTED | 任何人持链接 | — | 不进列表/搜索，仅直链可读 |

## 13. 错误码

> 00 §5.3 要求先在接口契约登记；以下清单已登记到 04 §7.4，新增码必须先改 04。

| code | HTTP | 触发条件 |
|---|---|---|
| `ARTICLE_NOT_FOUND` | 404 | 不存在、已软删、或当前用户不可见 |
| `ARTICLE_FORBIDDEN` | 403 | 非 EDITOR/ADMIN 执行创建/编辑/发布等写操作 |
| `ARTICLE_SLUG_CONFLICT` | 409 | 用户指定 slug 已被占用 |
| `ARTICLE_SLUG_IMMUTABLE` | 409 | PUBLISHED 后尝试修改 slug |
| `ARTICLE_STATE_INVALID` | 409 | 下架非 PUBLISHED；恢复非回收站等 |
| `ARTICLE_PIN_NOT_ALLOWED` | 409 | 非 PUBLISHED 置顶 |
| `ARTICLE_VERSION_CONFLICT` | 409 | 乐观锁 version 不匹配 |
| `ARTICLE_REVISION_NOT_FOUND` | 404 | 修订号不存在 |
| `ARTICLE_IMAGE_INVALID` | 422 | 图片 key 不属于当前用户/不存在，或图片地址不是 `/media/{objectKey}` 形式 |
| `ARTICLE_PURGE_NOT_ALLOWED` | 409 | 未软删即彻底删除 |
| `CATEGORY_NOT_FOUND` | 404 | 分类不存在或已禁用（公开） |
| `CATEGORY_NAME_CONFLICT` | 409 | name 唯一冲突 |
| `CATEGORY_SLUG_CONFLICT` | 409 | slug 唯一冲突 |
| `TAG_NOT_FOUND` | 404 | 标签不存在 |
| `TAG_NAME_CONFLICT` | 409 | name 唯一冲突 |
| `TAG_SLUG_CONFLICT` | 409 | slug 唯一冲突 |
| `COMMON_VALIDATION_ERROR` | 400 | 字段/游标/分页参数非法（含正文超限、tagIds>10） |

## 14. 验收用例

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M2-001 | EDITOR 登录 | 创建最小文章 | 201，DRAFT，version=0，生成 slug，修订 #1 |
| TC-M2-002 | 已存在 slug | 创建时指定同 slug | 409 `ARTICLE_SLUG_CONFLICT` |
| TC-M2-003 | 标题为中文 | 创建不传 slug | slug 形如 `post-` + 8 位随机字符，合法 |
| TC-M2-004 | — | 正文 200KB+1 字节 | 400，`data.errors.contentMd` |
| TC-M2-005 | — | tagIds 11 个 | 400 |
| TC-M2-006 | DRAFT | 发布 | PUBLISHED，published_at 非空，发 `ArticlePublishedEvent` |
| TC-M2-007 | 已 PUBLISHED | 再次发布 | 200 幂等，published_at 不变，积分不重复 |
| TC-M2-008 | PUBLISHED+PUBLIC | 下架 | ARCHIVED，公开详情 404，信息流消失 |
| TC-M2-009 | ARCHIVED | 重新发布 | PUBLISHED，published_at 保持首次值 |
| TC-M2-010 | DRAFT | 置顶 | 409 `ARTICLE_PIN_NOT_ALLOWED` |
| TC-M2-011 | PUBLISHED | 置顶 | `/articles/pinned` 命中，信息流不重复出现 |
| TC-M2-012 | 已置顶 | 取消置顶两次 | 均 200，幂等 |
| TC-M2-013 | PUBLISHED+PRIVATE | 游客/他人直链 | 404；作者 200；不在列表/搜索 |
| TC-M2-014 | PUBLISHED+UNLISTED | 游客直链 | 200；列表/搜索不含 |
| TC-M2-015 | PUBLIC | GET 详情 | 含 html/toc/readingSeconds，无 contentMd/content_text |
| TC-M2-016 | 作者 | GET `/articles/{id}/edit` | 含 contentMd、version、tagIds |
| TC-M2-017 | 旧 version | PUT 更新 | 409 `ARTICLE_VERSION_CONFLICT` |
| TC-M2-018 | 正常更新 | 改正文并保存 | html/text/word_count 重算，revision_no+1 |
| TC-M2-019 | 连续保存 25 次 | 清理任务后查修订 | 仅保留最近 20 版，编号连续 |
| TC-M2-020 | 存在修订 1 | 回滚 | 内容=修订 1，重新渲染，生成新修订号，change_note 记录 |
| TC-M2-021 | PUBLISHED | PUT 修改 slug | 409 `ARTICLE_SLUG_IMMUTABLE` |
| TC-M2-022 | PUBLISHED | DELETE | 进回收站，公开 404，发 `ArticleDeletedEvent` 一次；M4 按 `article_delete:{articleId}` 反转一次发布奖励 |
| TC-M2-023 | 回收站文章 | 恢复 | 原 status 恢复，PUBLISHED 重新进列表；不重发 `ArticleDeletedEvent`/`ArticlePublishedEvent`，不重新发奖励 |
| TC-M2-024 | 回收站文章 | EDITOR 调 purge | 403 |
| TC-M2-025 | 未软删文章 | ADMIN 调 purge | 409 `ARTICLE_PURGE_NOT_ALLOWED` |
| TC-M2-026 | 回收站文章 | ADMIN purge | 200；articles/tags/revisions/comments/daily_stats 行级联消失；不额外发事件、不再触发积分反转 |
| TC-M2-027 | 两篇文章标题含关键词 | 搜索 | 标题命中者排前（权重 3），degraded=false |
| TC-M2-028 | 关键词 1 字 | 搜索 | 200，degraded=true，走 LIKE |
| TC-M2-029 | 100 篇同 published_at | 游标翻页 | 不重不漏，nextCursor 可继续 |
| TC-M2-030 | 正文含 `<script>`/`onerror`/`javascript:`/`data:image/svg+xml` | 保存后读详情 | 全部被清洗，无脚本/事件属性/危险协议 |
| TC-M2-031 | 正文含 2 张 `/media/{key}` 图，删除 1 张 | 保存 | 删除图对应 key `ref_count` 归零，保留图不变；换封面时旧 key release、新 key claim |
| TC-M2-032 | 同一游客同文同日 | 连点 view 3 次 | counted 仅 1 次，view_count +1；`content:views:{yyyyMMdd}` field=articleId +1，`content:views:source:{yyyyMMdd}` 对应 internal/external +1，TTL≈25h |
| TC-M2-033 | USER 角色 | POST /articles | 403；GET `/me/articles` 200（通常空列表），不能编辑任何文章 |
| TC-M2-034 | 已有 slug 为 `search` 的文章 | GET `/articles/search?keyword=x` | 命中字面路由执行检索，不被解析为 slug；`/articles/{id}/edit` 仍为两段路径 |
| TC-M2-035 | 正文 `<h2 id="ok-anchor">A</h2><h2 id="Bad_ID">B</h2><p id="x">C</p>` | 保存后读 html | 仅 `ok-anchor` 保留；非法值与非标题 `id` 被剥离；toc 锚点与 html `id` 一致 |
| TC-M2-036 | 正文含 `![x](/media/goods/2026/a.png)` 与 `![y](https://evil.example/b.png)` | 保存 | 前者提取 key=`goods/2026/a.png` 并 claim；后者 422 `ARTICLE_IMAGE_INVALID` |
| TC-M2-037 | M7 调 `drainDailyViews(当日)` 两次 | 聚合日阅读 | 第一次返回当日计数并清空 Hash；第二次返回空，不重复入库 |
| TC-M2-038 | 文章 `like_count=0` | M3 同事务调 `increment(LIKE,1)` 后再调 `decrement(LIKE,1)` 两次 | 依次为 1、0、0；第二次递减影响 0 行且不抛异常；全过程无负数 |
| TC-M2-039 | 列表 20 篇文章、作者不同 | GET `/articles` | `author` 全部填充，`UserQueryService#getBriefs` 仅调用 1 次；SQL 审计无 `JOIN users` |

## 15. 性能与索引

| 查询 | 索引 | EXPLAIN 预期 | 验收 |
|---|---|---|---|
| 信息流游标 | `idx_feed` | `type=range, key=idx_feed`，无 filesort | 20 万行翻 100 页耗时平稳 |
| 分类页 | `idx_category` | `type=ref, key=idx_category`，反向扫 published_at | 无临时表 |
| 标签页 | `article_tags.idx_tag` + `articles.PRIMARY` | 两步：覆盖索引取 id → 批量回表 | 无 JOIN 大排序 |
| 详情 | `uk_slug` | `type=const, key=uk_slug` | 单行 |
| 搜索 | `ft_articles_search` | `type=fulltext, key=ft_articles_search` | P95 < 200ms |
| 我的文章 | `idx_author` | `type=ref, key=idx_author`，updated_at 排序 | 无 filesort |
| 归档 | `idx_published` | `type=range` | 年/月聚合走范围扫 |
| 后台回收站 | `idx_deleted` | `type=ref/range` | 分页稳定 |

- 列表 VO 只选白名单列，禁止 `content_*`；详情缓存 TTL 10 分钟 ±10% 抖动，空值缓存 60 秒。
- 热门榜无计数索引，按计数列 `ORDER BY ... LIMIT 10` + Redis 缓存 5 分钟；当前不引入 ZSet（§17 #10），后续量级增长由 M7 只读聚合承接。

## 16. 事件与跨模块

| 事件/调用 | 方向 | 载荷/签名 | 消费方 | 幂等 |
|---|---|---|---|---|
| `ArticlePublishedEvent` | M2 → M4 | articleId, authorId, title, publishedAt | M4 发发文积分（仅 `PUBLIC` 首发发事件） | `publish:{articleId}` |
| `ArticleDeletedEvent` | M2 → M4 | articleId, authorId | M4 反转发布奖励（**仅软删时发一次**） | `article_delete:{articleId}` |
| `FileAppService#claimByKey(objectKey, ownerId, bizType)` / `#releaseByKey(objectKey)` | M2 → M5 | 对象 key、ownerId、bizType | 发文/更新/删除/恢复 | 同事务同步，按 key 幂等 |
| `ArticleStatsService#drainDailyViews(date)` | M2 → M7 | `DailyViews {articleViews, internal, external}` | 看板日阅读回填 | 读后即删，重复调用为空 |
| `ArticleCounterService#increment/#decrement` | M3 → M2 | articleId、`CounterType`（`LIKE/FAVORITE/COMMENT`）、delta | 文章三计数同事务维护 | 原子 UPDATE；0 行=已到目标态 |

### 16.1 对外只读接口 `ArticleQueryService`（方法清单固定）

| 方法 | 调用方 | 返回/用途 |
|---|---|---|
| `getBrief(id)` | M3/M6/M7 | 单篇只读摘要：id、authorId、title、slug、status、visibility、allowComment、deleted |
| `getBriefs(ids)` | M3/M6 | 批量摘要（≤50），保持入参顺序；缺失项跳过 |
| `getPublicBySlug(slug)` | M6（AI 工具） | 仅 `PUBLISHED+PUBLIC`，含 `contentText`（截断 6000） |
| `searchPublic(query, limit)` | M6（AI 工具） | 公开检索，`limit` ≤20 |
| `getHotPublic(period, limit)` | M6/M7 | 热门榜只读口径 |
| `listCategories()` | M6/M7 | 启用分类及文章数 |
| `listTags(limit)` | M6/M7 | 启用标签及文章数 |
| `getSiteOverview()` | M7 | 站点聚合概览（文章数/阅读总数等只读口径） |
| `getReactionTarget(id)` | M3 | 可交互目标：authorId、status、visibility、allowComment、deleted；不可交互返回空 |

- `ArticleQueryService` 的 9 个只读方法与 `ArticleCounterService` 的 2 个计数方法，是 M2 对其他模块开放的全部同步能力，不得新增未登记方法；其他交叉查询一律走事件。
- 事件一律 `@TransactionalEventListener(AFTER_COMMIT)`；监听器异常不影响主事务。
- 软删文章发 `ArticleDeletedEvent`，M4 按 `article_delete:{articleId}` 反转一次发布奖励；恢复不重新发奖励；彻底删除不额外发事件。
- **M2 不写 `article_daily_stats`（也不写 `daily_stats`）**：只维护 `articles` 累计列与 §9 的两个 Redis 日计数器；日统计由 M7 经 `ArticleStatsService#drainDailyViews(date)` 聚合后落库（M7 §6.4/§6.5）。
- M7 统计口径：文章发布数取 `articles.published_at` 落在统计日的行（排除软删）；阅读日增量取 §9 的两个 Redis 日计数器，经 `drainDailyViews` 聚合，不直接对 `view_count` 求差。

### 16.2 计数端口 `ArticleCounterService`（M3 维护文章三计数的唯一写入口）

| 项 | 约定 |
|---|---|
| 方法 | `int increment(Long articleId, CounterType type, int delta)`；`int decrement(Long articleId, CounterType type, int delta)` |
| `CounterType` | 仅 `LIKE` / `FAVORITE` / `COMMENT`，分别映射 `articles.like_count` / `favorite_count` / `comment_count`；它是端口参数（不落库、不进 00 §6.1 业务枚举），列名映射在内部白名单完成，不做字符串拼接 |
| 语义 | `increment`：`UPDATE articles SET {col}={col}+:delta WHERE id=? AND deleted_at IS NULL`；`decrement`：`UPDATE articles SET {col}=GREATEST({col}-:delta,0) WHERE id=? AND deleted_at IS NULL AND {col}>0`；两者均为**单条原子 UPDATE**，不「先查后写」 |
| 0 行 | 递减影响 0 行（`{col}` 已为 0 或文章已删）→ 视为已是 0，返回 0，不抛异常；调用方按 0 值处理即可，不回滚互动明细 |
| 返回 | 变更后的计数值；`delta=0` 为纯读当前值（供 M3 对账求差值），不写库 |
| 事务 | 方法本身 `@Transactional(REQUIRED)`，**加入调用方事务**（M3 单事务），与 `article_reactions`/`comments` 明细同提交、同回滚；不开独立事务、不发事件、不写审计 |
| 校验 | 不做状态/归属/可见性校验（由 M3 在调用前用 `getReactionTarget`/`getBrief` 完成）；文章已软删时 `increment` 影响 0 行，同样不抛异常 |

## 17. 待确认事项与契约问题（默认方案）

| # | 事项 | 默认方案 | 状态 |
|---|---|---|---|
| 1 | 写接口门禁为 EDITOR，`/me/articles` 对 USER 开放 | 创建/编辑/发布仅 EDITOR（含 ADMIN）；USER 只能 `GET /me/articles` 看自己的（通常为空），无“USER 作者”路径 | 已解决 |
| 2 | 图片引用用 object_key 还是完整 URL | Markdown 统一相对路径 `/media/{objectKey}`；提取时识别 `/media/` 前缀；渲染时拼公开前缀 | 已解决 |
| 3 | M5 按 key 还是 fileId claim | `FileAppService#claimByKey(objectKey, ownerId, bizType)` / `#releaseByKey(objectKey)`；不再需要 `findIdByObjectKey` | 已解决 |
| 4 | `ArticleDeletedEvent` 在软删还是 purge 时发 | 软删即发（M4 反转一次）；恢复不重新发奖励；purge 不额外发 | 已解决 |
| 5 | PRIVATE/UNLISTED 发布是否发积分 | 已解决：仅 `visibility=PUBLIC` 首发发 `ArticlePublishedEvent`；改可见性或 PRIVATE/UNLISTED 不补发，M4 以 `publish:{articleId}` 幂等 | 已解决 |
| 6 | M7 如何得到日阅读增量 | M2 写 `content:views:{yyyyMMdd}` 与 `content:views:source:{yyyyMMdd}`（TTL 25h），M7 经 `ArticleStatsService#drainDailyViews(date)` 读取并清空 | 已解决 |
| 7 | 清洗白名单与 TOC 锚点 | `id` 仅允许 `h1~h6`，值匹配 `^[a-z0-9-]+$`；其余标签剥离 | 已解决 |
| 8 | 游标写法 | 使用行构造器 `(published_at, id) < (?, ?)`，已实测命中 `idx_feed` | 已解决 |
| 9 | 自动摘要长度、TOC 空标题锚点格式 | 已定：摘要 120~200 区间句末截断（上限 300）；空标题锚点 `heading-{序号}` | 已解决 |
| 10 | 热门榜数据来源 | 已定：直接按计数列排序 + Redis 缓存 5 分钟；不引入 ZSet，M7 只做只读聚合 | 已解决 |
| 11 | 详情路由与字面路由冲突 | 字面路由优先；`/articles/{id}/edit` 为两段路径；创建时拒绝保留 slug `search,pinned,top,archive` | 已解决 |
| 12 | 文章三计数的写入口归属 | 已解决：M3 不直接写 `articles`，统一调 §16.2 `ArticleCounterService#increment/decrement`（同事务） | 已解决 |

> 第 13 节错误码已逐条登记到 04 §7.4，本文件不再保留“待登记”状态。