package com.wzblog.modules.ai.infra.chat;

import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天上游端口：工具轮（非流式）与最终回答（真流式增量回调）。
 * <p>mock 实现用于本地与测试（确定性分片）；openai 实现以 stream=true 调用
 * OpenAI 兼容 /chat/completions，逐行解析 SSE data 帧，上游每产出一个增量即回调 onDelta，
 * 前端看到的 SSE 协议（session → (tool)* → delta* → done|error）保持不变。</p>
 */
public interface ChatClient {

    /** 工具轮：返回文本或工具调用（可空）。 */
    ChatCompletion complete(List<ChatMessage> messages, List<ToolSpec> tools);

    /** 最终回答：真流式——上游每产出一个文本增量即回调 onDelta（mock 为确定性分片）。 */
    void stream(List<ChatMessage> messages, Consumer<String> onDelta);

    /** 上游模型名（落库 ai_messages.model）。 */
    String modelName();

    /**
     * role=tool 时 toolCallId 必填（OpenAI 协议要求）；
     * assistant 可携带 toolCalls（工具轮的模型决策回放）。
     */
    record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content, null, null);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, null, null);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content, null, null);
        }

        public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", null, null, toolCalls);
        }

        public static ChatMessage tool(String content, String toolCallId) {
            return new ChatMessage("tool", content, toolCallId, null);
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
