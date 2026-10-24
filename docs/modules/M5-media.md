# M5 · 媒体模块（media）

> 负责人 Agent：M5 负责人
> 依赖模块：M0（配置、限流、ShedLock/异步执行器、审计、`CurrentUser`）；**不依赖 M1**——uploaderId/角色从 M0 的当前用户上下文获取，避免与 M1 的头像调用形成环。
> 对应阶段：阶段 5；但 M2 依赖本模块，**预签名、确认、claimByKey/releaseByKey、URL 四项基础能力必须在 M2 开工前完成**
> 状态：已定稿
> 契约版本：以 [00-文档索引与约定](../00-文档索引与约定.md) 1.3 为准；路由与枚举逐字取自 00 第 4、6 节

## 1. 模块信息

| 项 | 值 |
|---|---|
| 包路径 | `com.wzblog.modules.media` |
| 表与存储 | `files`（含 `thumb_key`；定义见 [03-数据库设计 3.5.1](../03-数据库设计.md) 与 DDL）；MinIO 桶 `wzblog-public` / `wzblog-private` |
| 对外事件 | 发布：无；订阅：无 |
| 对外 app 服务 | `FileAppService#claimByKey(objectKey, ownerId, bizType)` / `#releaseByKey(objectKey)`（同步调用，均返回 fileId；00 §9.2） |
| 被谁依赖 | M2（封面/正文图）、M1（头像）、M7（轮播）、M4（商品图，见 13） |
| 关键约束 | 大文件字节不经过 JVM；JVM 仅在 confirm 时流式读取 ≤5MB 做校验 |

## 2. 范围与非目标

- 做：预签名 PUT 直传；≤5MB 小文件 multipart 兜底；确认入库；短期访问 URL；删除；管理员列表与孤儿清理；魔数/解码/像素校验；320px 缩略图（key 落 `thumb_key`）；sha256 去重（命中返回既有 canonical fileId/objectKey）；引用计数。
- 不做：**不代理大文件上传**（>5MB 只走预签名，服务端不落临时文件、不做分片合并）；**不做视频**（无 `VIDEO` 枚举）；**不做 CDN**；不做 URL 抓图（SSRF）；不做图片编辑/水印；不新增路由、枚举、表或字段。

## 3. 路由清单

