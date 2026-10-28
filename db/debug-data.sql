-- =============================================================================
-- 文栈博客（WzBlog）· 调试数据集
-- -----------------------------------------------------------------------------
-- 用途：在 V1 结构 + V2 seed 之上，一次性填满 32 张表的可调试数据。
-- 前置：已执行 Flyway V1/V2；建议在独立调试库执行，不要用于生产。
-- 特性：显式主键（1000+ 段位）、先父后子、计数/余额/日统计在末尾按明细回算。
-- 幂等：非幂等；重复执行会因主键/唯一键冲突失败。
--
-- 调试账号（统一密码 Passw0rd1，BCrypt cost 10）：
--   debug_admin / debug_editor / debug_reader / debug_coder /
--   debug_guest / debug_newbie / debug_disabled
--   另有已注销账号 deleted_1008（不可登录）
--
-- 说明：所有业务数据均为虚构；时间列存 UTC，统计日按 Asia/Shanghai（UTC+8）换算。
-- =============================================================================
SET NAMES utf8mb4;

SET @pwd = '$2a$10$c4XZY1Pw0lqRJi9DEkvLJu4UZvae8pBX0gmICoBHk37E8X6nIujGS';

START TRANSACTION;

-- -----------------------------------------------------------------------------
-- 0. 解析 V2 seed 中已有数据的 ID（seed 未执行时下列变量为 NULL，插入会回滚）
-- -----------------------------------------------------------------------------
SET @cat_backend   = (SELECT id FROM categories WHERE slug = 'backend');
SET @cat_database  = (SELECT id FROM categories WHERE slug = 'database');
SET @cat_essay     = (SELECT id FROM categories WHERE slug = 'essay');

SET @tag_java      = (SELECT id FROM tags WHERE slug = 'java');
SET @tag_spring    = (SELECT id FROM tags WHERE slug = 'spring-boot');
SET @tag_mysql     = (SELECT id FROM tags WHERE slug = 'mysql');
SET @tag_redis     = (SELECT id FROM tags WHERE slug = 'redis');
SET @tag_mybatis   = (SELECT id FROM tags WHERE slug = 'mybatis');
SET @tag_arch      = (SELECT id FROM tags WHERE slug = 'architecture');
SET @tag_interview = (SELECT id FROM tags WHERE slug = 'interview');

SET @title_newcomer = (SELECT id FROM titles WHERE name = '初来乍到');
SET @title_active   = (SELECT id FROM titles WHERE name = '活跃作者');
SET @title_veteran  = (SELECT id FROM titles WHERE name = '资深贡献者');

-- -----------------------------------------------------------------------------
-- 1. 称号（titles）
-- -----------------------------------------------------------------------------
INSERT INTO titles (id, name, css_class, description, sort_order, created_at, updated_at) VALUES
(9001, '站长', 'title-owner', '调试数据：站点所有者称号', 5,
 '2026-10-18 01:00:00.000', '2026-10-18 01:00:00.000'),
(9002, '调试体验官', 'title-debugger', '调试数据：参与体验计划的用户', 25,
 '2026-10-19 01:00:00.000', '2026-10-19 01:00:00.000'),
(9003, '先行者', 'title-pioneer', '调试数据：早期参与者限定称号', 35,
 '2026-10-20 01:00:00.000', '2026-10-20 01:00:00.000');

-- -----------------------------------------------------------------------------
-- 2. 分类（categories）与标签（tags）
-- -----------------------------------------------------------------------------
INSERT INTO categories (id, name, slug, description, sort_order, status, created_at, updated_at) VALUES
(9001, '工程实践', 'engineering-practice', '调试数据：工程化、架构与协作实践', 15, 'ENABLED',
 '2026-10-18 01:10:00.000', '2026-10-18 01:10:00.000'),
(9002, '调试与压测', 'debug-loadtest', '调试数据：仅后台可见的归档分类', 90, 'DISABLED',
 '2026-10-18 01:10:00.000', '2026-10-18 01:10:00.000');

INSERT INTO tags (id, name, slug, status, created_at, updated_at) VALUES
(9101, 'SSE', 'sse', 'ENABLED', '2026-10-18 01:20:00.000', '2026-10-18 01:20:00.000'),
(9102, '并发编程', 'concurrency', 'ENABLED', '2026-10-18 01:20:00.000', '2026-10-18 01:20:00.000'),
(9103, '性能优化', 'performance', 'ENABLED', '2026-10-18 01:20:00.000', '2026-10-18 01:20:00.000'),
(9104, 'JVM', 'jvm', 'ENABLED', '2026-10-18 01:20:00.000', '2026-10-18 01:20:00.000'),
(9105, '调试数据', 'debug-data', 'ENABLED', '2026-10-18 01:20:00.000', '2026-10-18 01:20:00.000');

-- -----------------------------------------------------------------------------
-- 3. 文件（files）——object_key 与文章封面/正文图、头像、轮播、商品图一一对应
--    ref_count 在插入时即为最终引用次数；TEMP/ORPHAN 为清理任务样本。
-- -----------------------------------------------------------------------------
INSERT INTO files (id, bucket, object_key, thumb_key, original_name, mime_type, size_bytes, sha256,
                   width, height, uploader_id, biz_type, status, ref_count, created_at, updated_at)
VALUES
(5001, 'local', 'debug/covers/architecture.png', NULL, 'architecture.png', 'image/png', 245760,
 SHA2('debug-file-5001', 256), 1280, 720, 1001, 'ARTICLE_COVER', 'ACTIVE', 1,
 '2026-10-19 19:30:00.000', '2026-10-19 19:30:00.000'),
(5002, 'local', 'debug/covers/mysql-cursor.png', NULL, 'mysql-cursor.png', 'image/png', 198400,
 SHA2('debug-file-5002', 256), 1280, 720, 1002, 'ARTICLE_COVER', 'ACTIVE', 1,
 '2026-10-20 20:30:00.000', '2026-10-20 20:30:00.000'),
(5003, 'local', 'debug/covers/cache-incident.png', NULL, 'cache-incident.png', 'image/png', 176500,
 SHA2('debug-file-5003', 256), 1280, 720, 1001, 'ARTICLE_COVER', 'ACTIVE', 1,
 '2026-10-21 19:30:00.000', '2026-10-21 19:30:00.000'),
(5004, 'local', 'debug/content/module-flow.png', NULL, 'module-flow.png', 'image/png', 88320,
 SHA2('debug-file-5004', 256), 960, 540, 1001, 'CONTENT_IMAGE', 'ACTIVE', 2,
 '2026-10-19 19:35:00.000', '2026-10-19 19:35:00.000'),
(5005, 'local', 'debug/content/cursor-table.png', NULL, 'cursor-table.png', 'image/png', 76400,
 SHA2('debug-file-5005', 256), 900, 600, 1002, 'CONTENT_IMAGE', 'ACTIVE', 1,
 '2026-10-20 20:35:00.000', '2026-10-20 20:35:00.000'),
(5006, 'local', 'debug/content/sse-flow.png', NULL, 'sse-flow.png', 'image/png', 91200,
 SHA2('debug-file-5006', 256), 1200, 675, 1003, 'CONTENT_IMAGE', 'ACTIVE', 1,
 '2026-10-23 20:35:00.000', '2026-10-23 20:35:00.000'),
(5007, 'local', 'debug/avatars/debug-admin.png', NULL, 'debug-admin.png', 'image/png', 102400,
 SHA2('debug-file-5007', 256), 512, 512, 1001, 'AVATAR', 'ACTIVE', 1,
 '2026-10-18 01:25:00.000', '2026-10-18 01:25:00.000'),
(5008, 'local', 'debug/avatars/debug-editor.png', NULL, 'debug-editor.png', 'image/png', 98000,
 SHA2('debug-file-5008', 256), 512, 512, 1002, 'AVATAR', 'ACTIVE', 1,
 '2026-10-19 01:25:00.000', '2026-10-19 01:25:00.000'),
(5009, 'local', 'debug/avatars/debug-reader.png', NULL, 'debug-reader.png', 'image/png', 97000,
 SHA2('debug-file-5009', 256), 512, 512, 1003, 'AVATAR', 'ACTIVE', 1,
 '2026-10-20 01:25:00.000', '2026-10-20 01:25:00.000'),
(5010, 'local', 'debug/avatars/debug-coder.png', NULL, 'debug-coder.png', 'image/png', 96000,
 SHA2('debug-file-5010', 256), 512, 512, 1004, 'AVATAR', 'ACTIVE', 1,
 '2026-10-21 01:25:00.000', '2026-10-21 01:25:00.000'),
(5011, 'local', 'debug/banners/home-01.png', NULL, 'home-01.png', 'image/png', 320000,
 SHA2('debug-file-5011', 256), 1920, 640, 1001, 'BANNER', 'ACTIVE', 1,
 '2026-10-20 02:30:00.000', '2026-10-20 02:30:00.000'),
(5012, 'local', 'debug/banners/home-02.png', NULL, 'home-02.png', 'image/png', 310000,
 SHA2('debug-file-5012', 256), 1920, 640, 1001, 'BANNER', 'ACTIVE', 1,
 '2026-10-20 02:30:00.000', '2026-10-20 02:30:00.000'),
(5013, 'local', 'debug/banners/home-03.png', NULL, 'home-03.png', 'image/png', 300000,
 SHA2('debug-file-5013', 256), 1920, 640, 1001, 'BANNER', 'ACTIVE', 1,
 '2026-10-20 02:30:00.000', '2026-10-20 02:30:00.000'),
(5014, 'local', 'debug/goods/mug.png', NULL, 'mug.png', 'image/png', 64000,
 SHA2('debug-file-5014', 256), 800, 800, 1001, 'GOODS', 'ACTIVE', 1,
 '2026-10-21 02:30:00.000', '2026-10-21 02:30:00.000'),
(5015, 'local', 'debug/goods/title-badge.png', NULL, 'title-badge.png', 'image/png', 52000,
 SHA2('debug-file-5015', 256), 800, 800, 1001, 'GOODS', 'ACTIVE', 1,
 '2026-10-21 02:30:00.000', '2026-10-21 02:30:00.000'),
(5016, 'local', 'debug/friends/logo-a.png', NULL, 'logo-a.png', 'image/png', 24000,
 SHA2('debug-file-5016', 256), 256, 256, 1001, NULL, 'ACTIVE', 1,
 '2026-10-22 02:30:00.000', '2026-10-22 02:30:00.000'),
(5017, 'local', 'debug/temp/upload-pending.png', NULL, 'upload-pending.png', 'image/png', 18000,
 SHA2('debug-file-5017', 256), 640, 480, 1006, NULL, 'TEMP', 0,
 '2026-10-27 01:10:00.000', '2026-10-27 01:10:00.000'),
(5018, 'local', 'debug/temp/orphan.png', NULL, 'orphan.png', 'image/png', 16000,
 SHA2('debug-file-5018', 256), 640, 480, 1006, NULL, 'ORPHAN', 0,
 '2026-10-24 01:10:00.000', '2026-10-24 01:10:00.000');

-- -----------------------------------------------------------------------------
-- 4. 用户（users）——密码统一为 Passw0rd1 的 BCrypt；1008 为已注销账号
-- -----------------------------------------------------------------------------
INSERT INTO users (id, username, password_hash, email, email_verified_at, nickname, avatar_key, bio,
                   role, status, source, active_title_id, openid, unionid,
                   last_login_at, last_login_ip, version, created_at, updated_at, deleted_at)
