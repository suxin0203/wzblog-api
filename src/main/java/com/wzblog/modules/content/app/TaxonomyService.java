package com.wzblog.modules.content.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.content.api.dto.CategoryUpsertRequest;
import com.wzblog.modules.content.api.dto.TagUpsertRequest;
import com.wzblog.modules.content.api.vo.CategoryVO;
import com.wzblog.modules.content.api.vo.TagVO;
import com.wzblog.modules.content.domain.Category;
import com.wzblog.modules.content.domain.Tag;
import com.wzblog.modules.content.domain.TaxonomyStatus;
import com.wzblog.modules.content.infra.mapper.ArticleMapper;
import com.wzblog.modules.content.infra.mapper.ArticleTagMapper;
import com.wzblog.modules.content.infra.mapper.CategoryMapper;
import com.wzblog.modules.content.infra.mapper.TagMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类/标签：公开查询 + 后台 CRUD（物理删除）。
 */
@Service
public class TaxonomyService {

    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;

    public TaxonomyService(CategoryMapper categoryMapper,
                           TagMapper tagMapper,
                           ArticleMapper articleMapper,
                           ArticleTagMapper articleTagMapper) {
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.articleMapper = articleMapper;
        this.articleTagMapper = articleTagMapper;
    }

    public List<CategoryVO> listPublicCategories() {
        Map<Long, Long> counts = categoryCounts();
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .eq(Category::getStatus, TaxonomyStatus.ENABLED)
                        .orderByAsc(Category::getSortOrder)
                        .orderByAsc(Category::getId))
                .stream()
                .map(category -> toCategoryVO(category, counts.getOrDefault(category.getId(), 0L)))
                .toList();
    }

    public CategoryVO getPublicCategory(String slug) {
        Category category = categoryMapper.selectBySlug(slug);
        if (category == null || category.getStatus() != TaxonomyStatus.ENABLED) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return toCategoryVO(category, categoryCounts().getOrDefault(category.getId(), 0L));
    }

    public List<CategoryVO> listAdminCategories() {
        Map<Long, Long> counts = categoryCounts();
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .orderByAsc(Category::getSortOrder)
                        .orderByAsc(Category::getId))
                .stream()
                .map(category -> toCategoryVO(category, counts.getOrDefault(category.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryVO createCategory(CategoryUpsertRequest request) {
        String name = request.name().trim();
        String slug = request.slug().trim();
        assertCategoryNameFree(name, null);
        assertCategorySlugFree(slug, null);
        Category category = new Category();
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(request.description());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setStatus(request.status() == null ? TaxonomyStatus.ENABLED : request.status());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CATEGORY_SLUG_CONFLICT);
        }
        return toCategoryVO(category, 0L);
    }

    @Transactional
    public CategoryVO updateCategory(Long id, CategoryUpsertRequest request) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        String name = request.name().trim();
        String slug = request.slug().trim();
        assertCategoryNameFree(name, id);
        assertCategorySlugFree(slug, id);
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(request.description());
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.status() != null) {
            category.setStatus(request.status());
        }
        try {
            categoryMapper.updateById(category);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CATEGORY_SLUG_CONFLICT);
        }
        return toCategoryVO(category, categoryCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public void deleteCategory(Long id) {
        if (categoryMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        categoryMapper.deleteById(id);
    }

    public List<TagVO> listPublicTags() {
        Map<Long, Long> counts = tagCounts();
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getStatus, TaxonomyStatus.ENABLED)
                        .orderByAsc(Tag::getId))
                .stream()
                .map(tag -> toTagVO(tag, counts.getOrDefault(tag.getId(), 0L)))
                .toList();
    }

    public TagVO getPublicTag(String slug) {
        Tag tag = tagMapper.selectBySlug(slug);
        if (tag == null || tag.getStatus() != TaxonomyStatus.ENABLED) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        return toTagVO(tag, tagCounts().getOrDefault(tag.getId(), 0L));
    }

    public List<TagVO> listAdminTags() {
        Map<Long, Long> counts = tagCounts();
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>().orderByAsc(Tag::getId))
                .stream()
                .map(tag -> toTagVO(tag, counts.getOrDefault(tag.getId(), 0L)))
                .toList();
    }

    @Transactional
    public TagVO createTag(TagUpsertRequest request) {
        String name = request.name().trim();
        String slug = request.slug().trim();
        assertTagNameFree(name, null);
        assertTagSlugFree(slug, null);
        Tag tag = new Tag();
        tag.setName(name);
        tag.setSlug(slug);
        tag.setStatus(request.status() == null ? TaxonomyStatus.ENABLED : request.status());
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.TAG_SLUG_CONFLICT);
        }
        return toTagVO(tag, 0L);
    }

    @Transactional
    public TagVO updateTag(Long id, TagUpsertRequest request) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        String name = request.name().trim();
        String slug = request.slug().trim();
        assertTagNameFree(name, id);
        assertTagSlugFree(slug, id);
        tag.setName(name);
        tag.setSlug(slug);
        if (request.status() != null) {
            tag.setStatus(request.status());
        }
        try {
            tagMapper.updateById(tag);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.TAG_SLUG_CONFLICT);
        }
        return toTagVO(tag, tagCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public void deleteTag(Long id) {
        if (tagMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        tagMapper.deleteById(id);
    }

    private void assertCategoryNameFree(String name, Long excludeId) {
        LambdaQueryWrapper<Category> query = new LambdaQueryWrapper<Category>().eq(Category::getName, name);
        if (excludeId != null) {
            query.ne(Category::getId, excludeId);
        }
        if (categoryMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_CONFLICT);
        }
    }

    private void assertCategorySlugFree(String slug, Long excludeId) {
        LambdaQueryWrapper<Category> query = new LambdaQueryWrapper<Category>().eq(Category::getSlug, slug);
        if (excludeId != null) {
            query.ne(Category::getId, excludeId);
        }
        if (categoryMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_SLUG_CONFLICT);
        }
    }

    private void assertTagNameFree(String name, Long excludeId) {
        LambdaQueryWrapper<Tag> query = new LambdaQueryWrapper<Tag>().eq(Tag::getName, name);
        if (excludeId != null) {
            query.ne(Tag::getId, excludeId);
        }
        if (tagMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.TAG_NAME_CONFLICT);
        }
    }

    private void assertTagSlugFree(String slug, Long excludeId) {
        LambdaQueryWrapper<Tag> query = new LambdaQueryWrapper<Tag>().eq(Tag::getSlug, slug);
        if (excludeId != null) {
            query.ne(Tag::getId, excludeId);
        }
        if (tagMapper.selectCount(query) > 0) {
            throw new BusinessException(ErrorCode.TAG_SLUG_CONFLICT);
        }
    }

    private Map<Long, Long> categoryCounts() {
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : articleMapper.selectPublicCategoryCounts()) {
            Long id = number(row, "category_id", "categoryId");
            if (id != null) {
                result.put(id, number(row, "article_count", "articleCount"));
            }
        }
        return result;
    }

    private Map<Long, Long> tagCounts() {
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : articleTagMapper.selectPublicTagCounts()) {
            Long id = number(row, "tag_id", "tagId");
            if (id != null) {
                result.put(id, number(row, "article_count", "articleCount"));
            }
        }
        return result;
    }

    private Long number(Map<String, Object> row, String snake, String camel) {
        Object value = row.containsKey(snake) ? row.get(snake) : row.get(camel);
        return value instanceof Number number ? number.longValue() : null;
    }

    private CategoryVO toCategoryVO(Category category, long count) {
        return new CategoryVO(category.getId(), category.getName(), category.getSlug(),
                category.getDescription(), category.getSortOrder() == null ? 0 : category.getSortOrder(),
                category.getStatus() == null ? null : category.getStatus().name(), count);
    }

    private TagVO toTagVO(Tag tag, long count) {
        return new TagVO(tag.getId(), tag.getName(), tag.getSlug(),
                tag.getStatus() == null ? null : tag.getStatus().name(), count);
    }
}