> 与 00 文档第 4 节逐字一致，共 7 条；不得新增/改名/移动。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/files/presign` | EDITOR | 生成 object key 与 TEMP 元数据，返回 5 分钟 PUT 直传地址 |
| POST | `/files/upload` | USER | multipart 兜底（≤5MB），直接返回 ACTIVE 文件 |
| POST | `/files/{id}/confirm` | USER | 确认直传对象，校验后转 ACTIVE；幂等 |
| GET | `/files/{id}/url` | OPTIONAL | 公开读文件返回公开 URL；未确认文件（及未来私有桶）返回短期签名 URL |
| DELETE | `/files/{id}` | OWNER | 删除无引用文件（软删 + 删对象），ADMIN 可越过归属 |
| GET | `/admin/files` | ADMIN | 文件列表，按状态/上传者分页 |
| POST | `/admin/files/cleanup` | ADMIN | 触发清理，支持 dryRun |

## 4. DTO 契约

### 4.1 请求

| DTO / 路由 | 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|---|
| `PresignReq` | filename / contentType | string | 是 | 文件名 1~255，扩展名 ∈ `jpg/jpeg/png/webp`，不含 `/`、`\`、`..`、控制字符；contentType ∈ `image/jpeg`、`image/png`、`image/webp` 且与扩展名一致（仅预检，事实以魔数为准） |
| | size / bizType | long / enum `FileBizType` | 是 | size 1~5 242 880（5MB），须与 confirm 时对象实际大小相等；bizType 见 13 的角色×用途矩阵 |
| `UploadReq` `/files/upload` | file / bizType | MultipartFile / enum | 是 | 非空、≤5MB、扩展名白名单、魔数+解码校验；bizType 同上 |
| `ConfirmReq` | id（路径，confirm/delete 共用） | string(BIGINT) | 是 | 正整数；**请求体为空**，不接收 objectKey/sha256 |
| `UrlReq` | id（路径） / expires | string / int（秒） | 是 / 否 | id 正整数；expires 60~3600，默认 300，仅对签名 URL 生效 |
| `AdminFileQuery` | status / uploader | enum `FileStatus` / string(BIGINT) | 否 | status 不传=全部（含 DELETED）；uploader 为正整数 |
| | page / pageSize | int | 否 | 默认 1 / 20，pageSize 最大 100（00 §5.4） |
| `CleanupReq` | dryRun | boolean | 否 | 默认 `false`；`true` 只统计不写库不删对象 |

- ID 以字符串接收后转 `long`；非数字或 ≤0 → `COMMON_VALIDATION_ERROR`。客户端提交的 objectKey/bucket/sha256/status/refCount 一律**忽略**；未知枚举值 → `COMMON_VALIDATION_ERROR`。

### 4.2 响应

| VO | 字段 | 类型 | 说明 |
|---|---|---|---|
| `PresignVO` | fileId | string | `files.id`，字符串输出 |
| | objectKey | string | 服务端生成，客户端原样使用，不得改写路径 |
| | uploadUrl / expiresAt | string | 预签名 PUT，绑定 bucket+key+TTL 300s；expiresAt 为 ISO-8601 UTC（签发 +300s） |
| `FileVO` | fileId / objectKey / status / createdAt | string | status ∈ TEMP/ACTIVE/ORPHAN/DELETED；createdAt 为 ISO-8601 UTC |
| | mimeType / sizeBytes / width / height | string/long/int/int | 服务端探测/解码结果，非客户端声明 |
| | publicUrl | string? | 公开桶且对象可读时返回，否则 `null` |
| | thumbUrl | string? | `files.thumb_key` 的公开 URL；异步生成前 `thumb_key` 为 NULL，前端回退 publicUrl |
| `UrlVO` | fileId / url / thumbUrl | string | url 为公开 URL 或签名 GET URL |
| | expiresAt | string? | 公开 URL 为 `null`；签名 URL 为过期时间 |
| `AdminFileVO` | FileVO 全部字段 + originalName / sha256 / uploaderId / bizType / refCount / thumbKey / deletedAt | string? | sha256 小写 hex；refCount 为当前引用数；thumbKey 未生成时为 NULL |
| `PageVO<AdminFileVO>` | list/total/page/pageSize/hasNext | — | 00 §5.4 偏移分页；list 永不为 null |
| `CleanupVO` | dryRun / startedAt / finishedAt / tempScanned / tempDeleted / orphanMarked / orphanDeleted / objectDeleteFailed | boolean/string/int | UTC 时间；其余为单次执行计数 |

## 5. 数据表与生命周期

### 5.1 关键字段用途（字段定义见 03 §3.5.1）

| 字段 | 本模块约定 |
|---|---|
| `bucket` / `object_key` | 桶由 bizType 决定；key 服务端生成（6.2），一次上传只有一个 canonical key；`uk_object` 防重 |
| `sha256` / `mime_type` / `size_bytes` | 流式计算与探测，不采信请求头；confirm 时用 statObject 实际值覆盖声明值 |
| `width` / `height` | EXIF 方向修正后的可视尺寸；非图片为 NULL |
| `thumb_key` | 缩略图对象 key（`{biz}/{yyyy}/{MM}/{uuid}_thumb.jpg`），异步生成后写入；未生成/生成失败为 NULL，读取以该列为准，不按主 key 推导 |
| `uploader_id` / `biz_type` | 归属校验、角色×用途矩阵、桶与 key 前缀均由此推导 |
| `status` / `ref_count` | 状态机见 5.2；ref_count 仅由 `claimByKey`/`releaseByKey` 原子增减（`ck_files_ref_count >= 0`） |

### 5.2 状态机

| 当前 | 事件 | 下一状态 | 触发者 |
|---|---|---|---|
| — | presign 落库 | TEMP | `/files/presign` |
| TEMP | confirm 通过 | ACTIVE | `/files/{id}/confirm` |
| TEMP | 超 24h / 签名失败 / 去重落选 | DELETED | 确认接口或清理任务 |
| ACTIVE / ORPHAN | `claimByKey` | ACTIVE（ref_count+1） | M1/M2/M4/M7 |
| ACTIVE | `ref_count=0` 且 `updated_at < now-7d` | ORPHAN | 清理任务 |
| ACTIVE/ORPHAN | OWNER 删除（ref_count=0）/ ADMIN 越过归属（仍要求 0） | DELETED | 删除接口 |
| ORPHAN | `ref_count=0` 且 `updated_at < now-7d` | DELETED | 清理任务 |
| DELETED | — | 终态 | 不复活，不复用 key |

### 5.3 软删与对象删除顺序（冻结）

1. 事务内条件更新：`status='DELETED', deleted_at=UTC_TIMESTAMP(3)`，条件 `id=? AND status IN ('ACTIVE','ORPHAN') AND ref_count=0`；影响行数 0 按 13 处理。
2. 提交后删除 MinIO 对象（`removeObject`，404 视为成功，天然幂等）。
3. 对象删除失败**不回滚状态**：`WARN` 记录 fileId/objectKey + 堆栈，计数器 +1，进程内退避重试 3 次；仍失败由清理任务 D 步在 7 天内补偿，超期停止并告警（残留需人工 `mc rm`）。
4. 顺序固定「先置 DELETED，后删对象」：DB 是唯一事实源，避免「对象已删、行仍 ACTIVE」造成前台破图；与 08 §6.2 描述一致。

## 6. 存储设计

### 6.1 桶与访问

| 桶 | 访问策略 | 现有用途 |
|---|---|---|
| `wzblog-public` | 匿名只读 `s3:GetObject`，**禁止 ListBucket**（策略与 `minio-init` 见 08 §6.1） | 封面、正文图、头像、轮播、商品图 |
| `wzblog-private` | 私有，仅预签名 GET/PUT | **当前未使用**：5 个 bizType 均为公开读，此桶仅保留给未来的私有附件/导出 |

- 桶由 bizType 映射决定并写入 `files.bucket`，客户端不可指定；读取按行内 bucket 判断是否签名。公开 URL 为 `{MINIO_PUBLIC_ENDPOINT}/{bucket}/{objectKey}`（path-style，08 §4.3/§6.1），签名必须用 public-endpoint，否则 Host 不一致 403。
- 未确认（及未来私有桶）：预签名 GET，TTL 默认 300s、上限 3600s，不落库、不缓存。

### 6.2 Object key 规则（禁止客户端指定）

- 格式 `{biz}/{yyyy}/{MM}/{uuid}.{ext}`：`{biz}` ∈ `articles|avatars|banners|goods`；`yyyy/MM` 取 UTC；`uuid` 为 32 位小写 hex；`ext` 由服务端归一（`jpeg→jpg`）。
- 映射：`ARTICLE_COVER|CONTENT_IMAGE → articles`、`AVATAR → avatars`、`BANNER → banners`、`GOODS → goods`；缩略图按同一规则生成 `{biz}/{yyyy}/{MM}/{uuid}_thumb.jpg` 并写入 `files.thumb_key`，不单独建行、不单独计数；当前全部业务类型均为公开读，私有桶尚未启用。
- 兜底正则：`^(articles|avatars|banners|goods)/\d{4}/\d{2}/[0-9a-f]{32}\.(jpg|png|webp)$`；不匹配的 key 拒绝读写。

### 6.3 TTL 与 CORS

- 预签名 PUT 5 分钟；确认时限 24 小时；公开对象不过期；`temp/` 生命周期仅作兜底（08 §6.2）。
- MinIO CORS（浏览器直传必需）：来源白名单（本地 `http://localhost:5173`），方法 `PUT/GET/HEAD`，允许头 `*`，暴露 `ETag`，MaxAge 3000；未配置时浏览器报跨域而非 403。

