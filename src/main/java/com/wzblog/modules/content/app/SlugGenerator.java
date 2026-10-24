package com.wzblog.modules.content.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章 slug 生成与校验（00 §6.2、M2 §6）。
 */
@Component
public class SlugGenerator {

    public static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private static final Set<String> RESERVED = new LinkedHashSet<>(
            Set.of("search", "pinned", "top", "archive"));

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 校验用户显式传入的 slug；保留字与非法格式按 400 处理。
     */
    public String validateUserSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "slug 不能为空", null);
        }
        String value = slug.trim();
        if (value.length() < 3 || value.length() > 180 || !SLUG_PATTERN.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "slug 需为 3~180 位小写字母、数字或中划线", null);
        }
        if (RESERVED.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "slug 不能使用保留字 search/pinned/top/archive", null);
        }
        return value;
    }

    /**
     * 标题转 kebab；无法生成合法 slug 时回退 {@code post-{8 位随机}}。
     */
    public String autoSlug(String title) {
        String base = asciiKebab(title);
        if (base.length() < 3 || RESERVED.contains(base)) {
            return "post-" + random(8);
        }
        if (base.length() > 180) {
            base = base.substring(0, 180);
            int lastDash = base.lastIndexOf('-');
            if (lastDash > 160) {
                base = base.substring(0, lastDash);
            }
        }
        return base;
    }

    /**
     * 自动 slug 冲突重试后缀（≤3 次，{@code -{4 位随机}}）。
     */
    public String withRandomSuffix(String base, int length) {
        int keep = Math.max(1, 175 - base.length());
        String trimmed = base.length() > keep ? base.substring(0, keep) : base;
        return (trimmed.endsWith("-") ? trimmed : trimmed + "-") + random(4);
    }

    private String asciiKebab(String title) {
        if (title == null) {
            return "";
        }
        String lower = title.toLowerCase(Locale.ROOT);
        Matcher matcher = ASCII_WORD.matcher(lower);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            if (!builder.isEmpty()) {
                builder.append('-');
            }
            builder.append(matcher.group());
        }
        return builder.toString();
    }

    private String random(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}