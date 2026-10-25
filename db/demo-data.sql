-- 演示数据（可选导入，不属于 Flyway 迁移）
-- 导入方式（本地）：mysql -u$DB_USER -p $DB_NAME < db/demo-data.sql
-- 适用于新环境快速填充演示内容；幂等性弱，仅建议空库导入一次。

-- 演示用户（密码均为 Passw0rd1，BCrypt 由应用生成；此处演示直接给两个已有角色的种子外的普通用户占位说明）
-- 说明：用户密码哈希依赖应用端 BCrypt，演示用户建议通过注册接口创建，此处仅初始化内容侧数据。

-- 演示分类（幂等）
INSERT INTO categories (name, slug, description, sort_order, status)
VALUES
    ('后端', 'backend', '服务端技术与工程实践', 1, 'ACTIVE'),
    ('前端', 'frontend', 'Web 与客户端技术', 2, 'ACTIVE'),
    ('随笔', 'essay', '记录与思考', 3, 'ACTIVE')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 演示标签
INSERT INTO tags (name, slug, status)
VALUES
    ('Java', 'java', 'ACTIVE'),
    ('Spring Boot', 'spring-boot', 'ACTIVE'),
    ('MySQL', 'mysql', 'ACTIVE'),
    ('Redis', 'redis', 'ACTIVE'),
    ('架构', 'architecture', 'ACTIVE')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 演示文章（作者指向 bootstrap 管理员 id=1，未插入前请先启动应用完成管理员引导）
INSERT INTO articles (author_id, category_id, title, slug, summary, content_md, content_html,
                      content_text, word_count, reading_seconds, status, visibility, is_pinned,
                      allow_comment, version, published_at)
VALUES
    (1, (SELECT id FROM categories WHERE slug = 'backend'),
     '为什么我们把博客后端重构成模块化单体', 'why-modular-monolith',
     '从痛点出发，记录一次博客后端的架构重构决策。',
     '# 为什么选择模块化单体\n\n微服务不是银弹。本文记录我们在单库、单人维护的约束下，如何用包边界与端口获得可拆分性。\n\n## 三个硬规则\n\n- api 层不碰 Mapper\n- 跨模块只走 app 端口或事件\n- 事务只在 app 层',
     '<h1 id=\"why\">为什么选择模块化单体</h1><p>微服务不是银弹。</p>',
     '为什么选择模块化单体 微服务不是银弹。', 120, 60, 'PUBLISHED', 'PUBLIC', 1, 1, 0,
     UTC_TIMESTAMP(3)),
    (1, (SELECT id FROM categories WHERE slug = 'backend'),
     '积分账本设计：幂等、并发与对账', 'points-ledger-design',
     '不可变流水 + 幂等键 + 账户行锁，一套可讲清楚的积分系统建模。',
     '# 积分账本设计\n\n余额只是快照，真相在流水。\n\n- 每笔流水带幂等键\n- 反转不删流水，追加反向记录\n- 日终对账按差值收敛',
     '<h1 id=\"points\">积分账本设计</h1><p>余额只是快照。</p>',
     '积分账本设计 余额只是快照。', 100, 60, 'PUBLISHED', 'PUBLIC', 0, 1, 0,
     UTC_TIMESTAMP(3)),
    (1, (SELECT id FROM categories WHERE slug = 'essay'),
     '写作与工程之间的共同点', 'writing-and-engineering',
     '好的文章和好的代码都在追求同一件事：让复杂度可见且可控。',
     '# 写作与工程\n\n删掉一切不承载信息的句子，就像删掉一切不承载需求的分支。',
     '<h1 id=\"writing\">写作与工程</h1><p>删掉一切不承载信息的句子。</p>',
     '写作与工程 删掉一切不承载信息的句子。', 80, 60, 'PUBLISHED', 'PUBLIC', 0, 1, 0,
     UTC_TIMESTAMP(3));

-- 演示友链
INSERT INTO friend_links (blog_name, blog_url, theme, blogger_name, sort_order, status)
VALUES
    ('示例友链 A', 'https://example-a.com', '技术随笔', '阿一', 1, 'ENABLED'),
    ('示例友链 B', 'https://example-b.com', '工程笔记', '阿二', 2, 'ENABLED');

-- 演示称号
INSERT INTO titles (name, css_class, description, sort_order)
VALUES
    ('初创作者', 'badge-bronze', '发布首篇文章', 1),
    ('人气作者', 'badge-gold', '单篇文章获赞 50+', 2)
ON DUPLICATE KEY UPDATE css_class = VALUES(css_class);