## 7. 校验与图片处理

| 检查 | 规则 | 失败码 |
|---|---|---|
| 扩展名 / 声明 MIME | 小写后 ∈ `jpg/jpeg/png/webp`，文件名不得含路径；MIME 与扩展名一致（仅预检）；SVG（`<svg`/`image/svg+xml`）与 GIF（`GIF87a/GIF89a`）一律拒绝 | `FILE_TYPE_UNSUPPORTED` |
| 魔数 | JPEG `FF D8 FF`；PNG `89 50 4E 47 0D 0A 1A 0A`；WebP `RIFF....WEBP`（偏移 0/8） | `FILE_TYPE_UNSUPPORTED` |
| 解码 | `ImageIO` 实际解码；关缓存，JVM `-Djava.awt.headless=true` | `FILE_TYPE_UNSUPPORTED` |
| 像素 | 单边 ≤8000 且 `width*height ≤ 64_000_000`；先读 `ImageReader` 尺寸，超限不整图解码 | `FILE_PIXEL_EXCEEDED` |
| 大小 | ≤5MB（5 242 880 字节），以 `statObject.size` 为准 | `FILE_SIZE_EXCEEDED` |
| 缩略图 | 最长边 320px、等比、JPEG q=0.82、去除 EXIF；提交后异步生成，失败仅告警 | — |
| EXIF 方向 | 生成缩略图前按 Orientation 旋转/镜像；width/height 记录修正后的值；原图不改写 | — |

