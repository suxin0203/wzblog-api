package com.wzblog.modules.interaction.infra.text;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词过滤：Aho-Corasick 自动机，一次扫描命中全部模式。
 * <p>预处理 O(总词长)，匹配 O(文本长 + 命中数)。文本先做 NFKC 归一（全角→半角）、
 * 去零宽与控制字符、小写化再匹配；<b>打星与判定都作用在原文上</b>——归一化仅用于
 * 自动机匹配，命中区间映射回原文替换为 *，原文的空格/大小写/其余内容保持不变。</p>
 */
@Component
public class SensitiveWordFilter {

    private volatile Automaton automaton = new Automaton(List.of());

    /** 用词库重建自动机（配置变更时调用）。 */
    public synchronized void reload(List<String> words) {
        List<String> normalized = new ArrayList<>();
        if (words != null) {
            for (String word : words) {
                String value = normalize(word);
                if (!value.isEmpty()) {
                    normalized.add(value);
                }
            }
        }
        this.automaton = new Automaton(normalized);
    }

    /** 命中任意敏感词返回 true（对原文判定，与 mask 一致）。 */
    public boolean matches(String text) {
        return mask(text).hit();
    }

    /** 掩码结果：masked 为原文打星版本；hit 表示是否命中（决定强制 PENDING）。 */
    public record MaskResult(String masked, boolean hit) {
    }

    public MaskResult mask(String text) {
        if (text == null || text.isEmpty()) {
            return new MaskResult(text == null ? "" : text, false);
        }
        Automaton automaton = this.automaton;
        // 归一化时记录每个归一化字符在原文中的 codePoint 索引，命中区间据此映射回原文
        StringBuilder normalized = new StringBuilder();
        List<Integer> originIndex = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!isZeroWidth(codePoint) && !Character.isISOControl(codePoint)) {
                String mapped = normalizeCodePoint(codePoint);
                for (int k = 0; k < mapped.length(); k++) {
                    normalized.append(mapped.charAt(k));
                    originIndex.add(i);
                }
            }
            i += Character.charCount(codePoint);
        }
        boolean[] masked = new boolean[text.length()];
        boolean hit = false;
        Automaton.Node current = automaton.root;
        for (int i = 0; i < normalized.length(); i++) {
            current = automaton.next(current, normalized.charAt(i));
            if (current.terminal) {
                hit = true;
                int start = originIndex.get(i - current.depth + 1);
                int endOrigin = originIndex.get(i) + Character.charCount(text.codePointAt(originIndex.get(i)));
                for (int p = start; p < Math.min(endOrigin, text.length()); p++) {
                    masked[p] = true;
                }
            }
        }
        if (!hit) {
            return new MaskResult(text, false);
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            builder.append(masked[i] ? '*' : text.charAt(i));
        }
        return new MaskResult(builder.toString(), true);
    }

    /** NFKC 归一 + 去零宽/控制字符 + 小写（词库与文本共用同一归一函数）。 */
    static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase();
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            if (!isZeroWidth(codePoint) && !Character.isISOControl(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private static String normalizeCodePoint(int codePoint) {
        String value = new String(Character.toChars(codePoint));
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase();
    }

    private static boolean isZeroWidth(int codePoint) {
        return (codePoint >= 0x200B && codePoint <= 0x200D) || codePoint == 0xFEFF;
    }

    /** 不可变自动机：reload 时整体替换，读取无锁。 */
    static final class Automaton {

        private final Node root = new Node();
        private final List<String> words;

        Automaton(List<String> words) {
            this.words = List.copyOf(words);
            for (String word : words) {
                insert(word);
            }
            buildFailureLinks();
        }

        int wordCount() {
            return words.size();
        }

        private void insert(String word) {
            Node current = root;
            for (int i = 0; i < word.length(); i++) {
                char ch = word.charAt(i);
                current = current.children.computeIfAbsent(ch, key -> new Node());
            }
            current.terminal = true;
            current.depth = word.length();
        }

        private void buildFailureLinks() {
            Deque<Node> queue = new ArrayDeque<>();
            for (Node child : root.children.values()) {
                child.failure = root;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                Node current = queue.poll();
                for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                    Node child = entry.getValue();
                    Node fallback = current.failure;
                    while (fallback != root && !fallback.children.containsKey(entry.getKey())) {
                        fallback = fallback.failure;
                    }
                    Node next = fallback.children.get(entry.getKey());
                    child.failure = (next != null && next != child) ? next : root;
                    // 后缀也是词时继承 terminal，取最长词的 depth
                    if (child.failure.terminal && !child.terminal) {
                        child.terminal = true;
                        child.depth = child.failure.depth;
                    } else if (child.failure.depth > child.depth) {
                        child.depth = child.failure.depth;
                    }
                    queue.add(child);
                }
            }
        }

        private Node next(Node current, char ch) {
            while (true) {
                Node child = current.children.get(ch);
                if (child != null) {
                    return child;
                }
                if (current == root) {
                    return root;
                }
                current = current.failure;
            }
        }

        static final class Node {
            final Map<Character, Node> children = new HashMap<>();
            Node failure;
            boolean terminal;
            int depth;
        }
    }
}
