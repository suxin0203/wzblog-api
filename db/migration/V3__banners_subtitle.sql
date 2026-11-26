-- V3: 轮播图副标题（契约 §5.3a，2026-11-27）
-- banners 表原有 title 仅作管理备注/无障碍用途；本版升级为前台展示大标题并新增 subtitle 副标题。
-- 可空、不加非空约束：存量行行为不变（title+subtitle 均空时前台不渲染文字覆盖层）。

ALTER TABLE banners
    ADD COLUMN subtitle VARCHAR(150) NULL COMMENT '轮播副标题/介绍' AFTER title;