- 校验顺序：大小 → 扩展名/声明 MIME → 魔数 → 解码尺寸 → 像素 → sha256；单次读取上限 5MB，有界缓冲 + 超时（连接 3s/读 10s），不落本地盘，读取超时按 `FILE_OBJECT_MISSING` 处理。

## 8. 关键时序

### 8.1 预签名（先落 TEMP 行，再签名）

```
客户端 → POST /files/presign
  1 角色×bizType 矩阵 + 扩展名/大小校验；Redis 限流 30 次/小时/用户（超限 429 COMMON_RATE_LIMITED）
  2 生成 objectKey；INSERT files(status=TEMP, size_bytes=声明值, mime_type=声明值, uploader_id=当前用户)
  3 以 public-endpoint 对 PUT bucket/key 签名，TTL 300s；签名失败置 status=DELETED 并返回 500（不留 TEMP 垃圾）
  ← 200 {fileId, objectKey, uploadUrl, expiresAt}
客户端 → PUT uploadUrl（字节不经过 JVM）→ POST /files/{id}/confirm
```

**结论**：必须先落 TEMP 行再签名。fileId 是确认、URL、审计的唯一入口；签名失败可确定回收；先签名后落库会产生无主 key。

### 8.2 确认

```
1 行锁读文件：不存在或 DELETED → 404 FILE_NOT_FOUND；非 uploader 且非 ADMIN → 403
2 status=ACTIVE → 直接返回（幂等，不动 ref_count）；status=ORPHAN 或 created_at 超 24h
  → 置 DELETED（异步删对象）→ 409 FILE_CONFIRM_TIMEOUT
3 statObject：不存在 → 422 FILE_OBJECT_MISSING；size≠声明值或 >5MB → 行置 DELETED（提交后删对象）+ 400 FILE_SIZE_EXCEEDED
4 流式读：sha256 + 魔数 + 解码尺寸/像素；失败 → 行置 DELETED（提交后删对象）+ 对应错误码
5 去重：同 sha256+size 且 status∈(ACTIVE,ORPHAN) 的既有行 → 新行置 DELETED + 删除本次新上传的重复对象，
  返回既有 fileId/objectKey/publicUrl/thumbUrl；该 objectKey 为 canonical key，客户端必须使用服务端返回的 key
  （文章正文/封面中已插入的临时 key 需改写为 canonical key）
6 否则条件更新 TEMP→ACTIVE，写 sha256/mime/width/height/实际 size；提交后异步生成缩略图并回写 `thumb_key`
← 200 FileVO
```

- 重复确认幂等：ACTIVE 直接返回；并发两次由 `UPDATE ... WHERE status='TEMP'` 条件更新保证仅一次生效，另一请求重读后按 ACTIVE 返回。

### 8.3 multipart 兜底

`POST /files/upload`：大小/扩展名/魔数/解码/像素校验（同 7）→ 计算 sha256 → 去重命中则直接返回既有 FileVO（不写对象）→ 上传 MinIO → INSERT `status=ACTIVE, ref_count=0` → 异步缩略图 → 返回 FileVO。全程 ≤5MB，不代理大文件。

### 8.4 失败流

| 场景 | 检测点 | HTTP | 错误码 | 事务 |
|---|---|---|---|---|
| 类型伪装/扩展名不符 | 魔数/解码 | 400 | `FILE_TYPE_UNSUPPORTED` | 行置 DELETED 后异步删对象 |
| 超 5MB | presign 校验 / confirm statObject | 400 | `FILE_SIZE_EXCEEDED` | 同上 |
| 像素炸弹 | 尺寸读取 | 400 | `FILE_PIXEL_EXCEEDED` | 同上 |
| 对象未上传 | statObject | 422 | `FILE_OBJECT_MISSING` | 保持 TEMP，可重试 |
| 确认超时 | created_at | 409 | `FILE_CONFIRM_TIMEOUT` | 置 DELETED |
| 删除在用文件 | ref_count>0 | 409 | `FILE_IN_USE` | 不更新 |
| 越权（他人文件/用途越权） | 归属/矩阵 | 403 | `FILE_PRESIGN_FORBIDDEN` | 不更新 |
| 文件不存在/已删 | 查询 | 404 | `FILE_NOT_FOUND` | — |
| 清理重叠 | ShedLock 未获取 | 409 | `FILE_CLEANUP_RUNNING` | — |

