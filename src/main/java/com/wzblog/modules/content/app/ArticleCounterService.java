package com.wzblog.modules.content.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.content.infra.mapper.ArticleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文章三计数唯一写入口（M2 §16.2）；递减不做负数，影响 0 行视为已到目标态。
 */
@Service
public class ArticleCounterService {

    private final ArticleMapper articleMapper;

    public ArticleCounterService(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    @Transactional
    public int increment(Long articleId, CounterType type, int delta) {
        validate(articleId, type, delta);
        if (delta == 0) {
            return current(articleId, type);
        }
        articleMapper.incrementCounter(articleId, type.name(), delta);
        return current(articleId, type);
    }

    @Transactional
    public int decrement(Long articleId, CounterType type, int delta) {
        validate(articleId, type, delta);
        if (delta == 0) {
            return current(articleId, type);
        }
        articleMapper.decrementCounter(articleId, type.name(), delta);
        return current(articleId, type);
    }

    private int current(Long articleId, CounterType type) {
        Integer value = articleMapper.selectCounter(articleId, type.name());
        return value == null ? 0 : value;
    }

    private void validate(Long articleId, CounterType type, int delta) {
        if (articleId == null || type == null || delta < 0) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "计数参数非法", null);
        }
    }
}
