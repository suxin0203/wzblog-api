package com.wzblog.modules.content.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.media.FileBizType;
import com.wzblog.common.media.FileReferencePort;
import com.wzblog.common.util.Ids;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.content.api.dto.ArticleCreateRequest;
import com.wzblog.modules.content.api.dto.ArticleUpdateRequest;
import com.wzblog.modules.content.api.vo.ArticleCreatedVO;
import com.wzblog.modules.content.api.vo.ArticleEditVO;
import com.wzblog.modules.content.domain.Article;
import com.wzblog.modules.content.domain.ArticleRevision;
import com.wzblog.modules.content.domain.ArticleStatus;
import com.wzblog.modules.content.domain.ArticleVisibility;
import com.wzblog.modules.content.domain.Category;
import com.wzblog.modules.content.domain.Tag;
import com.wzblog.modules.content.domain.event.ArticleDeletedEvent;
import com.wzblog.modules.content.domain.event.ArticleModeratedEvent;
import com.wzblog.modules.content.domain.event.ArticlePublishedEvent;
import com.wzblog.modules.content.infra.config.ContentProperties;
import com.wzblog.modules.content.infra.mapper.ArticleMapper;
import com.wzblog.modules.content.infra.mapper.ArticleRevisionMapper;
import com.wzblog.modules.content.infra.mapper.ArticleTagMapper;
import com.wzblog.modules.content.infra.mapper.CategoryMapper;
import com.wzblog.modules.content.infra.mapper.TagMapper;
import com.wzblog.modules.admin.app.AuditRecorder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 文章写入：Markdown 流水线、slug、修订、文件引用差量、状态机与事件（M2 §7/§10）。
 *
 * <p>【教学：一个"教科书级"的应用服务长什么样】（详解见 docs/学习文档/04 与 08）</p>
 * <ul>
 *   <li><b>事务边界在 app 层方法上</b>（@Transactional）：一个用例 = 一个原子动作，
 *       方法正常返回提交、抛异常整体回滚 —— Controller 不管事务，Mapper 各自单条执行；</li>
 *   <li><b>11 个依赖的构造器注入</b>：应用服务是"编排者"，聚合 Mapper/流水线/端口/
 *       事件发布器/审计 —— 依赖多不是罪，藏着掖着才是；</li>
 *   <li><b>唯一事实源 + 派生</b>：客户端只传 content_md，html/纯文本/目录/字数全部
 *       服务端计算（绝不信任客户端提交的渲染结果）；</li>
 *   <li><b>唯一键兜底并发</b>："先查 slug 后插入"存在并发窗口，catch
 *       DuplicateKeyException 翻译成业务错误 —— 数据库唯一键才是最后防线；</li>
 *   <li><b>文件引用差量同步</b>：只 claim 新增图片、只 release 移除的图片，
 *       防止并发窗口里计数短暂归零导致误删（假设任何两行代码之间都可能有别的线程）；</li>
 *   <li><b>状态迁移用条件 UPDATE</b>：publish 等动作的 SQL 带 WHERE status=...，
 *       影响行数 0 = 并发/状态冲突 → 业务错误码（见 ArticleMapper）。</li>
 * </ul>
 */
@Service
public class ArticleWriteService {

    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final MarkdownPipeline markdownPipeline;
    private final SlugGenerator slugGenerator;
    private final FileReferencePort fileReferencePort;
    private final ArticleAssembler articleAssembler;
    private final ContentProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditRecorder auditRecorder;