## 9. 引用计数

### 9.1 `claimByKey(objectKey, ownerId, bizType)` → fileId

- 语义：按 canonical `objectKey` 为「某业务实体新增一次引用」+1，并把 ORPHAN 拉回 ACTIVE；单条原子 SQL：
  `UPDATE files SET ref_count=ref_count+1, status='ACTIVE' WHERE object_key=? AND status IN ('ACTIVE','ORPHAN') AND ref_count>=0`，随后按 `uk_object` 读出 `id` 返回（当前 bizType 均落 `wzblog-public`，同 key 不会跨桶重复）。
- 入参不含 fileId：业务表（`articles.cover_key`、正文图、`users.avatar_key`、`banners.image_key`、`goods.image_key`）只存 key，调用方无需先查 ID。
- 权限：`uploader_id = ownerId`（跨用户复用一律拒绝，防 IDOR）；`biz_type` 非空时必须等于入参，为空则写入入参。
- **非幂等**：同一 (objectKey, 业务实体) 调两次即 +2；幂等责任在调用方——M2 保存文章时对「旧图集合 vs 新图集合」做差集，只对新增 claim、只对移除 release；M1 换头像先 claim 新、再 release 旧。
- 失败：影响 0 行 → 按 key 查行区分 `FILE_NOT_FOUND`（不存在/已 DELETED）与 `FILE_CONFIRM_TIMEOUT`（仍 TEMP）；事务由调用方回滚。

### 9.2 `releaseByKey(objectKey)` → fileId

- 语义：`UPDATE files SET ref_count=ref_count-1 WHERE object_key=? AND ref_count>0`；不改 status、不删对象；返回被释放行的 fileId（0 行时返回 NULL）。减到 0 后由清理任务在 7 天后转 ORPHAN。幂等容错：影响 0 行（已为 0 或行不存在）记 DEBUG 并返回，不抛错，避免重复删除阻断文章删除。
- 调用方：M2 删文章/删图/换封面、M1 换头像、M7 删轮播、M4 下架商品。

### 9.3 差集与锁序

- M2 解析旧/新正文图片 key 与 `cover_key`，按 objectKey 求差；直接以 key 调 `claimByKey`/`releaseByKey`，无需先解析 fileId。
- 并发由 `ref_count = ref_count ± 1` 保证，禁止「读-改-写」；同一事务内按 objectKey 字典序处理避免死锁；清理只处理 `status='ORPHAN' AND ref_count=0`，双条件防误删。

## 10. 删除规则

| 主体 | 条件 | 结果 |
|---|---|---|
| OWNER（uploader） | `ref_count=0` 且 status∈(ACTIVE,ORPHAN) | 200，先置 DELETED（提交）再删对象，失败重试 |
| OWNER | `ref_count>0` | 409 `FILE_IN_USE`，提示先移除引用 |
| ADMIN | 可越过归属，但仍要求 `ref_count=0` | 同上；强制删除在用文件默认不支持（19-4） |
| 任意 | status=DELETED | 200 幂等返回 `data=null`，不重复删对象 |
| OWNER/ADMIN | status=TEMP | 按 5.3 顺序执行 |
| 非 OWNER 非 ADMIN | — | 403 `FILE_PRESIGN_FORBIDDEN` |

- 只软删不物理删行，保留 sha256/审计线索；`uk_object` 保证 key 不复用；删除后不可再 confirm/claim，`/files/{id}/url` 返回 404。

## 11. 清理任务

