package com.wzblog.modules.ai.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.ai.infra.chat.ChatClient;
import com.wzblog.modules.ai.infra.config.AiProperties;
import com.wzblog.modules.content.app.port.ArticleBrief;
import com.wzblog.modules.content.app.ArticleQueryService;
import com.wzblog.modules.content.app.port.PublicArticleContent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 编辑辅助（M6 §12）：摘要与标签建议，只读不落库。
 */
@Service
public class AiAssistService {

    private static final int INPUT_LIMIT = 12_000;

    private final ArticleQueryService articleQueryService;
    private final ChatClient chatClient;
    private final AiProperties properties;

    public AiAssistService(ArticleQueryService articleQueryService, ChatClient chatClient,
                           AiProperties properties) {
        this.articleQueryService = articleQueryService;
        this.chatClient = chatClient;
        this.properties = properties;
    }

    public Map<String, Object> summarize(com.wzblog.common.web.CurrentUser user, Long articleId,
                                         String style, Integer length) {
        requireEnabled();
        PublicArticleContent article = requireArticle(user, articleId);
        int target = length == null ? 200 : Math.min(Math.max(length, 80), 300);
        String prompt = """
                为以下博客文章写一段 %s 摘要：%d 字左右，单段纯文本，不用 Markdown、不加引号、不使用第一人称。
                标题：%s
                正文：%s""".formatted(style == null || "DETAILED".equals(style) ? "详细" : "简洁", target,
                article.title(), truncate(article.contentText()));

        var completion = chatClient.complete(List.of(ChatClient.ChatMessage.user(prompt)), null);
        String summary = completion.content() == null ? "" : completion.content().trim();
        return Map.of("summary", summary, "length", summary.length(), "model",
                chatClient.modelName(), "usage", Map.of(
                        "promptTokens", completion.usage().promptTokens(),
                        "completionTokens", completion.usage().completionTokens()));
    }

    public Map<String, Object> suggestTags(com.wzblog.common.web.CurrentUser user, Long articleId,
                                           Integer count) {
        requireEnabled();
        PublicArticleContent article = requireArticle(user, articleId);
        int target = count == null ? 5 : Math.min(Math.max(count, 1), 8);
        List<String> existing = articleQueryService.listTags(50).stream()
                .map(tag -> tag.name() == null ? "" : tag.name())
                .filter(name -> !name.isEmpty())
                .toList();
        String prompt = """
                为以下博客文章推荐 %d 个标签，一行一个，不要编号与解释。
                站内已有标签（优先复用）：%s
                标题：%s
                正文：%s""".formatted(target, String.join("、", existing), article.title(),
                truncate(article.contentText()));

        var completion = chatClient.complete(List.of(ChatClient.ChatMessage.user(prompt)), null);
        List<Map<String, Object>> tags = new ArrayList<>();
        for (String line : (completion.content() == null ? "" : completion.content()).split("\\R")) {
            String name = line.trim().replaceFirst("^[-*•]\\s*", "");
            if (name.isEmpty() || tags.size() >= target) {
                continue;
            }
            boolean exists = existing.stream()
                    .anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT)
                            .equals(name.toLowerCase(Locale.ROOT)));
            tags.add(Map.of("name", name, "slug", slugify(name), "exists", exists));
        }
        return Map.of("tags", tags, "model", chatClient.modelName(), "usage", Map.of(
                "promptTokens", completion.usage().promptTokens(),
                "completionTokens", completion.usage().completionTokens()));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.AI_DISABLED);
        }
    }

    /** A 方案：AI 辅助只服务作者本人（ADMIN 也必须是作者），不对他人文章开放。 */
    private PublicArticleContent requireArticle(com.wzblog.common.web.CurrentUser user,
                                                Long articleId) {
        ArticleBrief brief = articleQueryService.getBrief(articleId);
        if (brief == null || brief.deleted()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        if (user == null || !java.util.Objects.equals(brief.authorId(), user.id())) {
            throw new BusinessException(ErrorCode.ARTICLE_FORBIDDEN);
        }
        PublicArticleContent content = articleQueryService.getPublicBySlug(brief.slug());
        if (content == null) {
            // 未公开文章：用标题构造最小上下文
            return new PublicArticleContent(brief.id(), brief.authorId(), brief.title(),
                    brief.slug(), "", null);
        }
        return content;
    }

    private static String truncate(String text) {
        return text == null || text.length() <= INPUT_LIMIT
                ? (text == null ? "" : text) : text.substring(0, INPUT_LIMIT);
    }

    private static String slugify(String name) {
        String value = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return value.isEmpty() ? "tag" : value;
    }
}