    public ArticleWriteService(ArticleMapper articleMapper,
                               ArticleTagMapper articleTagMapper,
                               ArticleRevisionMapper revisionMapper,
                               CategoryMapper categoryMapper,
                               TagMapper tagMapper,
                               MarkdownPipeline markdownPipeline,
                               SlugGenerator slugGenerator,
                               FileReferencePort fileReferencePort,
                               ArticleAssembler articleAssembler,
                               ContentProperties properties,
                               ApplicationEventPublisher eventPublisher,
                               AuditRecorder auditRecorder) {
        this.articleMapper = articleMapper;
        this.articleTagMapper = articleTagMapper;
        this.revisionMapper = revisionMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.markdownPipeline = markdownPipeline;
        this.slugGenerator = slugGenerator;
        this.fileReferencePort = fileReferencePort;
        this.articleAssembler = articleAssembler;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 创建文章（草稿）。完整步骤：
     * ① 领域校验（Markdown 大小上限）→ ② 渲染管线（消毒/目录/字数/图片提取）
     * → ③ 关联校验（分类/标签存在）→ ④ slug 生成或查重 → ⑤ 组装实体（DRAFT, version=0）
     * → ⑥ 入库（唯一键兜底并发撞 slug）→ ⑦ 标签关联 + 初始修订 → ⑧ 图片引用登记
     * → ⑨ 返回出参 VO（ID 字符串化防 JS 精度丢失）。
     */
    @Transactional
    public ArticleCreatedVO create(CurrentUser currentUser, ArticleCreateRequest request) {
        String title = request.title().trim();
        markdownPipeline.validateSize(request.contentMd(), properties.getMaxContentBytes());
        MarkdownPipeline.MarkdownResult rendered = markdownPipeline.render(request.contentMd(), true);

        Long categoryId = Ids.parseNullable(request.categoryId());
        Category category = requireCategory(categoryId);
        List<Long> tagIds = Ids.parseList(request.tagIds());
        requireTags(tagIds);

        String slug = request.slug() == null || request.slug().isBlank()
                ? generateUniqueSlug(title)
                : validateAndCheckSlug(request.slug(), null);
        String coverKey = markdownPipeline.validateCoverKey(request.coverKey());

        Article article = new Article();
        article.setAuthorId(currentUser.id());
        article.setCategoryId(category == null ? null : category.getId());
        article.setTitle(title);
        article.setSlug(slug);
        article.setSummary(resolveSummary(request.summary(), rendered));
        article.setCoverKey(coverKey);
        article.setContentMd(request.contentMd());
        article.setContentHtml(rendered.html());
        article.setContentText(rendered.text());
        article.setTocJson(rendered.tocJson());
        article.setWordCount(rendered.wordCount());
        article.setReadingSeconds(rendered.readingSeconds());
        article.setStatus(ArticleStatus.DRAFT);
        article.setVisibility(request.visibility() == null ? ArticleVisibility.PUBLIC : request.visibility());
        article.setIsPinned(false);
        article.setAllowComment(request.allowComment() == null || request.allowComment());
        article.setVersion(0);
        try {
            articleMapper.insert(article);
        } catch (DuplicateKeyException ex) {
            // selectBySlug 预检与 INSERT 之间的并发窗口，由 slug 唯一键兜底
            throw new BusinessException(ErrorCode.ARTICLE_SLUG_CONFLICT);
        }

        replaceTags(article.getId(), tagIds);
        insertRevision(article, currentUser.id(), null);

        claimImages(coverKey, rendered.imageKeys(), Set.of(), currentUser.id(), currentUser);
        return new ArticleCreatedVO(article.getId(), article.getSlug(), article.getStatus().name(),
                article.getVersion(), Times.toInstant(article.getPublishedAt()));
    }

    @Transactional
    public ArticleEditVO update(CurrentUser currentUser, Long id, ArticleUpdateRequest request) {
        Article article = requireAuthor(currentUser, id);
        String title = request.title().trim();
        markdownPipeline.validateSize(request.contentMd(), properties.getMaxContentBytes());
        MarkdownPipeline.MarkdownResult rendered = markdownPipeline.render(request.contentMd(), true);

        Long categoryId = Ids.parseNullable(request.categoryId());
        Category category = requireCategory(categoryId);
        List<Long> tagIds = Ids.parseList(request.tagIds());
        requireTags(tagIds);

        String slug = article.getSlug();
        if (request.slug() != null && !request.slug().isBlank() && !request.slug().equals(article.getSlug())) {
            if (article.getStatus() == ArticleStatus.PUBLISHED) {
                throw new BusinessException(ErrorCode.ARTICLE_SLUG_IMMUTABLE);
            }
            slug = validateAndCheckSlug(request.slug(), article.getId());
        }
        String coverKey = markdownPipeline.validateCoverKey(request.coverKey());
        ArticleVisibility visibility = request.visibility() == null
                ? article.getVisibility() : request.visibility();
        boolean allowComment = request.allowComment() == null
                ? Boolean.TRUE.equals(article.getAllowComment()) : request.allowComment();

        Set<String> oldImages = referencedImages(article.getContentMd(), article.getCoverKey());
        Set<String> newImages = referencedImages(request.contentMd(), coverKey);

        int updated;
        try {
            updated = articleMapper.updateContent(article.getId(), request.version(),
                    title, resolveSummary(request.summary(), rendered), coverKey,
                    category == null ? null : category.getId(), slug,
                    request.contentMd(), rendered.html(), rendered.text(), rendered.tocJson(),
                    rendered.wordCount(), rendered.readingSeconds(), visibility, allowComment);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.ARTICLE_SLUG_CONFLICT);
        }
        if (updated == 0) {
            if (articleMapper.selectAnyById(id) == null) {
                throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.ARTICLE_VERSION_CONFLICT);
        }

        replaceTags(article.getId(), tagIds);
        Article refreshed = articleMapper.selectAnyById(article.getId());
        insertRevision(refreshed, currentUser.id(), request.changeNote());

        // 引用计数按差集同步：只 claim 新增、只 release 移除（交集不动，防净增）
        Set<String> oldAll = new LinkedHashSet<>(oldImages);
        Set<String> newAll = new LinkedHashSet<>(newImages);
        claimImages(coverKey, rendered.imageKeys(), oldAll, article.getAuthorId(), currentUser);
        releaseImages(oldAll, newAll);
        return articleAssembler.toEdit(refreshed);
    }