| 项 | 方案 |
|---|---|
| 调度 | `@Scheduled(cron="0 0 3 * * *", zone="Asia/Shanghai")` 每日 03:00 |
| 分布式锁 | ShedLock `media-cleanup`（`lockAtMostFor=PT30M`、`lockAtLeastFor=PT5M`）；手动触发复用同锁，未获取返回 `FILE_CLEANUP_RUNNING` |
| A 步 | `ACTIVE AND ref_count=0 AND updated_at < now-7d` → 置 ORPHAN（updated_at 由 ON UPDATE 刷新） |
| B 步 | `TEMP AND created_at < now-24h` → 先置 DELETED，提交后删对象 |
| C 步 | `ORPHAN AND ref_count=0 AND updated_at < now-7d` → 先置 DELETED，提交后删对象 |
| D 步 | `DELETED AND deleted_at > now-7d` 的对象删除补偿（幂等；404 视为成功；超过 7 天停止并告警） |
| 批量 | 每批 200 行、最多 10 批；每行独立事务，单行失败不中断整批 |
| 失败 | `WARN` 记录 fileId/objectKey/原因；计数器 `media_cleanup_fail_total`；次日重试 |
| dryRun | 只 SELECT 计数，不写库、不删对象；各步均带 `ref_count=0`，C 步额外要求 `status='ORPHAN'`；删对象前 `SELECT ... FOR UPDATE` 复核，杜绝误删 |

## 12. 并发、事务、幂等

| 场景 | 事务边界 | 锁/条件更新 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| 预签名 | INSERT 独立事务 | — | 每次新 fileId | 签名失败置 DELETED |
| 确认/去重 | 单事务 | `FOR UPDATE` + `WHERE status='TEMP'`；`idx_sha256` 查询 | fileId | 重读后按 ACTIVE 幂等返回；重复对象删除失败由清理补偿 |
| claimByKey/releaseByKey | 调用方事务 | `ref_count = ref_count ± 1`，按 `uk_object` 定位 | 调用方差集 | 0 行由调用方回滚或忽略 |
| 删除 | 状态事务 + 提交后删对象 | `status IN (...) AND ref_count=0` | fileId | DB 不回滚，对象删除重试（见 5.3） |
| 清理 | 每行一事务 | ShedLock + `ref_count=0` | 行 id | 记录并跳过 |

## 13. 权限与归属

| 操作 | 角色 | 归属校验 | bizType 范围 |
|---|---|---|---|
| 预签名 | EDITOR | — | `ARTICLE_COVER`、`CONTENT_IMAGE`；ADMIN 另可 `BANNER`、`GOODS` |
| multipart 上传 | USER | — | `AVATAR`；EDITOR 额外 `ARTICLE_*`；ADMIN 全部 |
| 取 URL（公开桶 ACTIVE/ORPHAN） | PUBLIC | — | — |
| 取 URL（TEMP / 未来私有桶）/ 确认 | USER | uploader 或 ADMIN | 与文件记录一致 |
| 删除 | OWNER | uploader；ADMIN 越过 | — |
| 列表 / 清理 | ADMIN | — | — |

- 未登录访问需签名场景 → 401；登录但非归属 → 403 `FILE_PRESIGN_FORBIDDEN`。读取公开文件无需登录；私有与未确认文件只能由上传者/管理员换取短期签名 URL。

## 14. 错误码

> 均为 00 §5.3 允许的 `FILE_` 前缀；**已登记到 04 §7.7**（见 19-7）。

| code | HTTP | 触发条件 | 前端提示 |
|---|---|---|---|
| `FILE_TYPE_UNSUPPORTED` | 400 | 扩展名不在白名单、魔数不符、解码失败、SVG/GIF | 仅支持 JPG/PNG/WebP 图片 |
| `FILE_SIZE_EXCEEDED` | 400 | 声明或实际 >5MB，或与声明大小不符 | 图片不能超过 5MB |
| `FILE_PIXEL_EXCEEDED` | 400 | 单边 >8000 或总像素 >64MP | 图片分辨率过高 |
| `FILE_NOT_FOUND` | 404 | 记录不存在或已 DELETED | 文件不存在 |
| `FILE_OBJECT_MISSING` | 422 | 确认时对象不存在/读取超时 | 文件尚未上传成功，请重试 |
| `FILE_CONFIRM_TIMEOUT` | 409 | 超 24h 未确认，或状态不允许该动作 | 上传已失效，请重新上传 |
| `FILE_IN_USE` | 409 | 删除时 `ref_count>0` | 文件正在被引用，无法删除 |
| `FILE_PRESIGN_FORBIDDEN` | 403 | 角色与 bizType 不匹配、非归属、跨用户 claim、key 非法 | 无权操作该文件 |
| `FILE_CLEANUP_RUNNING` | 409 | ShedLock 被其他实例/请求持有 | 清理任务正在执行，请稍后重试 |

