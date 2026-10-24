package com.wzblog.modules.content.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.content.api.vo.TocItemVO;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 写入流水线：CommonMark → 标题锚点 → OWASP 清洗 → 纯文本/字数/阅读时长/摘要。
 * 读取侧只返回库中派生列，不重复渲染。
 */
@Component
public class MarkdownPipeline {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            FootnotesExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .build();

    private static final String[] ALLOWED_TAGS = {
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "a", "img", "ul", "ol", "li",
            "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td",
            "hr", "strong", "em", "del"
    };

    private static final Pattern HEADING_ID = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern CLASS_VALUE = Pattern.compile("^[A-Za-z0-9 _-]{0,120}$");
    private static final Pattern MEDIA_SRC = Pattern.compile("^/media/(?!.*\\.\\.)[A-Za-z0-9._~%/+@-]+$");

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements(ALLOWED_TAGS)
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href").onElements("a")
            .allowAttributes("src").matching(MEDIA_SRC).onElements("img")
            .allowAttributes("alt").onElements("img")
            .allowAttributes("title").onElements("a", "img", "code")
            .allowAttributes("id").matching(HEADING_ID).onElements("h1", "h2", "h3", "h4", "h5", "h6")
            .allowAttributes("class").matching(CLASS_VALUE).onElements(ALLOWED_TAGS)
            .toFactory();

    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[[^\\]]*\\]\\(\\s*(<[^>]*>|[^\\s)]+)(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)");

    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "blockquote", "pre", "table", "thead", "tbody", "tr", "hr");

    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*");

    private final ObjectMapper objectMapper;

    public MarkdownPipeline(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 渲染结果；imageKeys 为正文中引用到的对象 key（已剥离 /media/ 前缀）。
     */
    public record MarkdownResult(String html,
                                 String text,
                                 String tocJson,
                                 int wordCount,
                                 int readingSeconds,
                                 String autoSummary,
                                 Set<String> imageKeys) {
    }

    public void validateSize(String markdown, int maxBytes) {
        if (markdown == null) {
            return;
        }
        if (markdown.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "正文不能超过 " + maxBytes / 1024 + " KB", null);
        }
    }

    public MarkdownResult render(String markdown, boolean strictImages) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n");
        Set<String> imageKeys = extractImageKeys(normalized, strictImages);

        Node document = PARSER.parse(normalized);
        String rawHtml = RENDERER.render(document);

        List<TocItemVO> toc = new ArrayList<>();
        String withAnchors = applyHeadingAnchors(rawHtml, toc);
        String safeHtml = POLICY.sanitize(withAnchors);
        String html = hardenLinks(safeHtml);

        String text = toPlainText(html);
        int wordCount = countWords(text);
        int readingSeconds = readingSeconds(text, wordCount);
        String summary = autoSummary(text);
        return new MarkdownResult(html, text, toJson(toc), wordCount, readingSeconds, summary, imageKeys);
    }

    /**
     * 从 Markdown 图片语法提取 {@code /media/{objectKey}}；strict 模式下其他地址返回 422。
     */
    public Set<String> extractImageKeys(String markdown, boolean strict) {
        Set<String> keys = new LinkedHashSet<>();
        if (markdown == null || markdown.isEmpty()) {
            return keys;
        }
        Matcher matcher = MARKDOWN_IMAGE.matcher(markdown);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            if (url.startsWith("<") && url.endsWith(">")) {
                url = url.substring(1, url.length() - 1).trim();
            }
            if (!url.startsWith("/media/")) {
                if (strict) {
                    throw new BusinessException(ErrorCode.ARTICLE_IMAGE_INVALID,
                            "正文图片必须使用 /media/{objectKey} 相对路径", null);
                }
                continue;
            }
            String key = url.substring("/media/".length());
            if (key.isBlank() || key.contains("..") || key.indexOf('?') >= 0 || key.indexOf('#') >= 0) {
                if (strict) {
                    throw new BusinessException(ErrorCode.ARTICLE_IMAGE_INVALID, "图片 objectKey 非法", null);
                }
                continue;
            }
            keys.add(key);
        }
        return keys;
    }

    /**
     * 封面 key 校验：对象 key 不含域名/前缀，不允许路径穿越。
     */
    public String validateCoverKey(String coverKey) {
        if (coverKey == null) {
            return null;
        }
        String value = coverKey.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > 255 || value.startsWith("/") || value.contains("://")
                || value.contains("..") || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new BusinessException(ErrorCode.ARTICLE_IMAGE_INVALID, "封面 key 非法", null);
        }
        return value;
    }

    private String applyHeadingAnchors(String html, List<TocItemVO> toc) {
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        int headingIndex = 0;
        Set<String> used = new LinkedHashSet<>();
        for (Element heading : document.select("h1, h2, h3, h4, h5, h6")) {
            headingIndex++;
            String text = heading.text().trim();
            String anchor = slugifyHeading(text);
            if (anchor.isEmpty()) {
                anchor = "heading-" + headingIndex;
            }
            String unique = anchor;
            int suffix = 2;
            while (!used.add(unique)) {
                unique = anchor + "-" + suffix;
                suffix++;
            }
            heading.attr("id", unique);
            toc.add(new TocItemVO(Integer.parseInt(heading.tagName().substring(1)), text, unique));
        }
        return document.body().html();
    }

    private String slugifyHeading(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            } else if (ch == ' ' || ch == '-' || ch == '_' || ch == '\t') {
                if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '-') {
                    builder.append('-');
                }
            }
        }
        while (!builder.isEmpty() && builder.charAt(builder.length() - 1) == '-') {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private String hardenLinks(String safeHtml) {
        Document document = Jsoup.parseBodyFragment(safeHtml);
        document.outputSettings().prettyPrint(false);
        for (Element link : document.select("a[href]")) {
            String href = link.attr("href").trim().toLowerCase(Locale.ROOT);
            if (href.startsWith("http://") || href.startsWith("https://")) {
                link.attr("rel", "noopener noreferrer");
            }
        }
        return document.body().html();
    }

    private String toPlainText(String html) {
        Document document = Jsoup.parseBodyFragment(html);
        StringBuilder raw = new StringBuilder();
        for (org.jsoup.nodes.Node child : document.body().childNodes()) {
            appendText(child, raw);
        }
        String[] lines = raw.toString().split("\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String normalized = line.replaceAll("[\\t\\x0B\\f\\r ]+", " ").trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(normalized);
        }
        return result.toString();
    }

    private void appendText(org.jsoup.nodes.Node node, StringBuilder builder) {
        if (node instanceof TextNode textNode) {
            builder.append(textNode.text());
            return;
        }
        if (node instanceof Element element) {
            String tag = element.tagName();
            if ("br".equals(tag)) {
                builder.append('\n');
                return;
            }
            boolean block = BLOCK_TAGS.contains(tag);
            if (block) {
                builder.append('\n');
            }
            for (org.jsoup.nodes.Node child : element.childNodes()) {
                appendText(child, builder);
            }
            if (block) {
                builder.append('\n');
            }
        }
    }

    private int countWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk++;
            }
            i += Character.charCount(codePoint);
        }
        int latin = 0;
        Matcher matcher = LATIN_WORD.matcher(text);
        while (matcher.find()) {
            latin++;
        }
        return cjk + latin;
    }

    private boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);
    }

    private int readingSeconds(String text, int wordCount) {
        int cjk = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk++;
            }
            i += Character.charCount(codePoint);
        }
        int latinWords = Math.max(0, wordCount - cjk);
        double seconds = Math.ceil(cjk / 300.0 + latinWords / 200.0);
        return Math.max(60, (int) seconds);
    }

    /**
     * 自动摘要：前 200 字符内、120~200 区间最后一个句末符号截断，否则硬截 200。
     */
    public String autoSummary(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        int limit = Math.min(200, normalized.length());
        int from = Math.min(120, limit > 0 ? limit - 1 : 0);
        for (int i = limit - 1; i >= from; i--) {
            char ch = normalized.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?') {
                return normalized.substring(0, i + 1);
            }
        }
        return normalized.substring(0, limit);
    }

    public String toJson(List<TocItemVO> toc) {
        try {
            return objectMapper.writeValueAsString(toc);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("TOC 序列化失败", ex);
        }
    }
}