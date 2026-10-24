-- =============================================================================
-- 文栈博客（WzBlog）· 数据库结构 V1
-- -----------------------------------------------------------------------------
-- 目标库     : MySQL 8.0+ / 库名 wzblog
-- 引擎/字符集: InnoDB / utf8mb4 / utf8mb4_0900_ai_ci
-- 时间约定   : 全部 DATETIME(3)，存 UTC；业务日按 Asia/Shanghai 换算
-- 主键约定   : BIGINT UNSIGNED AUTO_INCREMENT；ai_sessions.id 例外为 CHAR(26) ULID
-- 枚举约定   : VARCHAR + CHECK，值逐字取自 docs/00-文档索引与约定.md 第 6.1 节
-- 索引命名   : uk_ 唯一 / idx_ 普通 / ft_ 全文 / fk_ 外键 / ck_ 检查
-- 冻结声明   : 本文件由架构 Agent 独占，模块开发开始后禁止修改；后续变更用 V3+
-- 版本说明   : V1 仍为初版（项目尚未开发，可直接修订，无需增量脚本）。本次变更仅新增
--              列/唯一键/索引与收紧 CHECK，不新增表，表数量仍为 32
-- 表数量     : 32
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. identity · users
-- 先建 users，其 active_title_id 的外键在 titles 建表后通过 ALTER 添加
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username          VARCHAR(50)     NOT NULL COMMENT '登录名，唯一；注销时改写为 deleted_{id} 以释放唯一键',
    password_hash     VARCHAR(100)    NOT NULL COMMENT 'BCrypt 哈希（强度 10），兼容存量 $2b$ 前缀',
    email             VARCHAR(150)    NULL COMMENT '邮箱，唯一，用于找回密码',
    email_verified_at DATETIME(3)     NULL COMMENT '邮箱验证时间（UTC）',
    nickname          VARCHAR(50)     NOT NULL COMMENT '展示昵称',
    avatar_key        VARCHAR(255)    NULL COMMENT '头像对象 key（存 key 不存 URL）',
    bio               VARCHAR(255)    NULL COMMENT '个人简介',
    role              VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT '角色：USER/EDITOR/ADMIN',
    status            VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE/DISABLED',
    source            VARCHAR(16)     NOT NULL DEFAULT 'WEB' COMMENT '注册来源：WEB/WECHAT/QR',
    active_title_id   BIGINT UNSIGNED NULL COMMENT '当前展示称号，指向 titles.id',
    openid            VARCHAR(64)     NULL COMMENT '微信小程序 openid，唯一',
    unionid           VARCHAR(64)     NULL COMMENT '微信 unionid，唯一（允许多个 NULL）；多端打通',
    last_login_at     DATETIME(3)     NULL COMMENT '最近登录时间（UTC）',
    last_login_ip     VARCHAR(45)     NULL COMMENT '最近登录 IP，兼容 IPv6',
    version           INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at        DATETIME(3)     NULL COMMENT '软删时间（UTC），非空即已注销',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    UNIQUE KEY uk_openid (openid),
    UNIQUE KEY uk_unionid (unionid),
    KEY idx_status_created (status, created_at),
    KEY idx_deleted (deleted_at),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'EDITOR', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_source CHECK (source IN ('WEB', 'WECHAT', 'QR'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户账号';

-- -----------------------------------------------------------------------------
-- 2. points · titles（称号定义；被 users / points_goods / user_titles 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE titles (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)     NOT NULL COMMENT '称号名，唯一',
    css_class   VARCHAR(50)     NULL COMMENT '前端展示样式类名',
    description VARCHAR(255)    NULL COMMENT '称号说明',
    sort_order  INT             NOT NULL DEFAULT 0 COMMENT '展示排序，升序',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at  DATETIME(3)     NULL COMMENT '软删时间（UTC）；删除后不再发放，已持有记录保留',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_deleted (deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '称号定义表';

-- -----------------------------------------------------------------------------
-- 3. identity · refresh_tokens
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id        BIGINT UNSIGNED NOT NULL COMMENT '所属用户',
    token_hash     CHAR(64)        NOT NULL COMMENT 'Refresh Token 的 SHA-256（hex），不存明文',
    family_id      CHAR(36)        NOT NULL COMMENT 'Token 家族 ID（一次登录一个）',
    parent_id      BIGINT UNSIGNED NULL COMMENT '轮换链上一个 Token',
    replaced_by_id BIGINT UNSIGNED NULL COMMENT '被哪个新 Token 替换（用于泄露追溯）',
    client_ip      VARCHAR(45)     NULL COMMENT '签发时客户端 IP',
    user_agent     VARCHAR(255)    NULL COMMENT '签发时 User-Agent，用于设备会话列表',
    issued_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '签发时间（UTC，即创建时间）',
    expires_at     DATETIME(3)     NOT NULL COMMENT '过期时间（UTC）',
    revoked_at     DATETIME(3)     NULL COMMENT '吊销时间（UTC），NULL 为有效',
    revoke_reason  VARCHAR(32)     NULL COMMENT '吊销原因：ROTATED/LOGOUT/LOGOUT_ALL/REUSE_DETECTED/ADMIN',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_user_active (user_id, revoked_at, expires_at),
    KEY idx_family (family_id),
    KEY idx_expires (expires_at),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '刷新令牌（家族轮换、可吊销）';

-- -----------------------------------------------------------------------------
-- 4. identity · verification_codes
-- scene 取值来自契约 6.1 VerificationScene（REGISTER/RESET/BIND）
-- -----------------------------------------------------------------------------
CREATE TABLE verification_codes (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    scene         VARCHAR(16)     NOT NULL COMMENT '场景：REGISTER/RESET/BIND',
    target        VARCHAR(190)    NOT NULL COMMENT '接收方：邮箱或 openid',
    code_hash     CHAR(64)        NOT NULL COMMENT '验证码 SHA-256（hex），不存明文',
    expires_at    DATETIME(3)     NOT NULL COMMENT '过期时间（UTC，5 分钟）',
    consumed_at   DATETIME(3)     NULL COMMENT '消费时间（UTC），一次性',
    attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '校验失败次数，达到 3 次作废',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_scene_target (scene, target, created_at),
    KEY idx_expires (expires_at),
    CONSTRAINT ck_verification_codes_scene CHECK (scene IN ('REGISTER', 'RESET', 'BIND')),
    CONSTRAINT ck_verification_codes_attempt CHECK (attempt_count <= 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '邮箱/绑定验证码';

-- -----------------------------------------------------------------------------
-- 5. identity · login_audits（日志类，不建外键）
-- -----------------------------------------------------------------------------
CREATE TABLE login_audits (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id          BIGINT UNSIGNED NULL COMMENT '命中的用户；用户名不存在时为 NULL（不建外键）',
    username_attempt VARCHAR(50)     NULL COMMENT '本次尝试的登录名（脱敏前，仅审计）',
    success          TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否成功：1 成功 / 0 失败',
    failure_reason   VARCHAR(50)     NULL COMMENT '失败原因：BAD_CREDENTIALS/DISABLED/LOCKED/CAPTCHA_INVALID 等',
    ip               VARCHAR(45)     NULL COMMENT '客户端 IP',
    user_agent       VARCHAR(255)    NULL COMMENT '客户端 User-Agent',
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_user_created (user_id, created_at),
    KEY idx_ip_created (ip, created_at),
    KEY idx_created (created_at),
    CONSTRAINT ck_login_audits_success CHECK (success IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '登录审计（成功/失败均记录）';

-- -----------------------------------------------------------------------------
-- 6. content · categories
-- status 取值来自契约 6.1 CategoryStatus（ENABLED/DISABLED）
-- 分类为运营配置型数据，删除为物理删除，文章 category_id 由外键置 NULL
-- -----------------------------------------------------------------------------
CREATE TABLE categories (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)     NOT NULL COMMENT '分类名，唯一',
    slug        VARCHAR(60)     NOT NULL COMMENT 'URL 短名，唯一，^[a-z0-9]+(?:-[a-z0-9]+)*$',
    description VARCHAR(255)    NULL COMMENT '分类描述',
    sort_order  INT             NOT NULL DEFAULT 0 COMMENT '展示排序，升序',
    status      VARCHAR(16)     NOT NULL DEFAULT 'ENABLED' COMMENT '启用状态：ENABLED/DISABLED',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    UNIQUE KEY uk_slug (slug),
    KEY idx_status_sort (status, sort_order),
    CONSTRAINT ck_categories_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章分类';

-- -----------------------------------------------------------------------------
-- 7. content · tags
-- -----------------------------------------------------------------------------
CREATE TABLE tags (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(50)     NOT NULL COMMENT '标签名，唯一',
    slug       VARCHAR(60)     NOT NULL COMMENT 'URL 短名，唯一',
    status     VARCHAR(16)     NOT NULL DEFAULT 'ENABLED' COMMENT '启用状态：ENABLED/DISABLED',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    UNIQUE KEY uk_slug (slug),
    KEY idx_status (status),
    CONSTRAINT ck_tags_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章标签';

-- -----------------------------------------------------------------------------
-- 8. content · articles
-- -----------------------------------------------------------------------------
CREATE TABLE articles (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    author_id           BIGINT UNSIGNED NOT NULL COMMENT '作者，删除用户受 RESTRICT 保护（实际只软删用户）',
    category_id         BIGINT UNSIGNED NULL COMMENT '分类，NULL=未分类',
    title               VARCHAR(150)    NOT NULL COMMENT '标题（≤150 字符）',
    slug                VARCHAR(180)    NOT NULL COMMENT 'URL 短名，唯一；发布后不可修改',
    summary             VARCHAR(300)    NOT NULL DEFAULT '' COMMENT '摘要，自动截取后可手改',
    cover_key           VARCHAR(255)    NULL COMMENT '封面对象 key',
    content_md          MEDIUMTEXT      NOT NULL COMMENT 'Markdown 原文，唯一事实源（≤200 KB）',
    content_html        MEDIUMTEXT      NOT NULL COMMENT '渲染并清洗后的 HTML（派生列）',
    content_text        MEDIUMTEXT      NOT NULL COMMENT '去标签纯文本（派生列），供全文检索',
    toc_json            JSON            NULL COMMENT '目录结构（派生）',
    word_count          INT             NOT NULL DEFAULT 0 COMMENT '字数（派生）',
    reading_seconds     INT             NOT NULL DEFAULT 0 COMMENT '预计阅读秒数（派生）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT '文章状态：DRAFT/PUBLISHED/ARCHIVED',
    visibility          VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC' COMMENT '可见性：PUBLIC/PRIVATE/UNLISTED',
    is_pinned           TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否置顶：1 置顶 / 0 否（仅 PUBLISHED 可置顶）',
    allow_comment       TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否允许评论：1 允许 / 0 关闭',
    view_count          INT             NOT NULL DEFAULT 0 COMMENT '总阅读数（冗余计数）',
    like_count          INT             NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数）',
    favorite_count      INT             NOT NULL DEFAULT 0 COMMENT '收藏数（冗余计数）',
    comment_count       INT             NOT NULL DEFAULT 0 COMMENT '已过审评论数（冗余计数）',
    view_internal_count INT             NOT NULL DEFAULT 0 COMMENT '站内来源阅读数',
    view_external_count INT             NOT NULL DEFAULT 0 COMMENT '站外来源阅读数',
    published_at        DATETIME(3)     NULL COMMENT '首次发布时间（UTC），游标分页排序列',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at          DATETIME(3)     NULL COMMENT '软删时间（UTC），非空进入回收站',
    PRIMARY KEY (id),
    UNIQUE KEY uk_slug (slug),
    KEY idx_feed (status, visibility, published_at DESC, id DESC),
    KEY idx_category (category_id, status, published_at DESC),
    KEY idx_author (author_id, status, updated_at DESC),
    KEY idx_pinned (is_pinned, published_at DESC),
    KEY idx_deleted (deleted_at),
    KEY idx_published (published_at),
    FULLTEXT KEY ft_articles_search (title, content_text) WITH PARSER ngram,
    CONSTRAINT fk_articles_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_articles_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE SET NULL,
    CONSTRAINT ck_articles_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_articles_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'UNLISTED')),
    CONSTRAINT ck_articles_pinned CHECK (is_pinned IN (0, 1)),
    CONSTRAINT ck_articles_allow_comment CHECK (allow_comment IN (0, 1)),
    CONSTRAINT ck_articles_counters CHECK (view_count >= 0 AND like_count >= 0 AND favorite_count >= 0 AND comment_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章（Markdown 为唯一事实源）';

-- -----------------------------------------------------------------------------
-- 9. content · article_tags
-- -----------------------------------------------------------------------------
CREATE TABLE article_tags (
    article_id BIGINT UNSIGNED NOT NULL COMMENT '文章',
    tag_id     BIGINT UNSIGNED NOT NULL COMMENT '标签',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (article_id, tag_id),
    KEY idx_tag (tag_id, article_id),
    CONSTRAINT fk_article_tags_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE,
    CONSTRAINT fk_article_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章-标签关联';

-- -----------------------------------------------------------------------------
-- 10. content · article_revisions（每篇保留最近 20 版，定时清理）
-- -----------------------------------------------------------------------------
CREATE TABLE article_revisions (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    article_id  BIGINT UNSIGNED NOT NULL COMMENT '文章',
    revision_no INT             NOT NULL COMMENT '版本号，文章内自增，从 1 开始',
    editor_id   BIGINT UNSIGNED NOT NULL COMMENT '编辑人',
    title       VARCHAR(150)    NOT NULL COMMENT '该版本标题快照',
    summary     VARCHAR(300)    NOT NULL DEFAULT '' COMMENT '该版本摘要快照',
    content_md  MEDIUMTEXT      NOT NULL COMMENT '该版本 Markdown 快照（HTML 由渲染管道重算）',
    change_note VARCHAR(255)    NULL COMMENT '变更说明',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_revision (article_id, revision_no),
    KEY idx_article_created (article_id, created_at DESC),
    CONSTRAINT fk_article_revisions_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE,
    CONSTRAINT fk_article_revisions_editor FOREIGN KEY (editor_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_article_revisions_no CHECK (revision_no >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章修订历史';

-- -----------------------------------------------------------------------------
-- 11. interaction · comments（两级楼中楼）
-- -----------------------------------------------------------------------------
CREATE TABLE comments (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    article_id       BIGINT UNSIGNED NOT NULL COMMENT '文章',
    user_id          BIGINT UNSIGNED NULL COMMENT '评论用户；游客为 NULL',
    guest_name       VARCHAR(50)     NULL COMMENT '游客昵称；登录用户为 NULL',
    root_id          BIGINT UNSIGNED NULL COMMENT '根评论 ID；根评论为 NULL',
    parent_id        BIGINT UNSIGNED NULL COMMENT '被回复评论 ID；根评论为 NULL',
    reply_to_user_id BIGINT UNSIGNED NULL COMMENT '被回复人（"回复 @某人"）',
    content          VARCHAR(2000)   NOT NULL COMMENT '过滤敏感词后的纯文本（≤2000 字符）',
    status           VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING/APPROVED/REJECTED/HIDDEN',
    like_count       INT             NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数）',
    ip               VARCHAR(45)     NULL COMMENT '客户端 IP（风控）',
    user_agent       VARCHAR(255)    NULL COMMENT '客户端 User-Agent（风控）',
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at       DATETIME(3)     NULL COMMENT '软删时间（UTC）；父评论删除后子评论保留',
    PRIMARY KEY (id),
    KEY idx_article (article_id, status, root_id, created_at),
    KEY idx_root (root_id, created_at),
    KEY idx_user (user_id, created_at),
    KEY idx_moderation (status, created_at),
    KEY idx_parent (parent_id, created_at),
    CONSTRAINT fk_comments_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments (id) ON DELETE SET NULL,
    CONSTRAINT fk_comments_root FOREIGN KEY (root_id) REFERENCES comments (id) ON DELETE SET NULL,
    CONSTRAINT fk_comments_reply_user FOREIGN KEY (reply_to_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_comments_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'HIDDEN')),
    CONSTRAINT ck_comments_like_count CHECK (like_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章评论（两级楼中楼）';

-- -----------------------------------------------------------------------------
-- 12. interaction · article_reactions（点赞/收藏共用，唯一键保证幂等）
-- -----------------------------------------------------------------------------
CREATE TABLE article_reactions (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    article_id BIGINT UNSIGNED NOT NULL COMMENT '文章',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户',
    type       VARCHAR(16)     NOT NULL COMMENT '类型：LIKE/FAVORITE',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_target (article_id, user_id, type),
    KEY idx_user (user_id, type, created_at),
    KEY idx_article (article_id, type),
    CONSTRAINT fk_article_reactions_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE,
    CONSTRAINT fk_article_reactions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_article_reactions_type CHECK (type IN ('LIKE', 'FAVORITE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章点赞/收藏';

-- -----------------------------------------------------------------------------
-- 13. interaction · comment_likes
-- -----------------------------------------------------------------------------
CREATE TABLE comment_likes (
    comment_id BIGINT UNSIGNED NOT NULL COMMENT '评论',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (comment_id, user_id),
    KEY idx_user (user_id, created_at),
    CONSTRAINT fk_comment_likes_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评论点赞';

-- -----------------------------------------------------------------------------
-- 14. interaction · guestbook_messages
-- -----------------------------------------------------------------------------
CREATE TABLE guestbook_messages (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT UNSIGNED NULL COMMENT '留言用户；游客为 NULL',
    nickname   VARCHAR(50)     NOT NULL DEFAULT '匿名' COMMENT '展示昵称',
    content    VARCHAR(1000)   NOT NULL COMMENT '留言内容（≤1000 字符）',
    status     VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING/APPROVED/REJECTED/HIDDEN',
    ip         VARCHAR(45)     NULL COMMENT '客户端 IP（风控）',
    user_agent VARCHAR(255)    NULL COMMENT '客户端 User-Agent（风控）',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at DATETIME(3)     NULL COMMENT '软删时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_status_created (status, created_at),
    KEY idx_user (user_id, created_at),
    KEY idx_created (created_at),
    CONSTRAINT fk_guestbook_messages_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_guestbook_messages_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'HIDDEN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '留言板消息';

-- -----------------------------------------------------------------------------
-- 15. interaction · notifications
-- link_type 取值：ARTICLE/COMMENT/GUESTBOOK/ORDER/POINTS/SYSTEM（契约 6.1 已冻结 NotificationLinkType）
-- dedupe_key 由业务事件构造（如 comment:{commentId}:reply），唯一索引保证同一事件只通知一次
-- -----------------------------------------------------------------------------
CREATE TABLE notifications (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    recipient_id BIGINT UNSIGNED NOT NULL COMMENT '接收人',
    actor_id     BIGINT UNSIGNED NULL COMMENT '触发人；系统通知为 NULL',
    type         VARCHAR(32)     NOT NULL COMMENT '通知类型（NotificationType）',
    title        VARCHAR(150)    NOT NULL COMMENT '通知标题',
    content      VARCHAR(500)    NOT NULL DEFAULT '' COMMENT '通知正文',
    link_type    VARCHAR(32)     NULL COMMENT '跳转目标类型：ARTICLE/COMMENT/GUESTBOOK/ORDER/POINTS/SYSTEM',
    link_id      VARCHAR(64)     NULL COMMENT '跳转目标 ID（BIGINT 字符串或订单号）',
    dedupe_key   VARCHAR(128)    NULL COMMENT '去重键；同一业务事件只通知一次',
    is_read      TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否已读：1 已读 / 0 未读',
    read_at      DATETIME(3)     NULL COMMENT '读取时间（UTC）',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dedupe (dedupe_key),
    KEY idx_recipient_read (recipient_id, is_read, created_at),
    KEY idx_recipient_created (recipient_id, created_at),
    KEY idx_created (created_at),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_notifications_type CHECK (type IN (
        'COMMENT_REPLY', 'COMMENT_LIKE', 'ARTICLE_COMMENTED', 'ARTICLE_LIKED', 'ARTICLE_FAVORITED',
        'COMMENT_APPROVED', 'ORDER_APPROVED', 'ORDER_SHIPPED', 'ORDER_COMPLETED', 'ORDER_REJECTED',
        'ORDER_CANCELLED', 'POINTS_ADJUSTED', 'SYSTEM')),
    CONSTRAINT ck_notifications_is_read CHECK (is_read IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站内通知';

-- -----------------------------------------------------------------------------
-- 16. points · points_accounts（1:1 users；余额为快照，真相在流水）
-- -----------------------------------------------------------------------------
CREATE TABLE points_accounts (
    user_id      BIGINT UNSIGNED NOT NULL COMMENT '用户 ID（主键，1:1）',
    balance      INT             NOT NULL DEFAULT 0 COMMENT '当前余额（快照）',
    total_earned INT             NOT NULL DEFAULT 0 COMMENT '累计获得',
    total_spent  INT             NOT NULL DEFAULT 0 COMMENT '累计消耗',
    version      INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (user_id),
    CONSTRAINT fk_points_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_points_accounts_balance CHECK (balance >= 0),
    CONSTRAINT ck_points_accounts_totals CHECK (total_earned >= 0 AND total_spent >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分账户';

-- -----------------------------------------------------------------------------
-- 17. points · points_ledger（只追加；不建外键，可独立归档）
-- -----------------------------------------------------------------------------
CREATE TABLE points_ledger (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户（不建外键）',
    amount          INT             NOT NULL COMMENT '变动值：正=获得，负=消耗',
    balance_after   INT             NOT NULL COMMENT '写入后的余额，便于对账',
    type            VARCHAR(32)     NOT NULL COMMENT '积分类型（PointsType）',
    biz_type        VARCHAR(32)     NULL COMMENT '业务来源类型：ARTICLE/COMMENT/ORDER/SYSTEM',
    biz_id          VARCHAR(64)     NULL COMMENT '业务来源 ID',
    idempotency_key VARCHAR(128)    NOT NULL COMMENT '幂等键，如 publish:{articleId}',
    operator_id     BIGINT UNSIGNED NULL COMMENT '管理员操作人（ADMIN_ADJUST 时填写）',
    remark          VARCHAR(255)    NULL COMMENT '人类可读说明',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency (idempotency_key),
    KEY idx_user_created (user_id, created_at),
    KEY idx_user_type_created (user_id, type, created_at),
    KEY idx_biz (biz_type, biz_id),
    KEY idx_created (created_at),
    CONSTRAINT ck_points_ledger_type CHECK (type IN (
        'DAILY_LOGIN', 'ARTICLE_PUBLISH', 'COMMENT_APPROVED', 'LIKE_RECEIVED',
        'LIKE_REVERSED', 'ARTICLE_PUBLISH_REVERSAL', 'COMMENT_APPROVED_REVERSAL',
        'REDEEM', 'REFUND', 'ADMIN_ADJUST')),
    CONSTRAINT ck_points_ledger_amount CHECK (amount <> 0),
    CONSTRAINT ck_points_ledger_balance CHECK (balance_after >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分流水（只追加）';

-- -----------------------------------------------------------------------------
-- 18. points · points_goods
-- -----------------------------------------------------------------------------
CREATE TABLE points_goods (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name         VARCHAR(100)    NOT NULL COMMENT '商品名',
    type         VARCHAR(16)     NOT NULL DEFAULT 'PHYSICAL' COMMENT '商品类型：PHYSICAL 实物 / TITLE 称号',
    title_id     BIGINT UNSIGNED NULL COMMENT 'TITLE 类型对应称号',
    description  VARCHAR(500)    NULL COMMENT '商品描述',
    image_key    VARCHAR(255)    NULL COMMENT '商品图对象 key',
    points_price INT             NOT NULL COMMENT '单件所需积分',
    stock        INT             NULL COMMENT '库存；NULL=不限量',
    sold_count   INT             NOT NULL DEFAULT 0 COMMENT '已售数量',
    status       VARCHAR(16)     NOT NULL DEFAULT 'ON_SALE' COMMENT '状态：ON_SALE/OFF_SHELF',
    sort_order   INT             NOT NULL DEFAULT 0 COMMENT '展示排序，升序',
    version      INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本（库存并发）',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at   DATETIME(3)     NULL COMMENT '软删时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_status_sort (status, sort_order),
    KEY idx_type (type),
    KEY idx_deleted (deleted_at),
    CONSTRAINT fk_points_goods_title FOREIGN KEY (title_id) REFERENCES titles (id) ON DELETE SET NULL,
    CONSTRAINT ck_points_goods_type CHECK (type IN ('PHYSICAL', 'TITLE')),
    CONSTRAINT ck_points_goods_status CHECK (status IN ('ON_SALE', 'OFF_SHELF')),
    CONSTRAINT ck_points_goods_stock CHECK (stock IS NULL OR stock >= 0),
    CONSTRAINT ck_points_goods_price CHECK (points_price >= 0 AND sold_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分商品';

-- -----------------------------------------------------------------------------
-- 19. points · points_orders
-- -----------------------------------------------------------------------------
CREATE TABLE points_orders (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no            CHAR(24)        NOT NULL COMMENT '业务订单号（对外暴露，唯一）',
    idempotency_key     VARCHAR(64)     NOT NULL COMMENT '客户端幂等键；同一用户 24h 内唯一',
    user_id             BIGINT UNSIGNED NOT NULL COMMENT '兑换人',
    goods_id            BIGINT UNSIGNED NOT NULL COMMENT '商品',
    goods_name_snapshot VARCHAR(100)    NOT NULL COMMENT '下单时商品名快照',
    goods_type_snapshot VARCHAR(16)     NOT NULL COMMENT '下单时商品类型快照：PHYSICAL/TITLE',
    unit_points         INT             NOT NULL COMMENT '下单时单价积分快照',
    quantity            INT             NOT NULL DEFAULT 1 COMMENT '数量',
    total_points        INT             NOT NULL COMMENT '本次消耗总积分',
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING/APPROVED/SHIPPED/COMPLETED/CANCELLED/REJECTED',
    receiver_name       VARCHAR(50)     NULL COMMENT '收货人',
    receiver_phone      VARCHAR(20)     NULL COMMENT '收货电话',
    receiver_address    VARCHAR(255)    NULL COMMENT '收货地址',
    logistics_company   VARCHAR(50)     NULL COMMENT '物流公司',
    logistics_no        VARCHAR(64)     NULL COMMENT '物流单号',
    user_remark         VARCHAR(255)    NULL COMMENT '用户备注',
    admin_remark        VARCHAR(255)    NULL COMMENT '管理员备注',
    cancelled_reason    VARCHAR(255)    NULL COMMENT '取消/驳回原因',
    approved_at         DATETIME(3)     NULL COMMENT '审核通过时间（UTC）',
    shipped_at          DATETIME(3)     NULL COMMENT '发货时间（UTC）',
    completed_at        DATETIME(3)     NULL COMMENT '完成时间（UTC）',
    cancelled_at        DATETIME(3)     NULL COMMENT '取消/驳回时间（UTC）',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_idempotency (user_id, idempotency_key),
    KEY idx_user (user_id, created_at),
    KEY idx_status (status, created_at),
    KEY idx_goods (goods_id),
    KEY idx_created (created_at),
    CONSTRAINT fk_points_orders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_points_orders_goods FOREIGN KEY (goods_id) REFERENCES points_goods (id) ON DELETE RESTRICT,
    CONSTRAINT ck_points_orders_status CHECK (status IN (
        'PENDING', 'APPROVED', 'SHIPPED', 'COMPLETED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_points_orders_goods_type CHECK (goods_type_snapshot IN ('PHYSICAL', 'TITLE')),
    CONSTRAINT ck_points_orders_quantity CHECK (quantity > 0),
    CONSTRAINT ck_points_orders_total CHECK (unit_points >= 0 AND total_points >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分兑换订单';

-- -----------------------------------------------------------------------------
-- 20. points · user_titles
-- -----------------------------------------------------------------------------
CREATE TABLE user_titles (
    user_id         BIGINT UNSIGNED NOT NULL COMMENT '用户',
    title_id        BIGINT UNSIGNED NOT NULL COMMENT '称号',
    source_order_id BIGINT UNSIGNED NULL COMMENT '来源订单（商城兑换时填写）',
    acquired_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '获得时间（UTC）',
    PRIMARY KEY (user_id, title_id),
    KEY idx_title (title_id),
    KEY idx_order (source_order_id),
    CONSTRAINT fk_user_titles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_titles_title FOREIGN KEY (title_id) REFERENCES titles (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_titles_order FOREIGN KEY (source_order_id) REFERENCES points_orders (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户已获得称号';

-- -----------------------------------------------------------------------------
-- 21. points · points_order_logs（状态机审计；日志类不建外键）
-- -----------------------------------------------------------------------------
CREATE TABLE points_order_logs (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id    BIGINT UNSIGNED NOT NULL COMMENT '订单（不建外键，可独立归档）',
    from_status VARCHAR(16)     NULL COMMENT '变更前状态；创建订单时为 NULL',
    to_status   VARCHAR(16)     NOT NULL COMMENT '变更后状态',
    operator_id BIGINT UNSIGNED NULL COMMENT '操作人；系统任务为 NULL',
    remark      VARCHAR(255)    NULL COMMENT '变更说明',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_order (order_id, created_at),
    CONSTRAINT ck_points_order_logs_from CHECK (from_status IS NULL OR from_status IN (
        'PENDING', 'APPROVED', 'SHIPPED', 'COMPLETED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_points_order_logs_to CHECK (to_status IN (
        'PENDING', 'APPROVED', 'SHIPPED', 'COMPLETED', 'CANCELLED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单状态变更日志';

-- -----------------------------------------------------------------------------
-- 22. media · files（不建外键：上传者可为空，且需独立清理）
-- -----------------------------------------------------------------------------
CREATE TABLE files (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    bucket        VARCHAR(64)     NOT NULL COMMENT '对象存储桶名',
    object_key    VARCHAR(255)    NOT NULL COMMENT '对象 key（不存完整 URL）',
    thumb_key     VARCHAR(255)    NULL COMMENT '缩略图对象 key；无缩略图为 NULL',
    original_name VARCHAR(255)    NULL COMMENT '原始文件名',
    mime_type     VARCHAR(100)    NULL COMMENT '服务端探测的 MIME',
    size_bytes    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    sha256        CHAR(64)        NULL COMMENT '内容 SHA-256（hex），用于去重',
    width         INT             NULL COMMENT '图片宽度（像素）',
    height        INT             NULL COMMENT '图片高度（像素）',
    uploader_id   BIGINT UNSIGNED NULL COMMENT '上传者（不建外键）',
    biz_type      VARCHAR(32)     NULL COMMENT '业务用途：ARTICLE_COVER/CONTENT_IMAGE/AVATAR/BANNER/GOODS',
    status        VARCHAR(16)     NOT NULL DEFAULT 'TEMP' COMMENT '状态：TEMP/ACTIVE/ORPHAN/DELETED',
    ref_count     INT             NOT NULL DEFAULT 0 COMMENT '被引用次数',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at    DATETIME(3)     NULL COMMENT '软删时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_object (bucket, object_key),
    KEY idx_sha256 (sha256),
    KEY idx_status_created (status, created_at),
    KEY idx_uploader (uploader_id, created_at),
    KEY idx_deleted (deleted_at),
    CONSTRAINT ck_files_status CHECK (status IN ('TEMP', 'ACTIVE', 'ORPHAN', 'DELETED')),
    CONSTRAINT ck_files_biz_type CHECK (biz_type IS NULL OR biz_type IN (
        'ARTICLE_COVER', 'CONTENT_IMAGE', 'AVATAR', 'BANNER', 'GOODS')),
    CONSTRAINT ck_files_ref_count CHECK (ref_count >= 0 AND (width IS NULL OR width >= 0) AND (height IS NULL OR height >= 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件元数据（对象存储）';

-- -----------------------------------------------------------------------------
-- 23. ai · ai_sessions（id 为 CHAR(26) ULID；user_id 不建外键，游客为 NULL）
-- -----------------------------------------------------------------------------
CREATE TABLE ai_sessions (
    id              CHAR(26)        NOT NULL COMMENT '会话 ID（ULID，26 位，可排序不可枚举）',
    user_id         BIGINT UNSIGNED NULL COMMENT '登录用户；游客为 NULL（不建外键）',
    channel         VARCHAR(16)     NOT NULL DEFAULT 'WEB' COMMENT '渠道：WEB/MINIAPP',
    title           VARCHAR(100)    NULL COMMENT '会话标题（由首条提问截取）',
    message_count   INT             NOT NULL DEFAULT 0 COMMENT '消息条数（冗余计数）',
    last_message_at DATETIME(3)     NULL COMMENT '最近消息时间（UTC）',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    deleted_at      DATETIME(3)     NULL COMMENT '软删时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_user (user_id, last_message_at DESC),
    KEY idx_last (last_message_at DESC),
    KEY idx_deleted (deleted_at),
    CONSTRAINT ck_ai_sessions_channel CHECK (channel IN ('WEB', 'MINIAPP')),
    CONSTRAINT ck_ai_sessions_message_count CHECK (message_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 会话';

-- -----------------------------------------------------------------------------
-- 24. ai · ai_messages（status 为 v2 补充字段，用于流式中断/失败落库）
-- -----------------------------------------------------------------------------
CREATE TABLE ai_messages (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id        CHAR(26)        NOT NULL COMMENT '所属会话',
    role              VARCHAR(16)     NOT NULL COMMENT '角色：USER/ASSISTANT/TOOL/SYSTEM',
    content           MEDIUMTEXT      NOT NULL COMMENT '消息内容；TOOL 角色为工具结果',
    status            VARCHAR(16)     NOT NULL DEFAULT 'COMPLETE' COMMENT '落库状态：STREAMING/COMPLETE/INTERRUPTED/FAILED',
    tool_name         VARCHAR(64)     NULL COMMENT '工具名（role=TOOL 时）',
    tool_call_id      VARCHAR(64)     NULL COMMENT '上游工具调用 ID',
    tool_args_json    JSON            NULL COMMENT '工具入参',
    tool_result_json  JSON            NULL COMMENT '工具结果（已截断）',
    prompt_tokens     INT             NULL COMMENT '输入 token 数',
    completion_tokens INT             NULL COMMENT '输出 token 数',
    latency_ms        INT             NULL COMMENT '本次生成耗时（毫秒）',
    model             VARCHAR(64)     NULL COMMENT '实际使用的模型名',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_session (session_id, id),
    KEY idx_created (created_at),
    KEY idx_status_created (status, created_at),
    CONSTRAINT fk_ai_messages_session FOREIGN KEY (session_id) REFERENCES ai_sessions (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM')),
    CONSTRAINT ck_ai_messages_status CHECK (status IN ('STREAMING', 'COMPLETE', 'INTERRUPTED', 'FAILED')),
    CONSTRAINT ck_ai_messages_tokens CHECK (
        (prompt_tokens IS NULL OR prompt_tokens >= 0)
        AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (latency_ms IS NULL OR latency_ms >= 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 消息';

-- -----------------------------------------------------------------------------
-- 25. ai · ai_feedbacks
-- -----------------------------------------------------------------------------
CREATE TABLE ai_feedbacks (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    message_id BIGINT UNSIGNED NOT NULL COMMENT '被反馈的 AI 消息',
    user_id    BIGINT UNSIGNED NULL COMMENT '反馈用户；游客为 NULL（游客按会话维度限一条）',
    rating     SMALLINT        NOT NULL COMMENT '评分：1 有帮助 / -1 无帮助',
    comment    VARCHAR(500)    NULL COMMENT '补充说明',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_user (message_id, user_id),
    KEY idx_user (user_id, created_at),
    CONSTRAINT fk_ai_feedbacks_message FOREIGN KEY (message_id) REFERENCES ai_messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_feedbacks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_ai_feedbacks_rating CHECK (rating IN (-1, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 回答反馈';

-- -----------------------------------------------------------------------------
-- 26. system · site_configs
-- config_value 必须是合法 JSON；STRING 类型也以 JSON 字符串存（"文栈博客"）
-- -----------------------------------------------------------------------------
CREATE TABLE site_configs (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    config_key   VARCHAR(100)    NOT NULL COMMENT '配置键，唯一，点号命名（如 site.name）',
    config_value JSON            NOT NULL COMMENT '配置值（统一 JSON 编码）',
    value_type   VARCHAR(16)     NOT NULL DEFAULT 'STRING' COMMENT '值类型：STRING/NUMBER/BOOLEAN/JSON',
    description  VARCHAR(255)    NULL COMMENT '配置说明',
    is_public    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否对前台公开：1 公开 / 0 仅后台',
    updated_by   BIGINT UNSIGNED NULL COMMENT '最近修改人（不建外键）',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key),
    KEY idx_public (is_public),
    CONSTRAINT ck_site_configs_value_type CHECK (value_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'JSON')),
    CONSTRAINT ck_site_configs_is_public CHECK (is_public IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站点配置（KV + JSON）';

-- -----------------------------------------------------------------------------
-- 27. system · banners
-- status 取值来自契约 6.1 BannerStatus（ENABLED/DISABLED）
-- -----------------------------------------------------------------------------
CREATE TABLE banners (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    title      VARCHAR(100)    NULL COMMENT '轮播标题',
    image_key  VARCHAR(255)    NOT NULL COMMENT '图片对象 key',
    link_url   VARCHAR(500)    NULL COMMENT '点击跳转地址',
    sort_order INT             NOT NULL DEFAULT 0 COMMENT '展示排序，升序',
    status     VARCHAR(16)     NOT NULL DEFAULT 'ENABLED' COMMENT '启用状态：ENABLED/DISABLED',
    start_at   DATETIME(3)     NULL COMMENT '生效开始时间（UTC），NULL 立即生效',
    end_at     DATETIME(3)     NULL COMMENT '生效结束时间（UTC），NULL 长期有效',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_status_sort (status, sort_order),
    CONSTRAINT ck_banners_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '首页轮播图';

-- -----------------------------------------------------------------------------
-- 28. system · friend_links
-- -----------------------------------------------------------------------------
CREATE TABLE friend_links (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    blog_name    VARCHAR(100)    NOT NULL COMMENT '站点名',
    blog_url     VARCHAR(500)    NOT NULL COMMENT '站点地址',
    theme        VARCHAR(100)    NULL COMMENT '站点主题/简介',
    blogger_name VARCHAR(100)    NULL COMMENT '博主名',
    contact      VARCHAR(100)    NULL COMMENT '联系方式',
    logo_key     VARCHAR(255)    NULL COMMENT 'Logo 对象 key',
    sort_order   INT             NOT NULL DEFAULT 0 COMMENT '展示排序，升序',
    status       VARCHAR(16)     NOT NULL DEFAULT 'ENABLED' COMMENT '启用状态：ENABLED/DISABLED',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_status_sort (status, sort_order),
    CONSTRAINT ck_friend_links_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '友情链接';

-- -----------------------------------------------------------------------------
-- 29. system · daily_stats（按 Asia/Shanghai 自然日聚合，UPSERT 幂等）
-- -----------------------------------------------------------------------------
CREATE TABLE daily_stats (
    stat_date           DATE        NOT NULL COMMENT '统计日（Asia/Shanghai 自然日）',
    user_registered     INT         NOT NULL DEFAULT 0 COMMENT '新增注册用户数',
    article_published   INT         NOT NULL DEFAULT 0 COMMENT '发布文章数',
    comment_created     INT         NOT NULL DEFAULT 0 COMMENT '新增评论数（含待审）',
    view_count          INT         NOT NULL DEFAULT 0 COMMENT '阅读量（站内 + 站外）',
    view_internal_count INT         NOT NULL DEFAULT 0 COMMENT '站内来源阅读量',
    view_external_count INT         NOT NULL DEFAULT 0 COMMENT '站外来源阅读量',
    like_count          INT         NOT NULL DEFAULT 0 COMMENT '点赞数',
    favorite_count      INT         NOT NULL DEFAULT 0 COMMENT '收藏数',
    ai_message_count    INT         NOT NULL DEFAULT 0 COMMENT 'AI 消息数',
    points_issued       INT         NOT NULL DEFAULT 0 COMMENT '发放积分',
    points_consumed     INT         NOT NULL DEFAULT 0 COMMENT '消耗积分',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (stat_date),
    CONSTRAINT ck_daily_stats_non_negative CHECK (
        user_registered >= 0 AND article_published >= 0 AND comment_created >= 0
        AND view_count >= 0 AND view_internal_count >= 0 AND view_external_count >= 0
        AND like_count >= 0 AND favorite_count >= 0
        AND ai_message_count >= 0 AND points_issued >= 0 AND points_consumed >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站点日统计（预聚合）';

-- -----------------------------------------------------------------------------
-- 30. system · article_daily_stats
-- -----------------------------------------------------------------------------
CREATE TABLE article_daily_stats (
    article_id     BIGINT UNSIGNED NOT NULL COMMENT '文章',
    stat_date      DATE            NOT NULL COMMENT '统计日（Asia/Shanghai 自然日）',
    view_count     INT             NOT NULL DEFAULT 0 COMMENT '当日阅读量',
    like_count     INT             NOT NULL DEFAULT 0 COMMENT '当日点赞数',
    favorite_count INT             NOT NULL DEFAULT 0 COMMENT '当日收藏数',
    comment_count  INT             NOT NULL DEFAULT 0 COMMENT '当日评论数',
    PRIMARY KEY (article_id, stat_date),
    KEY idx_date (stat_date),
    CONSTRAINT fk_article_daily_stats_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE,
    CONSTRAINT ck_article_daily_stats_non_negative CHECK (
        view_count >= 0 AND like_count >= 0 AND favorite_count >= 0 AND comment_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章日统计（趋势/热门榜）';

-- -----------------------------------------------------------------------------
-- 31. system · audit_logs（审计类，不建外键）
-- -----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    operator_id BIGINT UNSIGNED NULL COMMENT '操作人；系统任务为 NULL（不建外键）',
    action      VARCHAR(64)     NOT NULL COMMENT '动作编码（AuditAction）：ARTICLE_DELETE/ARTICLE_RESTORE/ARTICLE_PURGE/USER_DISABLE/USER_DELETE/POINTS_ADJUST/POINTS_RECONCILE/INTERACTION_RECONCILE/ORDER_TRANSITION/CONFIG_UPDATE/GOODS_CREATE/GOODS_UPDATE/GOODS_DELETE/TITLE_CREATE/TITLE_UPDATE/TITLE_DELETE/COMMENT_STATUS/GUESTBOOK_STATUS/FILE_DELETE',
    target_type VARCHAR(32)     NOT NULL COMMENT '目标类型（AuditTargetType）：ARTICLE/USER/ORDER/POINTS/CONFIG/GOODS/TITLE/COMMENT/GUESTBOOK/FILE/SYSTEM',
    target_id   VARCHAR(64)     NULL COMMENT '目标 ID（BIGINT 字符串或业务编号）',
    before_json JSON            NULL COMMENT '变更前快照',
    after_json  JSON            NULL COMMENT '变更后快照',
    ip          VARCHAR(45)     NULL COMMENT '操作 IP',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_operator (operator_id, created_at),
    KEY idx_target (target_type, target_id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理端操作审计';

-- -----------------------------------------------------------------------------
-- 32. system · wechat_qr_sessions（热点状态在 Redis，DB 做审计与补偿）
-- -----------------------------------------------------------------------------
CREATE TABLE wechat_qr_sessions (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    scene_id        VARCHAR(64)     NOT NULL COMMENT '扫码场景值，唯一（小程序码 scene）',
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/CONFIRMED/COMPLETED/EXPIRED',
    user_id         BIGINT UNSIGNED NULL COMMENT '确认登录的用户；未确认为 NULL',
    temp_openid     VARCHAR(128)    NULL COMMENT '扫码但未绑定/注册时的临时 openid',
    bind_token_hash CHAR(64)        NULL COMMENT '一次性 bindToken 的 SHA-256（hex）',
    bind_expires_at DATETIME(3)     NULL COMMENT 'bindToken 过期时间（UTC，60 秒）',
    client_ip       VARCHAR(45)     NULL COMMENT 'PC 端 IP',
    user_agent      VARCHAR(255)    NULL COMMENT 'PC 端 User-Agent',
    expires_at      DATETIME(3)     NOT NULL COMMENT '会话过期时间（UTC，2 分钟）',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_scene (scene_id),
    KEY idx_status_expires (status, expires_at),
    KEY idx_user (user_id),
    CONSTRAINT fk_wechat_qr_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_wechat_qr_sessions_status CHECK (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'EXPIRED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '微信扫码登录会话';

-- -----------------------------------------------------------------------------
-- 收尾：users.active_title_id 的外键（titles 已创建）
-- -----------------------------------------------------------------------------
ALTER TABLE users
    ADD CONSTRAINT fk_users_active_title FOREIGN KEY (active_title_id) REFERENCES titles (id) ON DELETE SET NULL;