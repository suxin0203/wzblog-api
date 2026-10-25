package com.wzblog.modules.ai.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.modules.ai.infra.chat.ChatClient;
import com.wzblog.modules.content.app.ArticleQueryService;
import com.wzblog.modules.content.app.port.ArticleBrief;
import com.wzblog.modules.content.app.port.PublicArticleContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AI 工具注册表（M6 §8）：6 个只读白名单工具，参数手工校验后仅映射到
 * M2 ArticleQueryService 公开方法；结果 JSON 截断 6000 字符。
 */
@Component
public class AiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);
    private static final int RESULT_LIMIT = 6000;

    private final ArticleQueryService articleQueryService;
    private final ObjectMapper objectMapper;

    public AiToolRegistry(ArticleQueryService articleQueryService, ObjectMapper objectMapper) {
        this.articleQueryService = articleQueryService;
        this.objectMapper = objectMapper;
    }

    public List<ChatClient.ToolSpec> specs() {
        return List.of(
                new ChatClient.ToolSpec("search_articles", "按关键词检索站内已公开文章",
                        """
                        {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":50},"limit":{"type":"integer","minimum":1,"maximum":10,"default":5}},"required":["query"],"additionalProperties":false}
                        """),
                new ChatClient.ToolSpec("list_categories", "列出站内全部分类",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}
                        """),
                new ChatClient.ToolSpec("list_tags", "列出站内标签",
                        """
                        {"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":50,"default":20}},"additionalProperties":false}
                        """),
                new ChatClient.ToolSpec("get_hot_articles", "获取热门文章",
                        """
                        {"type":"object","properties":{"period":{"type":"string","enum":["7d","30d","all"],"default":"7d"},"limit":{"type":"integer","minimum":1,"maximum":10,"default":5}},"additionalProperties":false}
                        """),
                new ChatClient.ToolSpec("get_article_detail", "按 slug 获取文章详情",
                        """
                        {"type":"object","properties":{"slug":{"type":"string","pattern":"^[a-z0-9]+(?:-[a-z0-9]+)*$","maxLength":180}},"required":["slug"],"additionalProperties":false}
                        """),
                new ChatClient.ToolSpec("get_site_overview", "获取站点概览统计",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}
                        """));
    }

    /** 执行工具；非法参数或异常统一转可读错误结果（不中断对话）。 */
    public String execute(String name, String argumentsJson) {
        try {
            JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(argumentsJson);
            var result = switch (name) {
                case "search_articles" -> search(args);
                case "list_categories" -> toJson(Map.of("items",
                        articleQueryService.listCategories()));
                case "list_tags" -> toJson(Map.of("items",
                        articleQueryService.listTags(boundedInt(args, "limit", 20, 1, 50))));
                case "get_hot_articles" -> articleQueryService.getHotPublic(
                        period(args), boundedInt(args, "limit", 5, 1, 10));
                case "get_article_detail" -> detail(args.path("slug").asText(""));
                case "get_site_overview" -> articleQueryService.getSiteOverview();
                default -> "{\"error\":\"未知工具\"}";
            };
            return truncate(toJson(result));
        } catch (Exception ex) {
            log.warn("AI 工具执行失败 name={}", name, ex);
            return "{\"error\":\"查询失败\",\"hint\":\"请换个关键词\"}";
        }
    }

    private List<Map<String, Object>> search(JsonNode args) {
        String query = args.path("query").asText("");
        if (query.isBlank() || query.length() > 50) {
            throw new IllegalArgumentException("query 非法");
        }
        List<ArticleBrief> briefs = articleQueryService.searchPublic(query,
                boundedInt(args, "limit", 5, 1, 10));
        return List.of(Map.of("total", briefs.size(), "items", briefs));
    }

    private Map<String, Object> detail(String slug) {
        if (!slug.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$") || slug.length() > 180) {
            throw new IllegalArgumentException("slug 非法");
        }
        PublicArticleContent content = articleQueryService.getPublicBySlug(slug);
        if (content == null) {
            return Map.of("found", false);
        }
        return Map.of("found", true, "slug", content.slug(), "title", content.title(),
                "content", content.contentText(), "publishedAt", String.valueOf(content.publishedAt()));
    }

    private static String period(JsonNode args) {
        String value = args.path("period").asText("7d");
        return switch (value) {
            case "30d", "all" -> value;
            default -> "7d";
        };
    }

    private static int boundedInt(JsonNode args, String field, int defaultValue, int min, int max) {
        int value = args.path(field).asInt(defaultValue);
        return Math.max(min, Math.min(max, value));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    private static String truncate(String value) {
        return value.length() <= RESULT_LIMIT ? value
                : value.substring(0, RESULT_LIMIT) + "…(truncated)";
    }
}
