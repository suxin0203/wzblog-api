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
 * <p>预处理 O(总词长)，匹配 O(文本长 + 命中数)，优于逐词 indexOf。
 * 文本先做 NFKC 归一（全角→半角）、去零宽与控制字符、小写化再匹配。</p>
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

    /** 命中任意敏感词返回 true。 */
    public boolean matches(String text) {
        return automaton.firstMatch(text) >= 0;
    }

    /** 把命中的敏感词按字符替换为 *，返回掩码文本。 */
    public String mask(String text) {
        return automaton.mask(text);
    }

    /** NFKC 归一 + 去零宽/控制字符 + 小写。 */
    static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase();
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            if (!isZeroWidth(codePoint) && !Character.isISOControl(codePoint) && codePoint != ' ') {
                builder.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return builder.toString();
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

        int firstMatch(String text) {
            String normalized = normalize(text);
            Node current = root;
            for (int i = 0; i < normalized.length(); i++) {
                current = next(current, normalized.charAt(i));
                if (current.terminal) {
                    return i;
                }
            }
            return -1;
        }

        String mask(String text) {
            String normalized = normalize(text);
            boolean[] masked = new boolean[normalized.length()];
            Node current = root;
            for (int i = 0; i < normalized.length(); i++) {
                current = next(current, normalized.charAt(i));
                if (current.terminal) {
                    for (int j = i - current.depth + 1; j <= i; j++) {
                        masked[j] = true;
                    }
                }
            }
            StringBuilder builder = new StringBuilder(normalized.length());
            for (int i = 0; i < normalized.length(); i++) {
                builder.append(masked[i] ? '*' : normalized.charAt(i));
            }
            return builder.toString();
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