VALUES
(1001, 'debug_admin', @pwd, 'debug.admin@example.com', '2026-10-18 01:30:00.000', '文栈管理员',
 'debug/avatars/debug-admin.png', '负责站点运营与内容审核。', 'ADMIN', 'ACTIVE', 'WEB', 9001, NULL, NULL,
 '2026-10-27 01:00:00.000', '127.0.0.1', 7, '2026-10-18 01:20:00.000', '2026-10-27 01:00:00.000', NULL),
(1002, 'debug_editor', @pwd, 'debug.editor@example.com', '2026-10-19 01:30:00.000', '林墨',
 'debug/avatars/debug-editor.png', '后端工程师，负责内容编辑。', 'EDITOR', 'ACTIVE', 'WEB', 9002, NULL, NULL,
 '2026-10-25 09:00:00.000', '127.0.0.1', 4, '2026-10-19 01:20:00.000', '2026-10-25 09:00:00.000', NULL),
(1003, 'debug_reader', @pwd, 'debug.reader@example.com', '2026-10-20 01:30:00.000', '陈小满',
 'debug/avatars/debug-reader.png', '读者，关注缓存与数据库。', 'USER', 'ACTIVE', 'WEB', @title_newcomer, NULL, NULL,
 '2026-10-25 01:00:00.000', '127.0.0.1', 5, '2026-10-20 01:20:00.000', '2026-10-25 01:00:00.000', NULL),
(1004, 'debug_coder', @pwd, 'debug.coder@example.com', '2026-10-21 01:30:00.000', '周行',
 'debug/avatars/debug-coder.png', '写 Java，也写前端。', 'USER', 'ACTIVE', 'WEB', 9002, NULL, NULL,
 '2026-10-25 11:30:00.000', '127.0.0.1', 6, '2026-10-21 01:20:00.000', '2026-10-25 11:30:00.000', NULL),
(1005, 'debug_guest', @pwd, 'debug.guest@example.com', '2026-10-22 01:30:00.000', '许知远',
 NULL, '偶尔写点积分系统。', 'USER', 'ACTIVE', 'WEB', @title_newcomer, NULL, NULL,
 '2026-10-25 12:00:00.000', '127.0.0.1', 4, '2026-10-22 01:20:00.000', '2026-10-25 12:00:00.000', NULL),
(1006, 'debug_newbie', @pwd, 'debug.newbie@example.com', '2026-10-25 09:50:00.000', '新来的',
 NULL, '刚注册的新用户。', 'USER', 'ACTIVE', 'WEB', @title_newcomer, NULL, NULL,
 NULL, NULL, 0, '2026-10-25 09:45:00.000', '2026-10-25 09:45:00.000', NULL),
(1007, 'debug_disabled', @pwd, 'debug.disabled@example.com', '2026-10-23 02:00:00.000', '停用账号',
 NULL, '因违规被停用的演示账号。', 'USER', 'DISABLED', 'WECHAT', NULL,
 'debug_openid_1007', 'debug_unionid_1007', '2026-10-23 02:15:00.000', '127.0.0.1', 1,
 '2026-10-23 01:50:00.000', '2026-10-23 02:20:00.000', NULL),
(1008, 'deleted_1008', @pwd, NULL, NULL, '已注销用户', NULL, NULL,
 'USER', 'ACTIVE', 'WEB', NULL, NULL, NULL, NULL, NULL, 1,
 '2026-10-24 01:00:00.000', '2026-10-24 08:00:00.000', '2026-10-24 08:00:00.000');

-- -----------------------------------------------------------------------------
-- 5. 刷新令牌（refresh_tokens）——家族轮换链：9701 -> 9702；9703 已登出
-- -----------------------------------------------------------------------------
INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, parent_id, replaced_by_id,
                            client_ip, user_agent, issued_at, expires_at, revoked_at, revoke_reason)
VALUES
(9701, 1003, SHA2('debug-refresh-token-9701', 256), '11111111-1111-1111-1111-111111111111',
 NULL, 9702, '127.0.0.1', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0',
 '2026-10-25 01:00:00.000', '2026-11-24 01:00:00.000', '2026-10-25 01:05:00.000', 'ROTATED'),
(9702, 1003, SHA2('debug-refresh-token-9702', 256), '11111111-1111-1111-1111-111111111111',
 9701, NULL, '127.0.0.1', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0',
 '2026-10-25 01:05:00.000', '2026-11-24 01:05:00.000', NULL, NULL),
(9703, 1002, SHA2('debug-refresh-token-9703', 256), '22222222-2222-2222-2222-222222222222',
 NULL, NULL, '127.0.0.1', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) DebugBrowser/1.0',
 '2026-10-24 08:00:00.000', '2026-11-23 08:00:00.000', '2026-10-25 09:00:00.000', 'LOGOUT');

-- -----------------------------------------------------------------------------
-- 6. 验证码（verification_codes）——仅存 SHA-256，不存明文
-- -----------------------------------------------------------------------------
INSERT INTO verification_codes (id, scene, target, code_hash, expires_at, consumed_at, attempt_count, created_at) VALUES
(9801, 'RESET', 'debug.reader@example.com', SHA2('debug-code-9801', 256),
 '2026-10-25 01:10:00.000', NULL, 0, '2026-10-25 01:05:00.000'),
(9802, 'REGISTER', 'debug.newbie@example.com', SHA2('debug-code-9802', 256),
 '2026-10-27 10:00:00.000', NULL, 1, '2026-10-25 09:55:00.000'),
(9803, 'BIND', 'debug_openid_1007', SHA2('debug-code-9803', 256),
 '2026-10-23 02:10:00.000', '2026-10-23 02:06:00.000', 0, '2026-10-23 02:05:00.000');

