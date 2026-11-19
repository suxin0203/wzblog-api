package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * articles 表实体（V1__schema.sql）。content_md 是唯一事实源，其余正文列为派生列。
 * <p>不使用全局逻辑删除：所有查询显式过滤 {@code deleted_at}。</p>
 *
 * <p>【教学：读懂一个 MyBatis-Plus 实体】（详解见 docs/学习文档/06 与 08）</p>
 * <ul>
 *   <li><b>@TableName("articles")</b>：类 ↔ 表映射；列名按驼峰互转
 *       （authorId ↔ author_id，由 map-underscore-to-camel-case 开启）；</li>
 *   <li><b>@TableId(type = IdType.AUTO)</b>：主键策略 = 数据库自增，
 *       insert 后 MyBatis-Plus 自动回填生成的 id；</li>
 *   <li><b>为什么用普通类 + Lombok 而不用 record</b>：实体需要框架反射逐字段写入
 *       （查库填充、逐列 set 组装），record 的不可变性与此冲突 ——
 *       项目约定"传输对象（DTO/VO/事件）用 record，持久化实体用 Lombok 类"；</li>
 *   <li><b>派生列设计</b>：content_html/content_text/toc_json/word_count/
 *       reading_seconds 全部由 MarkdownPipeline 在<b>写入时</b>从 content_md 算出
 *       （"写一次读多次"），详情页读取零渲染 —— 博客读远多于写，这个方向的冗余极划算；</li>
 *   <li><b>version 乐观锁</b>：编辑提交必须携带打开时的版本号，updateById 时
 *       插件自动加 WHERE version=? 并 +1，并发后提交者收到 409 冲突；</li>
 *   <li><b>计数列</b>（view/like/favorite/comment_count）：冗余计数换列表页性能，
 *       由唯一写入口 ArticleCounterService 原子更新，每日对账任务修复漂移；</li>
 *   <li><b>软删</b>：deleted_at 非空即删（可恢复），刻意不用 MyBatis-Plus 的
 *       全局逻辑删除插件 —— 所有查询显式过滤，口径可见可控（见 isDeleted()）。</li>
 * </ul>
 */
@Getter
@Setter
@TableName("articles")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorId;
    private Long categoryId;
    private String title;
    private String slug;
    private String summary;
    private String coverKey;
    private String contentMd;
    private String contentHtml;
    private String contentText;
    private String tocJson;
    private Integer wordCount;
    private Integer readingSeconds;
    private ArticleStatus status;
    private ArticleVisibility visibility;
    private Boolean isPinned;
    private Boolean allowComment;
    private Integer viewCount;
    private Integer likeCount;
    private Integer favoriteCount;
    private Integer commentCount;
    private Integer viewInternalCount;
    private Integer viewExternalCount;
    private LocalDateTime publishedAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    /** 软删判定：deleted_at 非空即已删除。所有查询都应显式过滤（见类注释）。 */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}