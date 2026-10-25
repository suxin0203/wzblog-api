package com.wzblog.modules.ai.infra.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.ai.infra.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容上游（wzblog.ai.provider=openai）：/chat/completions 非流式调用，
 * 最终回答由服务端分片下发（协议与真流式一致，见 ChatClient 注释）。
 */
@Component
@ConditionalOnProperty(name = "wzblog.ai.provider", havingValue = "openai")
public class OpenAiCompatChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatChatClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatChatClient(AiProperties properties, ObjectMapper objectMapper,
                                  RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ChatCompletion complete(List<ChatMessage> messages, List<ToolSpec> tools) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", properties.getModel());
        request.put("temperature", 0.3);
        request.put("max_tokens", properties.getMaxTokens());
        request.put("stream", false);
        ArrayNode messageArray = request.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode item = messageArray.addObject();
            item.put("role", message.role());
            item.put("content", message.content());
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = request.putArray("tools");
            for (ToolSpec tool : tools) {
                ObjectNode item = toolArray.addObject();
                item.put("type", "function");
                ObjectNode function = item.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", readTree(tool.parametersJsonSchema()));
            }
            request.put("tool_choice", "auto");
        }
        JsonNode response = post(request);
        JsonNode choice = response.path("choices").path(0);
        JsonNode usageNode = response.path("usage");
        Usage usage = new Usage(usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0));
        JsonNode messageNode = choice.path("message");
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : messageNode.path("tool_calls")) {
            toolCalls.add(new ToolCall(call.path("id").asText(),
                    call.path("function").path("name").asText(),
                    call.path("function").path("arguments").asText("{}")));
        }
        return new ChatCompletion(messageNode.path("content").asText(null), toolCalls, usage);
    }

    @Override
    public List<String> stream(List<ChatMessage> messages) {
        String content = complete(messages, null).content();
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < content.length(); i += 24) {
            chunks.add(content.substring(i, Math.min(content.length(), i + 24)));
        }
        return chunks;
    }

    @Override
    public String modelName() {
        return properties.getModel();
    }

    private JsonNode post(ObjectNode request) {
        try {
            String body = restClient.post()
                    .uri(properties.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return readTree(body == null ? "{}" : body);
        } catch (ResourceAccessException ex) {
            log.warn("AI 上游网络异常: {}", ex.getMessage());
            throw new BusinessException(ErrorCode.AI_UPSTREAM_TIMEOUT);
        } catch (Exception ex) {
            log.warn("AI 上游调用失败: {}", ex.getMessage());
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
    }
}