## 15. 验收用例

| 编号 | 前置 | 操作 | 期望 |
|---|---|---|---|
| TC-M5-001 | EDITOR | presign 合法 PNG 1MB | 200；objectKey 匹配 6.2 正则；expiresAt≈+300s；DB 新增 TEMP 行 |
| TC-M5-002 | USER / EDITOR | USER 调 presign；EDITOR 传 `bizType=AVATAR` | 均 403 `FILE_PRESIGN_FORBIDDEN` |
| TC-M5-003 | — | zip/PHP/EXE 改名 `.png` 直传后 confirm | 400 `FILE_TYPE_UNSUPPORTED`；对象被删；行 DELETED；无 ACTIVE 记录 |
| TC-M5-004 | — | presign 声明 6MB；multipart 传 6MB | 400 `FILE_SIZE_EXCEEDED`；无对象残留 |
| TC-M5-005 | — | 20000×20000 PNG 直传后 confirm | 400 `FILE_PIXEL_EXCEEDED`；进程无 OOM；堆稳定 |
| TC-M5-006 | — | `.svg` 直传、`.svg` 改名 `.png`、GIF89a | 三种均 400 `FILE_TYPE_UNSUPPORTED`（SVG/GIF 默认拒绝） |
| TC-M5-007 | — | 同一图片两次 presign+confirm | 第二次返回同一 canonical fileId/objectKey；MinIO 仅保留一份对象，本次重复对象被删；ref_count 不变 |
| TC-M5-008 | — | 对已 ACTIVE 文件连续两次 confirm | 均 200 且响应一致；ref_count/sha256 不变 |
| TC-M5-009 | — | presign 后不 PUT 直接 confirm | 422 `FILE_OBJECT_MISSING`；行仍 TEMP 可重试 |
| TC-M5-010 | 造 25h 前 TEMP | confirm；再跑清理 | confirm 409 `FILE_CONFIRM_TIMEOUT` 且置 DELETED；对象删除成功 |
| TC-M5-011 | ACTIVE 文件 | 2 个并发事务各 claim 一次 | `ref_count=2`，无丢失更新；状态 ACTIVE |
| TC-M5-012 | 文章引用图 A、封面 B | 保存文章改为图 C、封面 D | A/B 各 -1、C/D 各 +1；A/B 在 7 天后转 ORPHAN |
| TC-M5-013 | `ref_count=1` | OWNER 删除 | 409 `FILE_IN_USE`；行与对象均不变 |
| TC-M5-014 | `ref_count=0` ACTIVE | OWNER 删除两次 | 首次 200 + DELETED + 对象不存在；第二次 200 幂等 |
| TC-M5-015 | 他人 ref_count=0 文件 | 普通用户删；ADMIN 删 | 前者 403；后者 200 且归属被越过 |
| TC-M5-016 | TEMP>24h、ORPHAN>7d、在用 ACTIVE 各 1 | 先 dryRun 再真实清理 | dryRun 计数变化但库/对象不变；真实清理删前两类，在用文件与对象保留 |
| TC-M5-017 | 公开桶 TEMP 文件；另造 1 条私有桶测试记录（仅验证保留能力） | 匿名取 URL；登录非归属取 URL | 均只返回短期签名 URL；匿名与越权 401/403，不泄露 key；私有桶无业务写入路径 |
| TC-M5-018 | 文章删除 | release 后立即查看；等待转 ORPHAN | 图仍可访问且 7 天内不删；7 天后转 ORPHAN，再过 7 天清理 |
| TC-M5-019 | EDITOR | presign 请求体额外带 objectKey/bucket/路径穿越串 | 额外字段被忽略，实际 key 仍服务端生成；穿越串 400 |
| TC-M5-020 | Orientation=6 的 JPEG | 上传并取 thumbUrl | 缩略图最长边 320 且方向正确；`files.thumb_key` 已写入且与 `{biz}/{yyyy}/{MM}/{uuid}_thumb.jpg` 匹配；width/height 与可视方向一致；EXIF 不进入缩略图 |

## 16. 性能与索引

