-- =============================================================================
-- 文栈博客（WzBlog）· 基础种子数据 V2
-- -----------------------------------------------------------------------------
-- 内容范围（严格限定，不写入任何业务数据 / 管理员账号）：
--   1) categories      4 条
--   2) tags            8 条
--   3) titles          3 条
--   4) site_configs    契约第 7.1 节的 14 个默认配置
--
-- 管理员账号：**不在本脚本中写入任何密码哈希**。应用启动时由
--   AdminBootstrap（EnvironmentPostProcessor / ApplicationRunner）读取环境变量
--   WZBLOG_ADMIN_USERNAME / WZBLOG_ADMIN_PASSWORD / WZBLOG_ADMIN_EMAIL 创建或提升管理员，
--   密码用 BCrypt(10) 现场哈希；环境变量缺失则跳过并打印 WARN。
--
-- 可重复执行：全部使用 INSERT ... ON DUPLICATE KEY UPDATE 空更新（id = id），
--   已存在的行（含运营改过的名称/配置值）不会被覆盖。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 分类（4）
-- -----------------------------------------------------------------------------
INSERT INTO categories (name, slug, description, sort_order, status)
VALUES
    ('后端开发', 'backend',  'Java、Spring 与服务端工程实践', 10, 'ENABLED'),
    ('前端开发', 'frontend', 'Web 前端与小程序相关实践',        20, 'ENABLED'),
    ('数据库',   'database', 'MySQL、Redis 与数据建模',         30, 'ENABLED'),
    ('技术随笔', 'essay',    '架构思考、复盘与杂记',             40, 'ENABLED')
ON DUPLICATE KEY UPDATE id = id;

-- -----------------------------------------------------------------------------
-- 2. 标签（8）
-- -----------------------------------------------------------------------------
INSERT INTO tags (name, slug, status)
VALUES
    ('Java',       'java',          'ENABLED'),
    ('Spring Boot','spring-boot',   'ENABLED'),
    ('MySQL',      'mysql',         'ENABLED'),
    ('Redis',      'redis',         'ENABLED'),
    ('MyBatis',    'mybatis',       'ENABLED'),
    ('Docker',     'docker',        'ENABLED'),
    ('架构设计',   'architecture',  'ENABLED'),
    ('面试笔记',   'interview',     'ENABLED')
ON DUPLICATE KEY UPDATE id = id;

-- -----------------------------------------------------------------------------
-- 3. 称号（3）
-- -----------------------------------------------------------------------------
INSERT INTO titles (name, css_class, description, sort_order)
VALUES
    ('初来乍到',   'title-newcomer', '完成首次注册与资料完善', 10),
    ('活跃作者',   'title-active',   '累计发布 10 篇以上文章', 20),
    ('资深贡献者', 'title-veteran',  '长期高质量输出与社区贡献', 30)
ON DUPLICATE KEY UPDATE id = id;

-- -----------------------------------------------------------------------------
-- 4. 站点配置（契约 7.1，共 14 项）
--    config_value 为 JSON：STRING 类型也必须带双引号（"值"）
-- -----------------------------------------------------------------------------
INSERT INTO site_configs (config_key, config_value, value_type, description, is_public)
VALUES
    ('site.name',                '"文栈博客"',                                'STRING',  '站点名',                 1),
    ('site.description',         '"一个记录 Java 后端实践与架构思考的技术博客"', 'STRING',  '站点描述',               1),
    ('site.announcement',        '""',                                        'STRING',  '站点公告',               1),
    ('site.icp',                 '""',                                        'STRING',  '备案号',                 1),
    ('site.logo_key',            '""',                                        'STRING',  'Logo 对象 key',          1),
    ('comment.audit_enabled',    'true',                                      'BOOLEAN', '评论是否强制审核',       0),
    ('comment.guest_enabled',    'true',                                      'BOOLEAN', '是否允许游客评论/留言',  1),
    ('comment.sensitive_words',  '[]',                                        'JSON',    '敏感词数组',             0),
    ('ai.enabled',               'false',                                     'BOOLEAN', 'AI 总开关（配置 Key 后由管理员开启）', 1),
    ('ai.daily_user_quota',      '100',                                       'NUMBER',  '登录用户日配额',         0),
    ('ai.ip_minute_quota',       '10',                                        'NUMBER',  'IP 分钟配额',            0),
    ('ai.guest_daily_quota',     '50',                                        'NUMBER',  '游客 IP 日配额',         0),
    ('upload.max_image_mb',      '5',                                         'NUMBER',  '图片大小上限（MB）',     1),
    ('stats.timezone',           '"Asia/Shanghai"',                           'STRING',  '统计时区',               1)
ON DUPLICATE KEY UPDATE id = id;