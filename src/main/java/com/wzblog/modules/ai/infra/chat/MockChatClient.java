package com.wzblog.modules.ai.infra.chat;

import com.wzblog.modules.ai.infra.config.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock 上游：确定性应答，便于无 Key 环境跑通全链路与测试。
 * <p>消息包含检索类关键词时触发一轮 search_articles 工具调用，
 * 随后基于工具结果分片输出最终回答——完整走通 session→tool→delta→done 协议。</p>
 */
@Component
@ConditionalOnProperty(name = "wzblog.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockChatClient implements ChatClient {

    private final AiProperties properties;

    public MockChatClient(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public ChatCompletion complete(List<ChatMessage> messages, List<ToolSpec> tools) {
        String lastUser = lastUserContent(messages);
        boolean alreadyTooled = messages.stream().anyMatch(message -> "tool".equals(message.role()));
        boolean wantsSearch = !alreadyTooled
                && containsAny(lastUser, "搜索", "检索", "文章", "search", "找");
        if (wantsSearch && tools != null && !tools.isEmpty()) {
            String query = lastUser.length() > 50 ? lastUser.substring(0, 50) : lastUser;
            String args = "{\"query\":" + com.fasterxml.jackson.databind.node.TextNode
                    .valueOf(query).toString() + ",\"limit\":5}";
            return new ChatCompletion(null,
                    List.of(new ToolCall("mock_call_1", "search_articles", args)),
                    new Usage(64, 0));
        }
        return new ChatCompletion("（mock）已了解你的问题：" + lastUser, null, new Usage(64, 8));
    }

    @Override
    public List<String> stream(List<ChatMessage> messages) {
        String lastUser = lastUserContent(messages);
        boolean hasToolResult = messages.stream()
                .anyMatch(message -> "tool".equals(message.role()));
        StringBuilder answer = new StringBuilder();
        if (hasToolResult) {
            String toolResult = messages.stream()
                    .filter(message -> "tool".equals(message.role()))
                    .reduce((first, second) -> second)
                    .map(ChatMessage::content).orElse("");
            answer.append("根据站内检索结果：").append(toolResult, 0,
                    Math.min(toolResult.length(), 200));
            answer.append("。以上为站内公开文章的匹配情况，如需了解某篇详情，可以告诉我它的 slug。");
        } else {
            answer.append("（mock 应答）你好，我是文栈博客的站内助手。关于「")
                    .append(lastUser, 0, Math.min(lastUser.length(), 100))
                    .append("」——当前为本地模拟模式，未连接真实模型；部署后配置 wzblog.ai.provider=openai 即可启用真实问答。");
        }
        // 按 ~24 字符分片，模拟流式节奏
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += 24) {
            chunks.add(answer.substring(i, Math.min(answer.length(), i + 24)));
        }
        return chunks;
    }

    @Override
    public String modelName() {
        return "mock-" + properties.getModel();
    }

    private static String lastUserContent(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("user".equals(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