-- -----------------------------------------------------------------------------
-- 7. 登录审计（login_audits）——成功/失败、存在/不存在用户、停用账号
-- -----------------------------------------------------------------------------
INSERT INTO login_audits (id, user_id, username_attempt, success, failure_reason, ip, user_agent, created_at) VALUES
(9901, 1001, 'debug_admin', 1, NULL, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-27 01:00:00.000'),
(9902, 1005, 'debug_guest', 0, 'BAD_CREDENTIALS', '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-26 23:00:00.000'),
(9903, NULL, 'ghost_user', 0, 'BAD_CREDENTIALS', '127.0.0.1',
 'curl/8.5.0', '2026-10-26 23:05:00.000'),
(9904, 1007, 'debug_disabled', 0, 'DISABLED', '127.0.0.1',
 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) MicroMessenger/8.0', '2026-10-23 02:20:00.000'),
(9905, 1003, 'debug_reader', 1, NULL, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-25 01:00:00.000');

-- -----------------------------------------------------------------------------
-- 8. 文章（articles）——覆盖 PUBLISHED/PRIVATE/UNLISTED/DRAFT/ARCHIVED/回收站
--    计数列先置 0，末尾按明细统一回算。
-- -----------------------------------------------------------------------------
INSERT INTO articles (id, author_id, category_id, title, slug, summary, cover_key,
                      content_md, content_html, content_text, toc_json,
                      word_count, reading_seconds, status, visibility, is_pinned, allow_comment,
                      version, published_at, created_at, updated_at, deleted_at)
VALUES
(2001, 1001, @cat_backend, '用模块化单体重写博客后端：边界、端口与事务',
 'debug-modular-monolith', '从包边界、跨模块端口到事务落点，记录一次可拆分的单体重构。',
 'debug/covers/architecture.png',
 '# 用模块化单体重写博客后端\n\n微服务不是默认答案。单人维护、单库部署的博客，更适合先把边界做清楚。\n\n## 三条硬规则\n\n- api 层不碰 Mapper\n- 跨模块只走 app 端口或领域事件\n- 事务只落在 app 层\n\n![模块依赖](/media/debug/content/module-flow.png)\n\n## 结果\n\n同一套代码既能在单体里运行，也保留了未来拆分的缝。',
 '<h1 id="heading-1">用模块化单体重写博客后端</h1><p>微服务不是默认答案。单人维护、单库部署的博客，更适合先把边界做清楚。</p><h2 id="heading-2">三条硬规则</h2><ul><li>api 层不碰 Mapper</li><li>跨模块只走 app 端口或领域事件</li><li>事务只落在 app 层</li></ul><img src="/media/debug/content/module-flow.png" alt="模块依赖"><h2 id="heading-3">结果</h2><p>同一套代码既能在单体里运行，也保留了未来拆分的缝。</p>',
 '用模块化单体重写博客后端\n微服务不是默认答案。单人维护、单库部署的博客，更适合先把边界做清楚。\n三条硬规则\napi 层不碰 Mapper\n跨模块只走 app 端口或领域事件\n事务只落在 app 层\n结果\n同一套代码既能在单体里运行，也保留了未来拆分的缝。',
 '[{"level":1,"text":"用模块化单体重写博客后端","anchor":"heading-1"},{"level":2,"text":"三条硬规则","anchor":"heading-2"},{"level":2,"text":"结果","anchor":"heading-3"}]',
 180, 60, 'PUBLISHED', 'PUBLIC', 1, 1, 3,
 '2026-10-20 02:00:00.000', '2026-10-19 20:00:00.000', '2026-10-20 02:00:00.000', NULL),

(2002, 1002, @cat_database, 'MySQL 行构造器游标分页实战',
 'debug-mysql-cursor-pagination', '用 (published_at, id) 行构造器替代 OFFSET，避免深翻页抖动。',
 'debug/covers/mysql-cursor.png',
 '# MySQL 行构造器游标分页实战\n\n深翻页的 OFFSET 会随着页数增长而变慢。\n\n## 为什么不用 OFFSET\n\nOFFSET 需要扫描并丢弃前 N 行，数据量大时成本不可控。\n\n## 实现\n\n先用 (published_at, id) 定位上一页最后一行，再按索引继续取。\n\n![执行计划](/media/debug/content/cursor-table.png)',
 '<h1 id="heading-1">MySQL 行构造器游标分页实战</h1><p>深翻页的 OFFSET 会随着页数增长而变慢。</p><h2 id="heading-2">为什么不用 OFFSET</h2><p>OFFSET 需要扫描并丢弃前 N 行，数据量大时成本不可控。</p><h2 id="heading-3">实现</h2><p>先用 (published_at, id) 定位上一页最后一行，再按索引继续取。</p><img src="/media/debug/content/cursor-table.png" alt="执行计划">',
 'MySQL 行构造器游标分页实战\n深翻页的 OFFSET 会随着页数增长而变慢。\n为什么不用 OFFSET\nOFFSET 需要扫描并丢弃前 N 行，数据量大时成本不可控。\n实现\n先用 (published_at, id) 定位上一页最后一行，再按索引继续取。',
 '[{"level":1,"text":"MySQL 行构造器游标分页实战","anchor":"heading-1"},{"level":2,"text":"为什么不用 OFFSET","anchor":"heading-2"},{"level":2,"text":"实现","anchor":"heading-3"}]',
 160, 60, 'PUBLISHED', 'PUBLIC', 0, 1, 2,
 '2026-10-21 03:00:00.000', '2026-10-20 21:00:00.000', '2026-10-21 03:00:00.000', NULL),

(2003, 1001, @cat_essay, '一次缓存脏读的复盘',
 'debug-cache-stale-read', '详情缓存命中后计数被冻结，根因是计数更新没有推进版本号。',
 'debug/covers/cache-incident.png',
 '# 一次缓存脏读的复盘\n\n详情缓存命中后，点赞数一直停在旧值。\n\n## 根因\n\n计数更新有意不递增 version，缓存 key 因此没有变化。\n\n## 修复\n\n缓存命中后覆盖新鲜的四个计数，计数更新仍不推进版本。',
 '<h1 id="heading-1">一次缓存脏读的复盘</h1><p>详情缓存命中后，点赞数一直停在旧值。</p><h2 id="heading-2">根因</h2><p>计数更新有意不递增 version，缓存 key 因此没有变化。</p><h2 id="heading-3">修复</h2><p>缓存命中后覆盖新鲜的四个计数，计数更新仍不推进版本。</p>',
 '一次缓存脏读的复盘\n详情缓存命中后，点赞数一直停在旧值。\n根因\n计数更新有意不递增 version，缓存 key 因此没有变化。\n修复\n缓存命中后覆盖新鲜的四个计数，计数更新仍不推进版本。',
 '[{"level":1,"text":"一次缓存脏读的复盘","anchor":"heading-1"},{"level":2,"text":"根因","anchor":"heading-2"},{"level":2,"text":"修复","anchor":"heading-3"}]',
 150, 60, 'PUBLISHED', 'PUBLIC', 1, 0, 2,
 '2026-10-22 04:00:00.000', '2026-10-21 20:00:00.000', '2026-10-22 04:00:00.000', NULL),

(2004, 1002, @cat_backend, 'Spring Security 方法级权限的坑',
 'debug-security-method-403', '方法级权限拒绝被全局异常处理器吞掉，403 变成了 500。',
 NULL,
 '# Spring Security 方法级权限的坑\n\n路由门禁通过，方法级注解却返回 500。\n\n## 403 变成 500\n\nAccessDeniedException 落到了兜底异常处理。\n\n## 处理顺序\n\n先处理认证异常，再处理权限异常，最后才是兜底。',
 '<h1 id="heading-1">Spring Security 方法级权限的坑</h1><p>路由门禁通过，方法级注解却返回 500。</p><h2 id="heading-2">403 变成 500</h2><p>AccessDeniedException 落到了兜底异常处理。</p><h2 id="heading-3">处理顺序</h2><p>先处理认证异常，再处理权限异常，最后才是兜底。</p>',
 'Spring Security 方法级权限的坑\n路由门禁通过，方法级注解却返回 500。\n403 变成 500\nAccessDeniedException 落到了兜底异常处理。\n处理顺序\n先处理认证异常，再处理权限异常，最后才是兜底。',
 '[{"level":1,"text":"Spring Security 方法级权限的坑","anchor":"heading-1"},{"level":2,"text":"403 变成 500","anchor":"heading-2"},{"level":2,"text":"处理顺序","anchor":"heading-3"}]',
 140, 60, 'PUBLISHED', 'PUBLIC', 0, 1, 1,
 '2026-10-23 05:00:00.000', '2026-10-22 22:00:00.000', '2026-10-23 05:00:00.000', NULL),

(2005, 1003, 9001, 'SSE 在单实例与多实例下的取舍',
 'debug-sse-single-vs-cluster', '单实例内存注册表足够简单，多实例再引入 Redis Pub/Sub。',
 NULL,
 '# SSE 在单实例与多实例下的取舍\n\n单实例用内存注册表维护连接，成本最低。\n\n## 单实例注册表\n\n每用户最多 3 条连接，15 秒心跳。\n\n## 多实例演进\n\n把事件投递到 Redis Pub/Sub，再广播到各实例。\n\n![SSE 流程](/media/debug/content/sse-flow.png)',
 '<h1 id="heading-1">SSE 在单实例与多实例下的取舍</h1><p>单实例用内存注册表维护连接，成本最低。</p><h2 id="heading-2">单实例注册表</h2><p>每用户最多 3 条连接，15 秒心跳。</p><h2 id="heading-3">多实例演进</h2><p>把事件投递到 Redis Pub/Sub，再广播到各实例。</p><img src="/media/debug/content/sse-flow.png" alt="SSE 流程">',
 'SSE 在单实例与多实例下的取舍\n单实例用内存注册表维护连接，成本最低。\n单实例注册表\n每用户最多 3 条连接，15 秒心跳。\n多实例演进\n把事件投递到 Redis Pub/Sub，再广播到各实例。',
 '[{"level":1,"text":"SSE 在单实例与多实例下的取舍","anchor":"heading-1"},{"level":2,"text":"单实例注册表","anchor":"heading-2"},{"level":2,"text":"多实例演进","anchor":"heading-3"}]',
 170, 60, 'PUBLISHED', 'PUBLIC', 0, 1, 2,
 '2026-10-24 06:00:00.000', '2026-10-23 21:00:00.000', '2026-10-24 06:00:00.000', NULL),

(2006, 1004, @cat_database, 'Redis 滑动窗口限流实现',
 'debug-redis-sliding-window', '固定窗口在边界会放行两倍流量，改用 ZSET + Lua 的滑动窗口。',
 NULL,
 '# Redis 滑动窗口限流实现\n\n固定窗口在整点边界会出现双倍突发。\n\n## 为什么固定窗口不够\n\n窗口切换时前后两段各放行一次配额。\n\n## Lua 实现\n\n一次 EVALSHA 完成清理、计数与写入，保证原子性。',
 '<h1 id="heading-1">Redis 滑动窗口限流实现</h1><p>固定窗口在整点边界会出现双倍突发。</p><h2 id="heading-2">为什么固定窗口不够</h2><p>窗口切换时前后两段各放行一次配额。</p><h2 id="heading-3">Lua 实现</h2><p>一次 EVALSHA 完成清理、计数与写入，保证原子性。</p>',
 'Redis 滑动窗口限流实现\n固定窗口在整点边界会出现双倍突发。\n为什么固定窗口不够\n窗口切换时前后两段各放行一次配额。\nLua 实现\n一次 EVALSHA 完成清理、计数与写入，保证原子性。',
 '[{"level":1,"text":"Redis 滑动窗口限流实现","anchor":"heading-1"},{"level":2,"text":"为什么固定窗口不够","anchor":"heading-2"},{"level":2,"text":"Lua 实现","anchor":"heading-3"}]',
 130, 60, 'PUBLISHED', 'UNLISTED', 0, 1, 1,
 '2026-10-25 07:00:00.000', '2026-10-24 20:00:00.000', '2026-10-25 07:00:00.000', NULL),

(2007, 1005, @cat_backend, '积分账本的幂等与反转',
 'debug-points-ledger-idempotency', '不可变流水 + 幂等键 + 账户行锁，余额只是快照。',
 NULL,
 '# 积分账本的幂等与反转\n\n余额是快照，真相在流水。\n\n## 幂等键\n\n同一业务键只允许写入一次，重复请求回读原流水。\n\n## 反转不删流水\n\n撤销时追加反向记录，不修改历史。',
 '<h1 id="heading-1">积分账本的幂等与反转</h1><p>余额是快照，真相在流水。</p><h2 id="heading-2">幂等键</h2><p>同一业务键只允许写入一次，重复请求回读原流水。</p><h2 id="heading-3">反转不删流水</h2><p>撤销时追加反向记录，不修改历史。</p>',
 '积分账本的幂等与反转\n余额是快照，真相在流水。\n幂等键\n同一业务键只允许写入一次，重复请求回读原流水。\n反转不删流水\n撤销时追加反向记录，不修改历史。',
 '[{"level":1,"text":"积分账本的幂等与反转","anchor":"heading-1"},{"level":2,"text":"幂等键","anchor":"heading-2"},{"level":2,"text":"反转不删流水","anchor":"heading-3"}]',
 120, 60, 'PUBLISHED', 'PRIVATE', 0, 1, 1,
 '2026-10-24 06:30:00.000', '2026-10-23 20:00:00.000', '2026-10-24 06:30:00.000', NULL),

(2008, 1001, @cat_backend, 'AI 工具调用笔记（草稿）',
 'debug-ai-tool-notes-draft', '记录工具调用的两轮编排与只读白名单。',
 NULL,
 '# AI 工具调用笔记（草稿）\n\n还在整理。\n\n## 待验证\n\n- 工具结果截断\n- 两轮调用上限',
 '<h1 id="heading-1">AI 工具调用笔记（草稿）</h1><p>还在整理。</p><h2 id="heading-2">待验证</h2><ul><li>工具结果截断</li><li>两轮调用上限</li></ul>',
 'AI 工具调用笔记（草稿）\n还在整理。\n待验证\n工具结果截断\n两轮调用上限',
 '[{"level":1,"text":"AI 工具调用笔记（草稿）","anchor":"heading-1"},{"level":2,"text":"待验证","anchor":"heading-2"}]',
 60, 60, 'DRAFT', 'PUBLIC', 0, 1, 0,
 NULL, '2026-10-26 01:00:00.000', '2026-10-26 01:10:00.000', NULL),

(2009, 1002, @cat_essay, '旧版迁移说明',
 'debug-legacy-migration-note', '旧 Node 服务的迁移说明，已归档。',
 NULL,
 '# 旧版迁移说明\n\n本文记录旧服务的迁移步骤，现已归档。\n\n## 归档原因\n\n新后端已经接管全部业务。',
 '<h1 id="heading-1">旧版迁移说明</h1><p>本文记录旧服务的迁移步骤，现已归档。</p><h2 id="heading-2">归档原因</h2><p>新后端已经接管全部业务。</p>',
 '旧版迁移说明\n本文记录旧服务的迁移步骤，现已归档。\n归档原因\n新后端已经接管全部业务。',
 '[{"level":1,"text":"旧版迁移说明","anchor":"heading-1"},{"level":2,"text":"归档原因","anchor":"heading-2"}]',
 90, 60, 'ARCHIVED', 'PUBLIC', 0, 0, 1,
 '2026-10-19 02:00:00.000', '2026-10-18 22:00:00.000', '2026-10-25 01:00:00.000', NULL),

(2010, 1003, 9002, '压测记录（已删除）',
 'debug-loadtest-record-deleted', '一次接口压测记录，已进入回收站。',
 NULL,
 '# 压测记录（已删除）\n\n记录单接口 P95 与错误率。\n\n## 数据\n\n- 并发 50，P95 180ms\n- 错误率 0%',
 '<h1 id="heading-1">压测记录（已删除）</h1><p>记录单接口 P95 与错误率。</p><h2 id="heading-2">数据</h2><ul><li>并发 50，P95 180ms</li><li>错误率 0%</li></ul>',
 '压测记录（已删除）\n记录单接口 P95 与错误率。\n数据\n并发 50，P95 180ms\n错误率 0%',
 '[{"level":1,"text":"压测记录（已删除）","anchor":"heading-1"},{"level":2,"text":"数据","anchor":"heading-2"}]',
 70, 60, 'PUBLISHED', 'PUBLIC', 0, 1, 1,
 '2026-10-26 09:00:00.000', '2026-10-26 02:00:00.000', '2026-10-26 12:00:00.000', '2026-10-26 12:00:00.000');

-- -----------------------------------------------------------------------------
-- 9. 文章-标签（article_tags）
-- -----------------------------------------------------------------------------
INSERT INTO article_tags (article_id, tag_id, created_at) VALUES
(2001, @tag_java,      '2026-10-20 02:00:00.000'),
(2001, @tag_spring,    '2026-10-20 02:00:00.000'),
(2001, @tag_arch,      '2026-10-20 02:00:00.000'),
(2002, @tag_mysql,     '2026-10-21 03:00:00.000'),
(2002, @tag_mybatis,   '2026-10-21 03:00:00.000'),
(2003, @tag_redis,     '2026-10-22 04:00:00.000'),
(2003, @tag_arch,      '2026-10-22 04:00:00.000'),
(2003, 9103,           '2026-10-22 04:00:00.000'),
(2004, @tag_java,      '2026-10-23 05:00:00.000'),
(2004, @tag_spring,    '2026-10-23 05:00:00.000'),
(2005, @tag_redis,     '2026-10-24 06:00:00.000'),
(2005, 9101,           '2026-10-24 06:00:00.000'),
(2005, @tag_arch,      '2026-10-24 06:00:00.000'),
(2006, @tag_redis,     '2026-10-25 07:00:00.000'),
(2006, 9102,           '2026-10-25 07:00:00.000'),
(2007, @tag_arch,      '2026-10-24 06:30:00.000'),
(2007, 9102,           '2026-10-24 06:30:00.000'),
(2008, 9105,           '2026-10-26 01:00:00.000'),
(2008, @tag_java,      '2026-10-26 01:00:00.000'),
(2009, 9105,           '2026-10-19 02:00:00.000'),
(2009, @tag_interview, '2026-10-19 02:00:00.000'),
(2010, 9103,           '2026-10-26 09:00:00.000'),
(2010, @tag_mysql,     '2026-10-26 09:00:00.000');

-- -----------------------------------------------------------------------------
-- 10. 修订历史（article_revisions）——最新一版与 articles 当前内容一致
-- -----------------------------------------------------------------------------
INSERT INTO article_revisions (id, article_id, revision_no, editor_id, title, summary,
                               content_md, change_note, created_at)
VALUES
(2101, 2001, 1, 1001, '用模块化单体重写博客后端（初稿）', '初稿：先搭边界。',
 '# 用模块化单体重写博客后端\n\n先搭边界，再谈拆分。', '初稿', '2026-10-19 20:05:00.000'),
(2102, 2001, 2, 1001, '用模块化单体重写博客后端：边界、端口与事务',
 '从包边界、跨模块端口到事务落点，记录一次可拆分的单体重构。',
 '# 用模块化单体重写博客后端\n\n微服务不是默认答案。单人维护、单库部署的博客，更适合先把边界做清楚。\n\n## 三条硬规则\n\n- api 层不碰 Mapper\n- 跨模块只走 app 端口或领域事件\n- 事务只落在 app 层\n\n![模块依赖](/media/debug/content/module-flow.png)\n\n## 结果\n\n同一套代码既能在单体里运行，也保留了未来拆分的缝。',
 '补充三条硬规则与模块依赖图', '2026-10-20 01:50:00.000'),
(2103, 2002, 1, 1002, 'MySQL 行构造器游标分页实战',
 '用 (published_at, id) 行构造器替代 OFFSET，避免深翻页抖动。',
 '# MySQL 行构造器游标分页实战\n\n深翻页的 OFFSET 会随着页数增长而变慢。\n\n## 为什么不用 OFFSET\n\nOFFSET 需要扫描并丢弃前 N 行，数据量大时成本不可控。\n\n## 实现\n\n先用 (published_at, id) 定位上一页最后一行，再按索引继续取。\n\n![执行计划](/media/debug/content/cursor-table.png)',
 '初稿', '2026-10-21 02:50:00.000'),
(2104, 2003, 1, 1001, '一次缓存脏读的复盘（初稿）', '初稿：先记录现象。',
 '# 一次缓存脏读的复盘\n\n先记录现象。', '初稿', '2026-10-21 20:05:00.000'),
(2105, 2003, 2, 1001, '一次缓存脏读的复盘',
 '详情缓存命中后计数被冻结，根因是计数更新没有推进版本号。',
 '# 一次缓存脏读的复盘\n\n详情缓存命中后，点赞数一直停在旧值。\n\n## 根因\n\n计数更新有意不递增 version，缓存 key 因此没有变化。\n\n## 修复\n\n缓存命中后覆盖新鲜的四个计数，计数更新仍不推进版本。',
 '补充根因与修复', '2026-10-22 03:50:00.000'),
(2106, 2004, 1, 1002, 'Spring Security 方法级权限的坑',
 '方法级权限拒绝被全局异常处理器吞掉，403 变成了 500。',
 '# Spring Security 方法级权限的坑\n\n路由门禁通过，方法级注解却返回 500。\n\n## 403 变成 500\n\nAccessDeniedException 落到了兜底异常处理。\n\n## 处理顺序\n\n先处理认证异常，再处理权限异常，最后才是兜底。',
 '初稿', '2026-10-22 22:30:00.000'),
(2107, 2005, 1, 1003, 'SSE 在单实例与多实例下的取舍',
 '单实例内存注册表足够简单，多实例再引入 Redis Pub/Sub。',
 '# SSE 在单实例与多实例下的取舍\n\n单实例用内存注册表维护连接，成本最低。\n\n## 单实例注册表\n\n每用户最多 3 条连接，15 秒心跳。\n\n## 多实例演进\n\n把事件投递到 Redis Pub/Sub，再广播到各实例。\n\n![SSE 流程](/media/debug/content/sse-flow.png)',
 '初稿', '2026-10-24 05:50:00.000'),
(2108, 2006, 1, 1004, 'Redis 滑动窗口限流实现',
 '固定窗口在边界会放行两倍流量，改用 ZSET + Lua 的滑动窗口。',
 '# Redis 滑动窗口限流实现\n\n固定窗口在整点边界会出现双倍突发。\n\n## 为什么固定窗口不够\n\n窗口切换时前后两段各放行一次配额。\n\n## Lua 实现\n\n一次 EVALSHA 完成清理、计数与写入，保证原子性。',
 '初稿', '2026-10-25 06:50:00.000'),
(2109, 2007, 1, 1005, '积分账本的幂等与反转',
 '不可变流水 + 幂等键 + 账户行锁，余额只是快照。',
 '# 积分账本的幂等与反转\n\n余额是快照，真相在流水。\n\n## 幂等键\n\n同一业务键只允许写入一次，重复请求回读原流水。\n\n## 反转不删流水\n\n撤销时追加反向记录，不修改历史。',
 '初稿', '2026-10-24 06:20:00.000'),
(2110, 2008, 1, 1001, 'AI 工具调用笔记（草稿）',
 '记录工具调用的两轮编排与只读白名单。',
 '# AI 工具调用笔记（草稿）\n\n还在整理。\n\n## 待验证\n\n- 工具结果截断\n- 两轮调用上限',
 '草稿', '2026-10-26 01:10:00.000'),
(2111, 2009, 1, 1002, '旧版迁移说明',
 '旧 Node 服务的迁移说明，已归档。',
 '# 旧版迁移说明\n\n本文记录旧服务的迁移步骤，现已归档。\n\n## 归档原因\n\n新后端已经接管全部业务。',
 '归档前快照', '2026-10-18 22:10:00.000'),
(2112, 2010, 1, 1003, '压测记录（已删除）',
 '一次接口压测记录，已进入回收站。',
 '# 压测记录（已删除）\n\n记录单接口 P95 与错误率。\n\n## 数据\n\n- 并发 50，P95 180ms\n- 错误率 0%',
 '压测记录', '2026-10-26 08:50:00.000');

-- -----------------------------------------------------------------------------
-- 11. 评论（comments）——先根后子，root_id/parent_id 自洽
-- -----------------------------------------------------------------------------
INSERT INTO comments (id, article_id, user_id, guest_name, root_id, parent_id, reply_to_user_id,
                      content, status, like_count, ip, user_agent, created_at, updated_at, deleted_at)
VALUES
(3001, 2001, 1003, NULL, NULL, NULL, NULL,
 '这篇的边界划分很清晰，事务落点那段很有共鸣。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-20 03:00:00.000', '2026-10-20 03:00:00.000', NULL),
(3002, 2001, NULL, '路过的猫', NULL, NULL, NULL,
 '想问下模块之间的事件是同步还是异步？', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (X11; Linux x86_64) DebugBrowser/1.0', '2026-10-20 04:00:00.000', '2026-10-20 04:00:00.000', NULL),
(3003, 2002, 1004, NULL, NULL, NULL, NULL,
 '游标分页在并发插入时确实更稳。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-21 04:00:00.000', '2026-10-21 04:00:00.000', NULL),
(3004, 2002, 1005, NULL, NULL, NULL, NULL,
 'EXPLAIN 那部分能再展开吗？', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) DebugBrowser/1.0', '2026-10-21 05:00:00.000', '2026-10-21 05:00:00.000', NULL),
(3005, 2003, 1004, NULL, NULL, NULL, NULL,
 '缓存 key 带版本这个做法我们也用过。', 'PENDING', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-22 05:00:00.000', '2026-10-22 05:00:00.000', NULL),
(3006, 2004, 1003, NULL, NULL, NULL, NULL,
 '广告：加群领取资料', 'REJECTED', 0, '127.0.0.1',
 'curl/8.5.0', '2026-10-23 06:00:00.000', '2026-10-23 06:00:00.000', NULL),
(3007, 2005, 1001, NULL, NULL, NULL, NULL,
 '单实例 SSE 的取舍写得很实在。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-24 07:00:00.000', '2026-10-26 02:00:00.000', '2026-10-26 02:00:00.000'),
(3008, 2001, 1004, NULL, NULL, NULL, NULL,
 '补充一点：端口返回值不要暴露 api VO。', 'HIDDEN', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-23 06:30:00.000', '2026-10-24 01:00:00.000', NULL),
(3019, 2010, 1003, NULL, NULL, NULL, NULL,
 '压测数据已归档。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-26 10:00:00.000', '2026-10-26 10:00:00.000', NULL),
(3011, 2001, 1002, NULL, 3001, 3001, 1003,
 '同意，尤其是事务边界。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-20 03:30:00.000', '2026-10-20 03:30:00.000', NULL),
(3012, 2001, 1003, NULL, 3001, 3011, 1002,
 '对，跨模块调用尽量放在事务外。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-20 03:40:00.000', '2026-10-20 03:40:00.000', NULL),
(3013, 2002, 1002, NULL, 3003, 3003, 1004,
 '可以看下 idx_feed 的复合索引。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-21 04:30:00.000', '2026-10-21 04:30:00.000', NULL),
(3014, 2002, 1004, NULL, 3002, 3002, NULL,
 '事件是 AFTER_COMMIT 异步的。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-21 05:30:00.000', '2026-10-21 05:30:00.000', NULL),
(3015, 2003, NULL, '匿名访客', 3005, 3005, 1004,
 '同问，期待展开。', 'PENDING', 0, '127.0.0.1',
 'Mozilla/5.0 (X11; Linux x86_64) DebugBrowser/1.0', '2026-10-22 06:00:00.000', '2026-10-22 06:00:00.000', NULL),
(3016, 2003, 1001, NULL, 3005, 3005, 1004,
 '等下一篇专门写。', 'PENDING', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-22 06:30:00.000', '2026-10-22 06:30:00.000', NULL),
(3017, 2005, 1002, NULL, 3007, 3007, 1001,
 '父评论删了，子评论还保留。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-26 03:00:00.000', '2026-10-26 03:00:00.000', NULL),
(3018, 2001, NULL, '游客小K', 3002, 3002, NULL,
 '异步事件要注意幂等。', 'APPROVED', 0, '127.0.0.1',
 'Mozilla/5.0 (X11; Linux x86_64) DebugBrowser/1.0', '2026-10-20 05:00:00.000', '2026-10-20 05:00:00.000', NULL);

-- -----------------------------------------------------------------------------
-- 12. 评论点赞（comment_likes）与文章点赞/收藏（article_reactions）
-- -----------------------------------------------------------------------------
INSERT INTO comment_likes (comment_id, user_id, created_at) VALUES
(3001, 1002, '2026-10-20 03:20:00.000'),
(3001, 1004, '2026-10-20 03:25:00.000'),
(3003, 1001, '2026-10-21 04:10:00.000'),
(3011, 1003, '2026-10-20 03:35:00.000'),
(3019, 1001, '2026-10-26 10:10:00.000');

INSERT INTO article_reactions (id, article_id, user_id, type, created_at) VALUES
(4001, 2001, 1002, 'LIKE',     '2026-10-20 03:10:00.000'),
(4002, 2001, 1003, 'LIKE',     '2026-10-20 03:15:00.000'),
(4003, 2001, 1004, 'LIKE',     '2026-10-20 05:10:00.000'),
(4004, 2001, 1005, 'LIKE',     '2026-10-21 06:00:00.000'),
(4005, 2001, 1003, 'FAVORITE', '2026-10-20 03:16:00.000'),
(4006, 2001, 1004, 'FAVORITE', '2026-10-20 05:11:00.000'),
(4007, 2002, 1001, 'LIKE',     '2026-10-21 06:10:00.000'),
(4008, 2002, 1003, 'LIKE',     '2026-10-21 06:20:00.000'),
(4009, 2002, 1005, 'LIKE',     '2026-10-22 01:00:00.000'),
(4010, 2002, 1001, 'FAVORITE', '2026-10-21 06:11:00.000'),
(4011, 2003, 1002, 'LIKE',     '2026-10-22 05:10:00.000'),
(4012, 2003, 1003, 'LIKE',     '2026-10-22 05:20:00.000'),
(4013, 2003, 1004, 'FAVORITE', '2026-10-22 05:30:00.000'),
(4014, 2004, 1003, 'LIKE',     '2026-10-23 07:00:00.000'),
(4015, 2005, 1001, 'LIKE',     '2026-10-24 08:00:00.000'),
(4016, 2005, 1004, 'LIKE',     '2026-10-24 08:10:00.000'),
(4017, 2005, 1003, 'FAVORITE', '2026-10-24 08:20:00.000'),
(4018, 2007, 1001, 'LIKE',     '2026-10-24 07:00:00.000'),
(4019, 2009, 1002, 'LIKE',     '2026-10-20 01:00:00.000'),
(4020, 2010, 1003, 'LIKE',     '2026-10-26 10:20:00.000');

-- -----------------------------------------------------------------------------
-- 13. 留言板（guestbook_messages）
-- -----------------------------------------------------------------------------
INSERT INTO guestbook_messages (id, user_id, nickname, content, status, ip, user_agent, created_at, updated_at, deleted_at) VALUES
(4501, 1003, '陈小满', '博客的模块划分文章很有帮助。', 'APPROVED', '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-21 03:00:00.000', '2026-10-21 03:00:00.000', NULL),
(4502, NULL, '访客A', '希望增加 RSS。', 'APPROVED', '127.0.0.1',
 'Mozilla/5.0 (X11; Linux x86_64) DebugBrowser/1.0', '2026-10-22 03:00:00.000', '2026-10-22 03:00:00.000', NULL),
(4503, 1004, '周行', '积分商城的马克杯还有货吗？', 'PENDING', '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-25 13:00:00.000', '2026-10-25 13:00:00.000', NULL),
(4504, 1001, '文栈管理员', '测试留言，请忽略。', 'REJECTED', '127.0.0.1',
 'curl/8.5.0', '2026-10-23 03:00:00.000', '2026-10-23 03:00:00.000', NULL),
(4505, 1002, '林墨', '已删除的旧留言。', 'APPROVED', '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-20 03:00:00.000', '2026-10-24 03:00:00.000', '2026-10-24 03:00:00.000');

-- -----------------------------------------------------------------------------
-- 14. 站内通知（notifications）——dedupe_key 唯一；已读/未读混合
-- -----------------------------------------------------------------------------
INSERT INTO notifications (id, recipient_id, actor_id, type, title, content, link_type, link_id,
                           dedupe_key, is_read, read_at, created_at)
VALUES
(4601, 1001, 1003, 'ARTICLE_COMMENTED', '你的文章收到新评论', '陈小满 评论了《用模块化单体重写博客后端》',
 'ARTICLE', '2001', 'comment:3001:article', 0, NULL, '2026-10-20 03:00:10.000'),
(4602, 1002, 1003, 'COMMENT_REPLY', '有人回复了你的评论', '陈小满 回复了你在《用模块化单体重写博客后端》下的评论',
 'COMMENT', '3011', 'comment:3011:reply', 0, NULL, '2026-10-20 03:30:10.000'),
(4603, 1003, NULL, 'COMMENT_APPROVED', '评论已通过审核', '你的评论已通过审核并展示',
 'COMMENT', '3001', 'comment:3001:approved', 1, '2026-10-20 06:00:00.000', '2026-10-20 05:10:00.000'),
(4604, 1002, 1001, 'ORDER_APPROVED', '兑换订单已通过审核', '订单 DBG2026102500000000003 已通过审核',
 'ORDER', '7003', 'order:7003:approved', 0, NULL, '2026-10-25 10:00:10.000'),
(4605, 1004, NULL, 'ORDER_COMPLETED', '兑换订单已完成', '订单 DBG2026102400000000002 已完成',
 'ORDER', '7002', 'order:7002:completed', 1, '2026-10-26 11:00:00.000', '2026-10-26 10:00:10.000'),
(4606, 1005, 1001, 'POINTS_ADJUSTED', '积分已调整', '管理员为你增加了 2500 积分',
 'POINTS', '5526', 'points:adjust:5526', 0, NULL, '2026-10-22 02:00:10.000'),
(4607, 1006, NULL, 'SYSTEM', '欢迎加入文栈博客', '完善个人资料，开始你的第一篇评论吧。',
 'SYSTEM', NULL, NULL, 0, NULL, '2026-10-25 09:45:10.000'),
(4608, 1005, 1001, 'ORDER_SHIPPED', '兑换订单已发货', '订单 DBG2026102500000000004 已发货，物流单号 SF1234567890',
 'ORDER', '7004', 'order:7004:shipped', 0, NULL, '2026-10-26 09:00:10.000');

-- -----------------------------------------------------------------------------
-- 15. 积分账户（points_accounts）——余额/累计在末尾与流水对账
-- -----------------------------------------------------------------------------
INSERT INTO points_accounts (user_id, balance, total_earned, total_spent, version, created_at, updated_at) VALUES
(1001, 1215, 1215, 0,    5, '2026-10-18 02:00:00.000', '2026-10-27 01:00:00.000'),
(1002, 5,    125,  120,  4, '2026-10-21 01:00:00.000', '2026-10-25 09:00:00.000'),
(1003, 105,  375,  270,  8, '2026-10-20 02:05:00.000', '2026-10-26 12:10:00.000'),
(1004, 225,  655,  430,  8, '2026-10-21 02:00:00.000', '2026-10-25 11:30:00.000'),
(1005, 205,  2605, 2400, 4, '2026-10-22 02:00:00.000', '2026-10-25 12:00:00.000'),
(1006, 0,    0,    0,    0, '2026-10-25 09:45:00.000', '2026-10-25 09:45:00.000'),
(1007, 0,    0,    0,    0, '2026-10-23 01:50:00.000', '2026-10-23 01:50:00.000');

-- -----------------------------------------------------------------------------
-- 16. 积分流水（points_ledger）——每笔 balance_after 与上一条严格衔接
-- -----------------------------------------------------------------------------
INSERT INTO points_ledger (id, user_id, amount, balance_after, type, biz_type, biz_id,
                           idempotency_key, operator_id, remark, created_at)
VALUES
(5501, 1001, 1000, 1000, 'ADMIN_ADJUST',            'SYSTEM',  NULL, 'debug:admin_adjust:1001', 1001, '管理员初始积分',       '2026-10-18 02:00:00.000'),
(5502, 1001, 100,  1100, 'ARTICLE_PUBLISH',         'ARTICLE', '2001', 'publish:2001',           NULL, '发布文章奖励',         '2026-10-20 02:10:00.000'),
(5503, 1001, 10,   1110, 'LIKE_RECEIVED',           'LIKE',    '2001', 'like:2001:1002',         NULL, '文章获赞',             '2026-10-20 03:10:10.000'),
(5504, 1001, 100,  1210, 'ARTICLE_PUBLISH',         'ARTICLE', '2003', 'publish:2003',           NULL, '发布文章奖励',         '2026-10-22 04:10:00.000'),
(5505, 1001, 5,    1215, 'DAILY_LOGIN',             'SYSTEM',  NULL, 'daily_login:1001:2026-10-27', NULL, '每日登录',         '2026-10-27 01:00:00.000'),

(5506, 1002, 5,    5,    'DAILY_LOGIN',             'SYSTEM',  NULL, 'daily_login:1002:2026-10-21', NULL, '每日登录',         '2026-10-21 01:00:00.000'),
(5507, 1002, 100,  105,  'ARTICLE_PUBLISH',         'ARTICLE', '2002', 'publish:2002',           NULL, '发布文章奖励',         '2026-10-21 03:10:00.000'),
(5508, 1002, 20,   125,  'COMMENT_APPROVED',        'COMMENT', '3013', 'comment_approved:3013',  NULL, '评论通过审核',         '2026-10-21 05:00:00.000'),
(5509, 1002, -120, 5,    'REDEEM',                  'ORDER',   '7003', 'redeem:debug-order-7003', NULL, '兑换文栈定制马克杯',  '2026-10-25 09:00:00.000'),

(5510, 1003, 200,  200,  'ADMIN_ADJUST',            'SYSTEM',  NULL, 'debug:admin_adjust:1003', 1001, '活动补偿积分',         '2026-10-20 02:05:00.000'),
(5511, 1003, 5,    205,  'DAILY_LOGIN',             'SYSTEM',  NULL, 'daily_login:1003:2026-10-20', NULL, '每日登录',         '2026-10-20 02:20:00.000'),
(5512, 1003, 20,   225,  'COMMENT_APPROVED',        'COMMENT', '3001', 'comment_approved:3001',  NULL, '评论通过审核',         '2026-10-20 05:10:00.000'),
(5513, 1003, -120, 105,  'REDEEM',                  'ORDER',   '7001', 'redeem:debug-order-7001', NULL, '兑换文栈定制马克杯',  '2026-10-21 10:00:00.000'),
(5514, 1003, -50,  55,   'REDEEM',                  'ORDER',   '7005', 'redeem:debug-order-7005', NULL, '兑换旧版贴纸',        '2026-10-22 10:00:00.000'),
(5515, 1003, 50,   105,  'REFUND',                  'ORDER',   '7005', 'refund:debug-order-7005', NULL, '订单取消退款',        '2026-10-22 10:05:00.000'),
(5516, 1003, 100,  205,  'ARTICLE_PUBLISH',         'ARTICLE', '2010', 'publish:2010',           NULL, '发布文章奖励',         '2026-10-26 09:10:00.000'),
(5517, 1003, -100, 105,  'ARTICLE_PUBLISH_REVERSAL','ARTICLE', '2010', 'publish_reversal:2010',  NULL, '文章删除反转奖励',     '2026-10-26 12:10:00.000'),

(5518, 1004, 500,  500,  'ADMIN_ADJUST',            'SYSTEM',  NULL, 'debug:admin_adjust:1004', 1001, '内测贡献奖励',         '2026-10-21 02:00:00.000'),
(5519, 1004, 5,    505,  'DAILY_LOGIN',             'SYSTEM',  NULL, 'daily_login:1004:2026-10-21', NULL, '每日登录',         '2026-10-21 02:10:00.000'),
(5520, 1004, 10,   515,  'LIKE_RECEIVED',           'LIKE',    '2006', 'like:2006:1005',         NULL, '文章获赞',             '2026-10-23 08:35:00.000'),
(5521, 1004, 20,   535,  'COMMENT_APPROVED',        'COMMENT', '3014', 'comment_approved:3014',  NULL, '评论通过审核',         '2026-10-23 09:00:00.000'),
(5522, 1004, -10,  525,  'LIKE_REVERSED',           'LIKE',    '2006', 'like_reverse:2006:1005', NULL, '点赞被取消，反转奖励', '2026-10-24 02:00:00.000'),
(5523, 1004, -300, 225,  'REDEEM',                  'ORDER',   '7002', 'redeem:debug-order-7002', NULL, '兑换调试体验官称号',  '2026-10-24 11:00:00.000'),
(5524, 1004, -120, 105,  'REDEEM',                  'ORDER',   '7006', 'redeem:debug-order-7006', NULL, '兑换文栈定制马克杯',  '2026-10-25 11:00:00.000'),
(5525, 1004, 120,  225,  'REFUND',                  'ORDER',   '7006', 'refund:debug-order-7006', NULL, '订单驳回退款',        '2026-10-25 11:30:00.000'),

(5526, 1005, 2500, 2500, 'ADMIN_ADJUST',            'SYSTEM',  NULL, 'debug:admin_adjust:1005', 1001, '文章被推荐奖励',       '2026-10-22 02:00:00.000'),
(5527, 1005, 100,  2600, 'ARTICLE_PUBLISH',         'ARTICLE', '2007', 'publish:2007',           NULL, '发布文章奖励',         '2026-10-24 06:10:00.000'),
(5528, 1005, 5,    2605, 'DAILY_LOGIN',             'SYSTEM',  NULL, 'daily_login:1005:2026-10-24', NULL, '每日登录',         '2026-10-24 06:20:00.000'),
(5529, 1005, -2400, 205, 'REDEEM',                  'ORDER',   '7004', 'redeem:debug-order-7004', NULL, '兑换机械键盘键帽 x3', '2026-10-25 12:00:00.000');

-- -----------------------------------------------------------------------------
-- 17. 积分商品（points_goods）——sold_count = 未取消/未驳回订单数量之和
-- -----------------------------------------------------------------------------
INSERT INTO points_goods (id, name, type, title_id, description, image_key, points_price, stock,
                          sold_count, status, sort_order, version, created_at, updated_at, deleted_at)
VALUES
(6001, '文栈定制马克杯', 'PHYSICAL', NULL, '陶瓷马克杯，350ml，包邮。', 'debug/goods/mug.png',
 120, 18, 2, 'ON_SALE', 10, 3, '2026-10-21 02:30:00.000', '2026-10-25 10:00:00.000', NULL),
(6002, '调试体验官称号', 'TITLE', 9002, '获得后可在个人主页展示“调试体验官”。', 'debug/goods/title-badge.png',
 300, NULL, 1, 'ON_SALE', 20, 2, '2026-10-21 02:30:00.000', '2026-10-26 10:00:00.000', NULL),
(6003, '机械键盘键帽', 'PHYSICAL', NULL, 'PBT 键帽一套，共 3 枚。', NULL,
 800, 0, 3, 'ON_SALE', 30, 4, '2026-10-22 02:30:00.000', '2026-10-26 09:00:00.000', NULL),
(6004, '旧版贴纸', 'PHYSICAL', NULL, '绝版贴纸，已下架。', NULL,
 50, 100, 0, 'OFF_SHELF', 40, 1, '2026-10-21 02:30:00.000', '2026-10-24 02:30:00.000', NULL),
(6005, '先行者徽章', 'TITLE', 9003, '早期参与者限定，已删除。', NULL,
 200, NULL, 0, 'OFF_SHELF', 50, 1, '2026-10-22 02:30:00.000', '2026-10-25 02:30:00.000', '2026-10-25 02:30:00.000');

-- -----------------------------------------------------------------------------
-- 18. 积分订单（points_orders）——覆盖六种状态；快照字段与商品一致
-- -----------------------------------------------------------------------------
INSERT INTO points_orders (id, order_no, idempotency_key, user_id, goods_id,
                           goods_name_snapshot, goods_type_snapshot, unit_points, quantity, total_points,
                           status, receiver_name, receiver_phone, receiver_address,
                           logistics_company, logistics_no, user_remark, admin_remark, cancelled_reason,
                           approved_at, shipped_at, completed_at, cancelled_at, version, created_at, updated_at)
VALUES
(7001, 'DBG2026102100000000001', 'debug-order-7001', 1003, 6001,
 '文栈定制马克杯', 'PHYSICAL', 120, 1, 120,
 'PENDING', '陈小满', '13800000003', '上海市浦东新区调试路 1 号',
 NULL, NULL, '请尽快发货', NULL, NULL,
 NULL, NULL, NULL, NULL, 0, '2026-10-21 10:00:00.000', '2026-10-21 10:00:00.000'),
(7002, 'DBG2026102400000000002', 'debug-order-7002', 1004, 6002,
 '调试体验官称号', 'TITLE', 300, 1, 300,
 'COMPLETED', NULL, NULL, NULL,
 NULL, NULL, '想体验一下称号展示', '已发放称号', NULL,
 '2026-10-24 12:00:00.000', '2026-10-25 09:00:00.000', '2026-10-26 10:00:00.000', NULL, 3,
 '2026-10-24 11:00:00.000', '2026-10-26 10:00:00.000'),
(7003, 'DBG2026102500000000003', 'debug-order-7003', 1002, 6001,
 '文栈定制马克杯', 'PHYSICAL', 120, 1, 120,
 'APPROVED', '林墨', '13800000002', '北京市海淀区调试街 2 号',
 NULL, NULL, '工作日送货', '已审核，待发货', NULL,
 '2026-10-25 10:00:00.000', NULL, NULL, NULL, 1,
 '2026-10-25 09:00:00.000', '2026-10-25 10:00:00.000'),
(7004, 'DBG2026102500000000004', 'debug-order-7004', 1005, 6003,
 '机械键盘键帽', 'PHYSICAL', 800, 3, 2400,
 'SHIPPED', '许知远', '13800000005', '广东省深圳市南山区调试大道 5 号',
 '顺丰速运', 'SF1234567890', '三套一起发', '已发货', NULL,
 '2026-10-25 13:00:00.000', '2026-10-26 09:00:00.000', NULL, NULL, 2,
 '2026-10-25 12:00:00.000', '2026-10-26 09:00:00.000'),
(7005, 'DBG2026102200000000005', 'debug-order-7005', 1003, 6004,
 '旧版贴纸', 'PHYSICAL', 50, 1, 50,
 'CANCELLED', '陈小满', '13800000003', '上海市浦东新区调试路 1 号',
 NULL, NULL, '暂时不需要了', NULL, '用户主动取消',
 NULL, NULL, NULL, '2026-10-22 10:05:00.000', 1,
 '2026-10-22 10:00:00.000', '2026-10-22 10:05:00.000'),
(7006, 'DBG2026102500000000006', 'debug-order-7006', 1004, 6001,
 '文栈定制马克杯', 'PHYSICAL', 120, 1, 120,
 'REJECTED', '周行', '13800000004', '浙江省杭州市西湖区调试巷 4 号',
 NULL, NULL, NULL, '库存核对异常，已退款', '管理员驳回',
 NULL, NULL, NULL, '2026-10-25 11:30:00.000', 1,
 '2026-10-25 11:00:00.000', '2026-10-25 11:30:00.000');

-- -----------------------------------------------------------------------------
-- 19. 订单状态日志（points_order_logs）——每单从 PENDING 起按状态机推进
-- -----------------------------------------------------------------------------
INSERT INTO points_order_logs (id, order_id, from_status, to_status, operator_id, remark, created_at) VALUES
(7501, 7001, NULL,      'PENDING',   1003, '用户创建订单', '2026-10-21 10:00:00.000'),
(7502, 7002, NULL,      'PENDING',   1004, '用户创建订单', '2026-10-24 11:00:00.000'),
(7503, 7002, 'PENDING', 'APPROVED',  1001, '审核通过',     '2026-10-24 12:00:00.000'),
(7504, 7002, 'APPROVED','SHIPPED',   1001, '虚拟商品直接发放', '2026-10-25 09:00:00.000'),
(7505, 7002, 'SHIPPED', 'COMPLETED', 1004, '用户确认完成', '2026-10-26 10:00:00.000'),
(7506, 7003, NULL,      'PENDING',   1002, '用户创建订单', '2026-10-25 09:00:00.000'),
(7507, 7003, 'PENDING', 'APPROVED',  1001, '审核通过',     '2026-10-25 10:00:00.000'),
(7508, 7004, NULL,      'PENDING',   1005, '用户创建订单', '2026-10-25 12:00:00.000'),
(7509, 7004, 'PENDING', 'APPROVED',  1001, '审核通过',     '2026-10-25 13:00:00.000'),
(7510, 7004, 'APPROVED','SHIPPED',   1001, '已发货',       '2026-10-26 09:00:00.000'),
(7511, 7005, NULL,      'PENDING',   1003, '用户创建订单', '2026-10-22 10:00:00.000'),
(7512, 7005, 'PENDING', 'CANCELLED', 1003, '用户主动取消', '2026-10-22 10:05:00.000'),
(7513, 7006, NULL,      'PENDING',   1004, '用户创建订单', '2026-10-25 11:00:00.000'),
(7514, 7006, 'PENDING', 'REJECTED',  1001, '库存核对异常，驳回并退款', '2026-10-25 11:30:00.000');

-- -----------------------------------------------------------------------------
-- 20. 用户称号（user_titles）——1004 的“调试体验官”来自已完成订单 7002
-- -----------------------------------------------------------------------------
INSERT INTO user_titles (user_id, title_id, source_order_id, acquired_at) VALUES
(1001, 9001, NULL, '2026-10-18 01:30:00.000'),
(1001, @title_veteran, NULL, '2026-10-26 10:00:00.000'),
(1002, 9002, NULL, '2026-10-19 01:30:00.000'),
(1002, @title_active, NULL, '2026-10-21 03:10:00.000'),
(1003, @title_newcomer, NULL, '2026-10-20 01:30:00.000'),
(1004, 9002, 7002, '2026-10-26 10:00:00.000'),
(1005, @title_newcomer, NULL, '2026-10-24 06:10:00.000'),
(1006, @title_newcomer, NULL, '2026-10-25 09:45:00.000');

-- -----------------------------------------------------------------------------
-- 21. AI 会话 / 消息 / 反馈（ai_sessions、ai_messages、ai_feedbacks）
-- -----------------------------------------------------------------------------
INSERT INTO ai_sessions (id, user_id, channel, title, message_count, last_message_at, created_at, updated_at, deleted_at) VALUES
('01J9Z8K4T5M6N7P8Q9R0S1T2V3', 1003, 'WEB',    '积分账本如何保证幂等？', 3, '2026-10-25 10:01:00.000', '2026-10-25 10:00:00.000', '2026-10-25 10:01:00.000', NULL),
('01J9Z8K4T5M6N7P8Q9R0S1T2W4', NULL, 'WEB',    '怎么联系站长？',         2, '2026-10-25 11:01:00.000', '2026-10-25 11:00:00.000', '2026-10-25 11:01:00.000', NULL),
('01J9Z8K4T5M6N7P8Q9R0S1T2X5', 1004, 'MINIAPP','推荐几篇 MySQL 文章',    3, '2026-10-26 09:01:00.000', '2026-10-26 09:00:00.000', '2026-10-26 09:01:00.000', NULL);

INSERT INTO ai_messages (id, session_id, role, content, status, tool_name, tool_call_id,
                         tool_args_json, tool_result_json, prompt_tokens, completion_tokens,
                         latency_ms, model, created_at, updated_at)
VALUES
(8001, '01J9Z8K4T5M6N7P8Q9R0S1T2V3', 'USER', '积分账本如何保证幂等？', 'COMPLETE',
 NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
 '2026-10-25 10:00:00.000', '2026-10-25 10:00:00.000'),
(8002, '01J9Z8K4T5M6N7P8Q9R0S1T2V3', 'TOOL', '{"items":[{"id":"2007","title":"积分账本的幂等与反转"}]}', 'COMPLETE',
 'search_articles', 'call_debug_001', '{"query":"积分 幂等","size":3}',
 '{"items":[{"id":"2007","title":"积分账本的幂等与反转"}]}', NULL, NULL, 35, 'mock-wzblog-1',
 '2026-10-25 10:00:05.000', '2026-10-25 10:00:05.000'),
(8003, '01J9Z8K4T5M6N7P8Q9R0S1T2V3', 'ASSISTANT',
 '账本用幂等键保证同一业务只写一次，重复请求回读原流水；反转时追加反向记录，不修改历史。可参考《积分账本的幂等与反转》。',
 'COMPLETE', NULL, NULL, NULL, NULL, 120, 180, 900, 'mock-wzblog-1',
 '2026-10-25 10:01:00.000', '2026-10-25 10:01:00.000'),
(8004, '01J9Z8K4T5M6N7P8Q9R0S1T2W4', 'USER', '怎么联系站长？', 'COMPLETE',
 NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
 '2026-10-25 11:00:00.000', '2026-10-25 11:00:00.000'),
(8005, '01J9Z8K4T5M6N7P8Q9R0S1T2W4', 'ASSISTANT',
 '可以在留言板留言，或发送邮件到站长邮箱；紧急问题请在文章评论区说明。',
 'COMPLETE', NULL, NULL, NULL, NULL, 30, 45, 420, 'mock-wzblog-1',
 '2026-10-25 11:01:00.000', '2026-10-25 11:01:00.000'),
(8006, '01J9Z8K4T5M6N7P8Q9R0S1T2X5', 'USER', '推荐几篇 MySQL 文章', 'COMPLETE',
 NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
 '2026-10-26 09:00:00.000', '2026-10-26 09:00:00.000'),
(8007, '01J9Z8K4T5M6N7P8Q9R0S1T2X5', 'TOOL', '{"items":[{"id":"2002","title":"MySQL 行构造器游标分页实战"}]}', 'COMPLETE',
 'search_articles', 'call_debug_002', '{"query":"MySQL 分页","size":3}',
 '{"items":[{"id":"2002","title":"MySQL 行构造器游标分页实战"}]}', NULL, NULL, 28, 'mock-wzblog-1',
 '2026-10-26 09:00:04.000', '2026-10-26 09:00:04.000'),
(8008, '01J9Z8K4T5M6N7P8Q9R0S1T2X5', 'ASSISTANT',
 '推荐《MySQL 行构造器游标分页实战》，里面用 (published_at, id) 替代 OFFSET，并附了执行计划。',
 'COMPLETE', NULL, NULL, NULL, NULL, 80, 120, 780, 'mock-wzblog-1',
 '2026-10-26 09:01:00.000', '2026-10-26 09:01:00.000');

INSERT INTO ai_feedbacks (id, message_id, user_id, rating, comment, created_at) VALUES
(8501, 8003, 1003, 1,  '解释清楚，引用也准确。', '2026-10-25 10:02:00.000'),
(8502, 8005, NULL, 1,  '游客反馈：有帮助。',     '2026-10-25 11:02:00.000'),
(8503, 8008, 1004, -1, '引用的文章有点旧，希望补充新版。', '2026-10-26 09:02:00.000');

-- -----------------------------------------------------------------------------
-- 22. 站点配置（site_configs）——在 V2 seed 上更新 3 项，并新增 1 项页脚配置
-- -----------------------------------------------------------------------------
UPDATE site_configs
SET config_value = '"调试环境已启用，页面数据均为虚构。"',
    updated_by = 1001, updated_at = '2026-10-27 01:30:00.000'
WHERE config_key = 'site.announcement';

UPDATE site_configs
SET config_value = 'true', updated_by = 1001, updated_at = '2026-10-27 01:30:00.000'
WHERE config_key = 'ai.enabled';

UPDATE site_configs
SET config_value = '["广告","加群","代写"]', value_type = 'JSON',
    updated_by = 1001, updated_at = '2026-10-27 01:30:00.000'
WHERE config_key = 'comment.sensitive_words';

INSERT INTO site_configs (id, config_key, config_value, value_type, description, is_public,
                          updated_by, created_at, updated_at)
VALUES
(8501, 'site.footer', '"© 2026 文栈博客 · 本地调试环境"', 'STRING', '页脚文案', 1, 1001,
 '2026-10-27 01:30:00.000', '2026-10-27 01:30:00.000');

-- -----------------------------------------------------------------------------
-- 23. 轮播图（banners）与友链（friend_links）
-- -----------------------------------------------------------------------------
INSERT INTO banners (id, title, image_key, link_url, sort_order, status, start_at, end_at, created_at, updated_at) VALUES
(8801, '模块化单体专题', 'debug/banners/home-01.png', '/articles/debug-modular-monolith', 10, 'ENABLED',
 '2026-10-01 00:00:00.000', '2026-12-31 23:59:59.000', '2026-10-20 02:30:00.000', '2026-10-20 02:30:00.000'),
(8802, '积分商城上新', 'debug/banners/home-02.png', '/points/goods', 20, 'ENABLED',
 '2026-10-15 00:00:00.000', '2026-11-30 23:59:59.000', '2026-10-20 02:30:00.000', '2026-10-20 02:30:00.000'),
(8803, '旧版活动（已停用）', 'debug/banners/home-03.png', NULL, 30, 'DISABLED',
 NULL, NULL, '2026-10-20 02:30:00.000', '2026-10-24 02:30:00.000');

INSERT INTO friend_links (id, blog_name, blog_url, theme, blogger_name, contact, logo_key,
                          sort_order, status, created_at, updated_at)
VALUES
(8901, '码农周刊', 'https://weekly.example.com', '技术周刊', '编辑部', 'hi@weekly.example.com',
 'debug/friends/logo-a.png', 10, 'ENABLED', '2026-10-22 02:30:00.000', '2026-10-22 02:30:00.000'),
(8902, '数据库手记', 'https://db.example.com', 'MySQL / Redis', '阿库', NULL,
 NULL, 20, 'ENABLED', '2026-10-22 02:30:00.000', '2026-10-22 02:30:00.000'),
(8903, '旧站', 'https://old.example.com', '已停更', '旧站长', NULL,
 NULL, 30, 'DISABLED', '2026-10-22 02:30:00.000', '2026-10-24 02:30:00.000');

-- -----------------------------------------------------------------------------
-- 24. 文章日统计（article_daily_stats）——先造阅读曲线，再按明细回填互动
-- -----------------------------------------------------------------------------
INSERT INTO article_daily_stats (article_id, stat_date, view_count)
SELECT a.id, d.stat_date,
       10 + MOD(a.id, 7) + 3 * DATEDIFF(d.stat_date, DATE(a.published_at + INTERVAL 8 HOUR))
FROM articles a
JOIN (
    SELECT DATE '2026-10-18' AS stat_date UNION ALL
    SELECT DATE '2026-10-19' UNION ALL
    SELECT DATE '2026-10-20' UNION ALL
    SELECT DATE '2026-10-21' UNION ALL
    SELECT DATE '2026-10-22' UNION ALL
    SELECT DATE '2026-10-23' UNION ALL
    SELECT DATE '2026-10-24' UNION ALL
    SELECT DATE '2026-10-25' UNION ALL
    SELECT DATE '2026-10-26' UNION ALL
    SELECT DATE '2026-10-27'
) d ON d.stat_date >= DATE(a.published_at + INTERVAL 8 HOUR) AND d.stat_date <= DATE '2026-10-27'
WHERE a.id BETWEEN 2001 AND 2010
  AND a.status = 'PUBLISHED'
  AND a.deleted_at IS NULL
  AND a.visibility IN ('PUBLIC', 'UNLISTED');

UPDATE article_daily_stats s
LEFT JOIN (
    SELECT article_id, DATE(created_at + INTERVAL 8 HOUR) AS day_key, COUNT(*) AS n
    FROM article_reactions WHERE type = 'LIKE' GROUP BY article_id, day_key
) l ON l.article_id = s.article_id AND l.day_key = s.stat_date
LEFT JOIN (
    SELECT article_id, DATE(created_at + INTERVAL 8 HOUR) AS day_key, COUNT(*) AS n
    FROM article_reactions WHERE type = 'FAVORITE' GROUP BY article_id, day_key
) f ON f.article_id = s.article_id AND f.day_key = s.stat_date
LEFT JOIN (
    SELECT article_id, DATE(created_at + INTERVAL 8 HOUR) AS day_key, COUNT(*) AS n
    FROM comments GROUP BY article_id, day_key
) c ON c.article_id = s.article_id AND c.day_key = s.stat_date
SET s.like_count = COALESCE(l.n, 0),
    s.favorite_count = COALESCE(f.n, 0),
    s.comment_count = COALESCE(c.n, 0);

-- 文章计数回算：点赞/收藏/过审评论按明细，阅读按日统计汇总
UPDATE articles a
LEFT JOIN (SELECT article_id, COUNT(*) AS n FROM article_reactions WHERE type = 'LIKE' GROUP BY article_id) l
    ON l.article_id = a.id
LEFT JOIN (SELECT article_id, COUNT(*) AS n FROM article_reactions WHERE type = 'FAVORITE' GROUP BY article_id) f
    ON f.article_id = a.id
LEFT JOIN (SELECT article_id, COUNT(*) AS n FROM comments WHERE status = 'APPROVED' AND deleted_at IS NULL GROUP BY article_id) c
    ON c.article_id = a.id
LEFT JOIN (SELECT article_id, SUM(view_count) AS v FROM article_daily_stats GROUP BY article_id) v
    ON v.article_id = a.id
SET a.like_count = COALESCE(l.n, 0),
    a.favorite_count = COALESCE(f.n, 0),
    a.comment_count = COALESCE(c.n, 0),
    a.view_count = COALESCE(v.v, a.view_count),
    a.view_internal_count = FLOOR(COALESCE(v.v, a.view_count) * 0.7),
    a.view_external_count = COALESCE(v.v, a.view_count) - FLOOR(COALESCE(v.v, a.view_count) * 0.7)
WHERE a.id BETWEEN 2001 AND 2010;

-- 评论点赞数回算
UPDATE comments c
LEFT JOIN (SELECT comment_id, COUNT(*) AS n FROM comment_likes GROUP BY comment_id) l
    ON l.comment_id = c.id
SET c.like_count = COALESCE(l.n, 0)
WHERE c.id IN (3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008, 3011, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019);

-- -----------------------------------------------------------------------------
-- 25. 站点日统计（daily_stats）——全部由明细聚合，保证与业务表一致
-- -----------------------------------------------------------------------------
INSERT INTO daily_stats (stat_date, user_registered, article_published, comment_created,
                         view_count, view_internal_count, view_external_count,
                         like_count, favorite_count, ai_message_count, points_issued, points_consumed)
SELECT d.stat_date,
       (SELECT COUNT(*) FROM users u
        WHERE u.id BETWEEN 1001 AND 1008
          AND DATE(u.created_at + INTERVAL 8 HOUR) = d.stat_date),
       (SELECT COUNT(*) FROM articles a
        WHERE a.id BETWEEN 2001 AND 2010 AND a.published_at IS NOT NULL
          AND DATE(a.published_at + INTERVAL 8 HOUR) = d.stat_date),
       (SELECT COUNT(*) FROM comments c
        WHERE c.id BETWEEN 3001 AND 3019
          AND DATE(c.created_at + INTERVAL 8 HOUR) = d.stat_date),
       COALESCE((SELECT SUM(s.view_count) FROM article_daily_stats s
                 WHERE s.stat_date = d.stat_date), 0),
       FLOOR(COALESCE((SELECT SUM(s.view_count) FROM article_daily_stats s
                       WHERE s.stat_date = d.stat_date), 0) * 0.7),
       COALESCE((SELECT SUM(s.view_count) FROM article_daily_stats s
                 WHERE s.stat_date = d.stat_date), 0)
           - FLOOR(COALESCE((SELECT SUM(s.view_count) FROM article_daily_stats s
                             WHERE s.stat_date = d.stat_date), 0) * 0.7),
       (SELECT COUNT(*) FROM article_reactions r
        WHERE r.id BETWEEN 4001 AND 4020 AND r.type = 'LIKE'
          AND DATE(r.created_at + INTERVAL 8 HOUR) = d.stat_date),
       (SELECT COUNT(*) FROM article_reactions r
        WHERE r.id BETWEEN 4001 AND 4020 AND r.type = 'FAVORITE'
          AND DATE(r.created_at + INTERVAL 8 HOUR) = d.stat_date),
       (SELECT COUNT(*) FROM ai_messages m
        WHERE m.id BETWEEN 8001 AND 8008
          AND DATE(m.created_at + INTERVAL 8 HOUR) = d.stat_date),
       COALESCE((SELECT SUM(l.amount) FROM points_ledger l
                 WHERE l.id BETWEEN 5501 AND 5529 AND l.amount > 0
                   AND DATE(l.created_at + INTERVAL 8 HOUR) = d.stat_date), 0),
       COALESCE((SELECT -SUM(l.amount) FROM points_ledger l
                 WHERE l.id BETWEEN 5501 AND 5529 AND l.amount < 0
                   AND DATE(l.created_at + INTERVAL 8 HOUR) = d.stat_date), 0)
FROM (
    SELECT DATE '2026-10-18' AS stat_date UNION ALL
    SELECT DATE '2026-10-19' UNION ALL
    SELECT DATE '2026-10-20' UNION ALL
    SELECT DATE '2026-10-21' UNION ALL
    SELECT DATE '2026-10-22' UNION ALL
    SELECT DATE '2026-10-23' UNION ALL
    SELECT DATE '2026-10-24' UNION ALL
    SELECT DATE '2026-10-25' UNION ALL
    SELECT DATE '2026-10-26' UNION ALL
    SELECT DATE '2026-10-27'
) d;

-- -----------------------------------------------------------------------------
-- 26. 审计日志（audit_logs）——操作人/目标/前后快照与业务数据对应
-- -----------------------------------------------------------------------------
INSERT INTO audit_logs (id, operator_id, action, target_type, target_id, before_json, after_json, ip, created_at) VALUES
(9501, 1001, 'ARTICLE_DELETE', 'ARTICLE', '2010',
 '{"status":"PUBLISHED","deleted":false}', '{"status":"PUBLISHED","deleted":true}', '127.0.0.1',
 '2026-10-26 12:00:00.000'),
(9502, 1001, 'POINTS_ADJUST', 'POINTS', '5526',
 '{"userId":1005,"balance":0}', '{"userId":1005,"balance":2500,"amount":2500}', '127.0.0.1',
 '2026-10-22 02:00:00.000'),
(9503, 1001, 'ORDER_TRANSITION', 'ORDER', '7002',
 '{"status":"APPROVED"}', '{"status":"SHIPPED"}', '127.0.0.1',
 '2026-10-25 09:00:00.000'),
(9504, 1001, 'CONFIG_UPDATE', 'CONFIG', 'site.announcement',
 '{"value":""}', '{"value":"调试环境已启用，页面数据均为虚构。"}', '127.0.0.1',
 '2026-10-27 01:30:00.000'),
(9505, 1002, 'COMMENT_STATUS', 'COMMENT', '3006',
 '{"status":"PENDING"}', '{"status":"REJECTED","remark":"包含广告词"}', '127.0.0.1',
 '2026-10-23 06:10:00.000'),
(9506, 1001, 'GOODS_UPDATE', 'GOODS', '6004',
 '{"status":"ON_SALE","stock":100}', '{"status":"OFF_SHELF","stock":100}', '127.0.0.1',
 '2026-10-24 02:30:00.000'),
(9507, 1001, 'TITLE_CREATE', 'TITLE', '9002',
 NULL, '{"name":"调试体验官","sortOrder":25}', '127.0.0.1',
 '2026-10-19 01:00:00.000'),
(9508, NULL, 'POINTS_RECONCILE', 'SYSTEM', NULL,
 '{"drift":2}', '{"drift":0}', NULL,
 '2026-10-26 19:30:00.000'),
(9509, 1001, 'USER_DISABLE', 'USER', '1007',
 '{"status":"ACTIVE"}', '{"status":"DISABLED","reason":"违规内容"}', '127.0.0.1',
 '2026-10-23 02:20:00.000');

-- -----------------------------------------------------------------------------
-- 27. 微信扫码会话（wechat_qr_sessions）——M8 已砍，仅保留审计样本
-- -----------------------------------------------------------------------------
INSERT INTO wechat_qr_sessions (id, scene_id, status, user_id, temp_openid, bind_token_hash,
                                bind_expires_at, client_ip, user_agent, expires_at, created_at, updated_at)
VALUES
(9601, 'debug-scene-20261027-001', 'PENDING', NULL, NULL, NULL, NULL, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-27 02:10:00.000',
 '2026-10-27 02:08:00.000', '2026-10-27 02:08:00.000'),
(9602, 'debug-scene-20261026-001', 'COMPLETED', 1003, 'debug_temp_openid_9602',
 SHA2('debug-bind-9602', 256), '2026-10-26 01:01:00.000', '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-26 01:05:00.000',
 '2026-10-26 01:00:00.000', '2026-10-26 01:03:00.000'),
(9603, 'debug-scene-20261026-002', 'EXPIRED', NULL, NULL, NULL, NULL, '127.0.0.1',
 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) DebugBrowser/1.0', '2026-10-26 02:00:00.000',
 '2026-10-26 01:58:00.000', '2026-10-26 02:00:00.000'),
(9604, 'debug-scene-20261026-003', 'CONFIRMED', 1004, 'debug_temp_openid_9604',
 SHA2('debug-bind-9604', 256), '2026-10-26 03:01:00.000', '127.0.0.1',
 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) MicroMessenger/8.0', '2026-10-26 03:05:00.000',
 '2026-10-26 03:00:00.000', '2026-10-26 03:00:30.000');

COMMIT;

-- -----------------------------------------------------------------------------
-- 28. 一致性自检：以下查询应全部返回空结果
-- -----------------------------------------------------------------------------
SELECT 'article counter mismatch' AS check_name, a.id, a.like_count, a.favorite_count,
       a.comment_count, a.view_count, a.view_internal_count, a.view_external_count
FROM articles a
LEFT JOIN (SELECT article_id, COUNT(*) n FROM article_reactions WHERE type = 'LIKE' GROUP BY article_id) l
    ON l.article_id = a.id
LEFT JOIN (SELECT article_id, COUNT(*) n FROM article_reactions WHERE type = 'FAVORITE' GROUP BY article_id) f
    ON f.article_id = a.id
LEFT JOIN (SELECT article_id, COUNT(*) n FROM comments WHERE status = 'APPROVED' AND deleted_at IS NULL GROUP BY article_id) c
    ON c.article_id = a.id
WHERE a.id BETWEEN 2001 AND 2010
  AND (a.like_count <> COALESCE(l.n, 0)
    OR a.favorite_count <> COALESCE(f.n, 0)
    OR a.comment_count <> COALESCE(c.n, 0)
    OR a.view_count <> a.view_internal_count + a.view_external_count);

SELECT 'comment like mismatch' AS check_name, c.id, c.like_count, COALESCE(l.n, 0) AS actual
FROM comments c
LEFT JOIN (SELECT comment_id, COUNT(*) n FROM comment_likes GROUP BY comment_id) l
    ON l.comment_id = c.id
WHERE c.id BETWEEN 3001 AND 3019 AND c.like_count <> COALESCE(l.n, 0);

SELECT 'points balance mismatch' AS check_name, a.user_id, a.balance, a.total_earned,
       a.total_spent, COALESCE(l.amount_sum, 0) AS ledger_sum
FROM points_accounts a
LEFT JOIN (SELECT user_id, SUM(amount) amount_sum FROM points_ledger GROUP BY user_id) l
    ON l.user_id = a.user_id
WHERE a.balance <> COALESCE(l.amount_sum, 0)
   OR a.total_earned - a.total_spent <> a.balance;

SELECT 'debug dataset loaded' AS message,
       (SELECT COUNT(*) FROM users WHERE id BETWEEN 1001 AND 1008)            AS users,
       (SELECT COUNT(*) FROM articles WHERE id BETWEEN 2001 AND 2010)         AS articles,
       (SELECT COUNT(*) FROM comments WHERE id BETWEEN 3001 AND 3019)         AS comments,
       (SELECT COUNT(*) FROM points_ledger WHERE id BETWEEN 5501 AND 5529)    AS ledger_rows,
       (SELECT COUNT(*) FROM points_orders WHERE id BETWEEN 7001 AND 7006)    AS orders;
