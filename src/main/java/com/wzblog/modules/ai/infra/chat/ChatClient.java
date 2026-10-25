package com.wzblog.modules.ai.infra.chat;

import java.util.List;

/**
 * 聊天上游端口：工具轮（非流式）与最终回答（流式分片）。
 * <p>mock 实现用于本地与测试；openai 实现走 OpenAI 兼容 /chat/completions。
 * 真流式 SSE 解析上游 chunk 需在接入真实 Key 后启用，当前 openai 实现
 * 以「非流式上游 + 服务端分片下发」满足同一 SSE 协议（docs/11 登记偏差）。</p>
 */
public interface ChatClient {

    /** 工具轮：返回文本或工具调用（可空）。 */
    ChatCompletion complete(List<ChatMessage> messages, List<ToolSpec> tools);

    /** 最终回答：按小块流式返回，已按服务端节奏切分。 */
    List<String> stream(List<ChatMessage> messages);

    /** 上游模型名（落库 ai_messages.model）。 */
    String modelName();

    record ChatMessage(String role, String content) {

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }

        public static ChatMessage tool(String content) {
            return new ChatMessage("tool", content);
        }
    }

    record ToolSpec(String name, String description, String parametersJsonSchema) {
    }

    record ToolCall(String id, String name, String argumentsJson) {
    }

    record Usage(int promptTokens, int completionTokens) {
    }

    record ChatCompletion(String content, List<ToolCall> toolCalls, Usage usage) {
    }
}