    @Transactional
    public ArticleEditVO publish(CurrentUser currentUser, Long id) {
        Article article = requireAuthor(currentUser, id);
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            return articleAssembler.toEdit(article);
        }
        boolean firstPublish = article.getPublishedAt() == null;
        if (articleMapper.publish(id) == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_STATE_INVALID);
        }
        Article refreshed = articleMapper.selectAnyById(id);
        if (firstPublish && refreshed.getVisibility() == ArticleVisibility.PUBLIC) {
            eventPublisher.publishEvent(new ArticlePublishedEvent(
                    refreshed.getId(), refreshed.getAuthorId(), refreshed.getTitle(), refreshed.getPublishedAt()));
        }
        return articleAssembler.toEdit(refreshed);
    }

    @Transactional
    public ArticleEditVO unpublish(CurrentUser currentUser, Long id) {
        requireAuthor(currentUser, id);
        if (articleMapper.unpublish(id) == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_STATE_INVALID);
        }
        return articleAssembler.toEdit(articleMapper.selectAnyById(id));
    }

    @Transactional
    public void pin(CurrentUser admin, Long id) {
        requireAdmin(admin);
        Article article = requireExisting(id);
        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.ARTICLE_PIN_NOT_ALLOWED);
        }
        articleMapper.pin(id);
        auditRecorder.record(admin, "ARTICLE_PIN", "ARTICLE", String.valueOf(id),
                "{\"isPinned\":false}", "{\"isPinned\":true}");
    }

    @Transactional
    public void unpin(CurrentUser admin, Long id) {
        requireAdmin(admin);
        requireExisting(id);
        articleMapper.unpin(id);
        auditRecorder.record(admin, "ARTICLE_UNPIN", "ARTICLE", String.valueOf(id),
                "{\"isPinned\":true}", "{\"isPinned\":false}");
    }

    /**
     * 软删（D13）：可逆状态，不释放图片引用、不反转积分；purge 才是终态。
     * 作者可删自己的；ADMIN 可删任何（治理），治理动作审计并通知作者。
     */
    @Transactional
    public void softDelete(CurrentUser currentUser, Long id) {
        Article article = articleMapper.selectAnyById(id);
        if (article == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        boolean isAuthor = Objects.equals(article.getAuthorId(), currentUser.id());
        if (!isAuthor && !currentUser.isAdmin()) {
            throw forbiddenOrHidden(article);
        }
        if (article.isDeleted()) {
            return;
        }
        if (articleMapper.softDelete(id) > 0 && !isAuthor) {
            auditRecorder.record(currentUser, "ARTICLE_DELETE", "ARTICLE", String.valueOf(id),
                    "{\"deleted\":false}", "{\"deleted\":true}");
            eventPublisher.publishEvent(new ArticleModeratedEvent(id, article.getAuthorId(),
                    currentUser.id(), "SOFT_DELETE", null, article.getVersion() + 1, article.getTitle()));
        }
    }

    @Transactional
    public void restore(CurrentUser admin, Long id) {
        requireAdmin(admin);
        Article article = articleMapper.selectAnyById(id);
        if (article == null || !article.isDeleted()) {
            throw new BusinessException(ErrorCode.ARTICLE_STATE_INVALID);
        }
        if (articleMapper.restore(id) == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_STATE_INVALID);
        }
        // D13：软删时没有释放引用，恢复无需重新 claim
        auditRecorder.record(admin, "ARTICLE_RESTORE", "ARTICLE", String.valueOf(id),
                "{\"deleted\":true}", "{\"deleted\":false}");
        eventPublisher.publishEvent(new ArticleModeratedEvent(id, article.getAuthorId(),
                admin.id(), "RESTORE", null, article.getVersion() + 1, article.getTitle()));
    }

    /** purge 是终态：释放图片引用、反转发布积分、审计并通知作者。 */
    @Transactional
    public void purge(CurrentUser admin, Long id) {
        requireAdmin(admin);
        Article article = articleMapper.selectAnyById(id);
        if (article == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (!article.isDeleted()) {
            throw new BusinessException(ErrorCode.ARTICLE_PURGE_NOT_ALLOWED);
        }
        Set<String> images = referencedImages(article.getContentMd(), article.getCoverKey());
        // 显式先删子表再删主行：InnoDB 对「CASCADE 删子行 + comments 自引用(parent_id/root_id) SET NULL」
        // 的组合会抛 1452，含楼中楼回复的文章必现（回归用例 purgeArticleWithNestedCommentsSucceeds）
        articleMapper.purgeComments(id);
        articleMapper.purgeArticleTags(id);
        articleMapper.purgeRevisions(id);
        articleMapper.purgeReactions(id);
        articleMapper.purgeDailyStats(id);
        articleMapper.purge(id);
        releaseImages(images, Set.of());
        eventPublisher.publishEvent(new ArticleDeletedEvent(article.getId(), article.getAuthorId()));
        auditRecorder.record(admin, "ARTICLE_PURGE", "ARTICLE", String.valueOf(id),
                "{\"deleted\":true}", "{\"purged\":true}");
        eventPublisher.publishEvent(new ArticleModeratedEvent(id, article.getAuthorId(),
                admin.id(), "PURGE", null, article.getVersion(), article.getTitle()));
    }

    /** 接管（D2）：ADMIN 成为作者后才能改他人正文；审计 + 通知原作者。 */
    @Transactional
    public ArticleEditVO takeover(CurrentUser admin, Long id, String reason) {
        requireAdmin(admin);
        Article article = articleMapper.selectAnyById(id);
        if (article == null || article.isDeleted()) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (Objects.equals(article.getAuthorId(), admin.id())) {
            return articleAssembler.toEdit(article); // 幂等
        }
        Long oldAuthorId = article.getAuthorId();
        if (articleMapper.updateAuthor(id, admin.id()) == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_STATE_INVALID);
        }
        Article refreshed = articleMapper.selectAnyById(id);
        String normalizedReason = reason == null || reason.isBlank() ? "" : reason.trim();
        auditRecorder.record(admin, "ARTICLE_TAKEOVER", "ARTICLE", String.valueOf(id),
                "{\"authorId\":" + oldAuthorId + "}",
                "{\"authorId\":" + admin.id() + ",\"reason\":\"" + escapeJson(normalizedReason) + "\"}");
        eventPublisher.publishEvent(new ArticleModeratedEvent(id, oldAuthorId, admin.id(),
                "TAKEOVER", normalizedReason, refreshed.getVersion(), refreshed.getTitle()));
        return articleAssembler.toEdit(refreshed);
    }

    @Transactional
    public ArticleEditVO restoreRevision(CurrentUser currentUser, Long id, Integer revisionNo, String changeNote) {
        Article article = requireAuthor(currentUser, id);
        ArticleRevision revision = revisionMapper.selectOneRevision(id, revisionNo);
        if (revision == null) {
            throw new BusinessException(ErrorCode.ARTICLE_REVISION_NOT_FOUND);
        }
        MarkdownPipeline.MarkdownResult rendered = markdownPipeline.render(revision.getContentMd(), false);
        Set<String> oldImages = referencedImages(article.getContentMd(), article.getCoverKey());
        Set<String> newImages = referencedImages(revision.getContentMd(), article.getCoverKey());

        int updated = articleMapper.updateContent(article.getId(), article.getVersion(),
                revision.getTitle(), revision.getSummary(), article.getCoverKey(), article.getCategoryId(),
                article.getSlug(), revision.getContentMd(), rendered.html(), rendered.text(), rendered.tocJson(),
                rendered.wordCount(), rendered.readingSeconds(), article.getVisibility(),
                Boolean.TRUE.equals(article.getAllowComment()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ARTICLE_VERSION_CONFLICT);
        }
        Article refreshed = articleMapper.selectAnyById(id);
        insertRevision(refreshed, currentUser.id(),
                changeNote == null || changeNote.isBlank() ? "restore from r" + revisionNo : changeNote);
        claimRecordedImages(refreshed.getCoverKey(), imageKeys(revision.getContentMd()), oldImages);
        releaseImages(oldImages, newImages);
        return articleAssembler.toEdit(refreshed);
    }

    private Article requireExisting(Long id) {
        Article article = articleMapper.selectAnyById(id);
        if (article == null || article.isDeleted()) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        return article;
    }

    /** 内容动作只有作者本人；ADMIN 不接管也不能改（D2）。 */
    private Article requireAuthor(CurrentUser currentUser, Long id) {
        Article article = requireExisting(id);
        if (!Objects.equals(article.getAuthorId(), currentUser.id())) {
            throw forbiddenOrHidden(article);
        }
        return article;
    }

    private void requireAdmin(CurrentUser currentUser) {
        if (currentUser == null || !currentUser.isAdmin()) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    /** 已公开文章越权 → 403；草稿/私密/已删 → 404，不泄露存在性。 */
    private BusinessException forbiddenOrHidden(Article article) {
        boolean publiclyVisible = article.getStatus() == ArticleStatus.PUBLISHED
                && article.getVisibility() == ArticleVisibility.PUBLIC;
        return publiclyVisible
                ? new BusinessException(ErrorCode.ARTICLE_FORBIDDEN)
                : new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Category requireCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    private void requireTags(List<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return;
        }
        List<Tag> tags = tagMapper.selectByIds(tagIds);
        if (tags.size() != tagIds.size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
    }

    private void replaceTags(Long articleId, List<Long> tagIds) {
        articleTagMapper.deleteByArticleId(articleId);
        for (Long tagId : tagIds) {
            articleTagMapper.insert(articleId, tagId);
        }
    }

    private void insertRevision(Article article, Long editorId, String changeNote) {
        int revisionNo = revisionMapper.maxRevisionNo(article.getId()) + 1;
        ArticleRevision revision = new ArticleRevision();
        revision.setArticleId(article.getId());
        revision.setRevisionNo(revisionNo);
        revision.setEditorId(editorId);
        revision.setTitle(article.getTitle());
        revision.setSummary(article.getSummary() == null ? "" : article.getSummary());
        revision.setContentMd(article.getContentMd());
        revision.setChangeNote(changeNote);
        try {
            revisionMapper.insert(revision);
        } catch (DuplicateKeyException ex) {
            revision.setRevisionNo(revisionMapper.maxRevisionNo(article.getId()) + 1);
            revisionMapper.insert(revision);
        }
        int keep = Math.max(1, properties.getRevisionKeep());
        int max = revisionMapper.maxRevisionNo(article.getId());
        if (max > keep) {
            revisionMapper.deleteOlderThan(article.getId(), max - keep);
        }
    }

    private String resolveSummary(String summary, MarkdownPipeline.MarkdownResult rendered) {
        if (summary != null && !summary.isBlank()) {
            String value = summary.trim();
            return value.length() <= 300 ? value : value.substring(0, 300);
        }
        String auto = markdownPipeline.autoSummary(rendered.text());
        return auto.length() <= 300 ? auto : auto.substring(0, 300);
    }

    private String generateUniqueSlug(String title) {
        String base = slugGenerator.autoSlug(title);
        String candidate = base;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (articleMapper.selectBySlug(candidate) == null) {
                return candidate;
            }
            candidate = slugGenerator.withRandomSuffix(base, 4);
        }
        return candidate;
    }

    private String validateAndCheckSlug(String slug, Long selfId) {
        String value = slugGenerator.validateUserSlug(slug);
        Article existing = articleMapper.selectBySlug(value);
        if (existing != null && !Objects.equals(existing.getId(), selfId)) {
            throw new BusinessException(ErrorCode.ARTICLE_SLUG_CONFLICT);
        }
        return value;
    }

    private Set<String> referencedImages(String markdown, String coverKey) {
        Set<String> keys = new LinkedHashSet<>(imageKeys(markdown));
        if (coverKey != null && !coverKey.isBlank()) {
            keys.add(coverKey);
        }
        return keys;
    }

    private Set<String> imageKeys(String markdown) {
        return markdownPipeline.extractImageKeys(markdown == null ? "" : markdown, false);
    }

    /** 只 claim 差集：请求中新出现、且上传者是文章作者或当前操作人的引用。 */
    private void claimImages(String coverKey, Set<String> contentImages, Set<String> existing,
                             Long ownerId, CurrentUser actor) {
        if (isNewImage(coverKey, existing)) {
            fileReferencePort.claimByKey(coverKey, ownerId, actor, FileBizType.ARTICLE_COVER);
        }
        for (String key : contentImages) {
            if (!existing.contains(key)) {
                fileReferencePort.claimByKey(key, ownerId, actor, FileBizType.CONTENT_IMAGE);
            }
        }
    }

    /** 恢复服务端已存内容：key 不来自请求，跳过上传者校验。 */
    private void claimRecordedImages(String coverKey, Set<String> contentImages, Set<String> existing) {
        if (isNewImage(coverKey, existing)) {
            fileReferencePort.claimRecordedByKey(coverKey, FileBizType.ARTICLE_COVER);
        }
        for (String key : contentImages) {
            if (!existing.contains(key)) {
                fileReferencePort.claimRecordedByKey(key, FileBizType.CONTENT_IMAGE);
            }
        }
    }

    private boolean isNewImage(String key, Set<String> existing) {
        return key != null && !key.isBlank() && !existing.contains(key);
    }

    private void releaseImages(Set<String> oldImages, Set<String> newImages) {
        for (String key : oldImages) {
            if (!newImages.contains(key)) {
                fileReferencePort.releaseByKey(key);
            }
        }
    }
}