| 查询/操作 | 索引 | 预期 | 验收 |
|---|---|---|---|
| 确认去重 `sha256=? AND size_bytes=? AND status IN(...)` | `idx_sha256` | 命中后回表 1~2 行 | 10 万行下 <10ms |
| 清理扫描 | `idx_status_created(status, created_at)` | 范围扫描，避免全表 | 单批 200 行 <50ms |
| 后台列表按上传者 | `idx_uploader(uploader_id, created_at)` | 分页稳定 | pageSize=20 <50ms |
| 大文件上传 | — | 客户端↔MinIO 直连，JVM 不承载字节 | 10MB 级对象应用内存无增长 |
| confirm 校验 | — | 1 次 statObject + 1 次 ≤5MB 流式读 | 内网 P95 <800ms |

- 缩略图在事务提交后异步生成（2~4 线程、有界队列），不阻塞 confirm，失败可重试；列表 `COUNT(*)` + `LIMIT`，禁止 `SELECT *`；图片字节由 MinIO/反代直出并设长缓存，应用不代理。

## 17. 安全基线

| 风险 | 对策 |
|---|---|
| 对象 key 注入/路径穿越 | key 全部服务端生成；固定 `{biz}` 枚举 + UUID；入库前正则复核；任何 DTO 不接受 key/bucket |
| Content-Type 伪造 | 只信魔数 + `ImageIO` 解码；mime_type 存探测值；拒绝 SVG 与 GIF |
| 预签名范围 | 绑定 bucket+key+PUT+300s；**PUT 签名不强制 body 大小，大小以 confirm 的 statObject 为准** |
| 桶策略与 CORS | 公开桶仅 `GetObject`、不 List；应用专用服务账号最小权限；root 仅初始化；CORS 来源白名单、禁用 `*`、暴露 `ETag` |
| 越权访问 | URL/confirm/delete 均校验 uploader；未确认/未来私有文件一律签名且短 TTL |
| 资源耗尽 | 5MB/8000×8000/64MP 上限；有界缓冲、超时、ImageIO 关缓存；限流 30 次/小时/用户 |
| 信息泄露 | 错误响应不含 endpoint/凭证；日志不打印签名 URL 全文，只打 fileId |

## 18. 事件与跨模块调用

| 调用 | 方向 | 载荷 | 消费方 | 幂等 |
|---|---|---|---|---|
| `claimByKey(objectKey, ownerId, bizType)` | M1/M2/M4/M7 → M5 | objectKey、ownerId、bizType → fileId | 各业务保存流程 | 依赖调用方差集，M5 侧非幂等 |
| `releaseByKey(objectKey)` | M1/M2/M4/M7 → M5 | objectKey → fileId | 各业务删除/替换流程 | 0 行影响视为已释放 |
| `GET /files/{id}/url` | 前端 → M5 | id、expires? | — | 幂等读 |

- 本模块不发布、不订阅领域事件。

## 19. 待确认事项（均已给默认值）

| # | 事项 | 默认方案 | 需谁确认 |
|---|---|---|---|
| 1 | WebP 是否支持 | 支持（白名单含 `webp`，魔数校验） | 产品 |
| 2 | GIF / SVG | GIF 拒绝（不做仅静态首帧）；SVG 拒绝，不引入清洗流程 | 产品/安全 |
| 3 | ORPHAN 宽限 | ACTIVE ref=0 满 7 天转 ORPHAN，再满 7 天删除（合计 14 天）；需 7 天删除则把 C 步宽限调 0 | 产品/运维 |
| 4 | 管理员强制删除 | 只越过归属，仍禁止 `ref_count>0` | 产品 |
| 5 | 私有桶用途 | 当前 5 个 bizType 全落公开桶，private 仅保留能力，后续私有附件/导出再启用；文档不得声称已使用 | 产品 |
| 6 | confirm 读对象 | 允许 ≤5MB 流经 JVM（有界、不落盘），不引入独立校验服务 | 架构负责人 |
| 7 | FILE_ 错误码 | 已登记到 04 §7.7；新增码必须先改 04 | 已解决 |

> 已冻结项（不再列为待确认）：跨模块接口统一为 `claimByKey(objectKey, ownerId, bizType)` / `releaseByKey(objectKey)`（00 §9.2）；object key 统一 `{biz}/{yyyy}/{MM}/{uuid}.{ext}`；缩略图 key 落 `files.thumb_key`；删除顺序「DB 先置 DELETED → 再删对象 → 失败重试」；去重返回既有 canonical fileId/objectKey 并删除本次重复对象。