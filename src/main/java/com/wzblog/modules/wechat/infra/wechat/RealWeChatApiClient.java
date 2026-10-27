package com.wzblog.modules.wechat.infra.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.wechat.infra.config.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 真实微信上游（wzblog.wechat.provider=real）：
 * jscode2session / getwxacodeunlimit / cgi-bin/token。
 * access_token 缓存在 Redis（TTL = expires_in - 300s），SET NX 单飞刷新 + 双重检查；
 * 连接 3s / 读 10s 超时——无界超时会挂死工作线程（与 AI 客户端同一教训）。
 */
public class RealWeChatApiClient implements WeChatApiClient {

    private static final Logger log = LoggerFactory.getLogger(RealWeChatApiClient.class);
    private static final String TOKEN_KEY = "wzblog:wechat:mp:access_token";
    private static final String TOKEN_LOCK = "wzblog:wechat:mp:access_token:lock";

    private final WechatProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RealWeChatApiClient(WechatProperties properties, StringRedisTemplate redis,
                               ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        this.restClient = builder.requestFactory(factory).build();
    }

    @Override
    public SessionResult code2Session(String code) {
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code"
                .formatted(properties.getAppId(), properties.getAppSecret(), code);
        JsonNode body = getJson(url);
        if (body.has("errcode") && body.get("errcode").asInt() != 0) {
            log.warn("jscode2session 上游错误 errcode={}（DEBUG 记录原文）", body.get("errcode").asInt());
            throw WeChatApiClient.mapUpstreamError(body.get("errcode").asInt(),
                    body.path("errmsg").asText());
        }
        String openid = body.path("openid").asText(null);
        if (openid == null || openid.isBlank()) {
            throw new BusinessException(ErrorCode.WECHAT_API_ERROR);
        }
        // session_key 不落库、不外传、不入日志（M8 §16）
        String unionid = body.path("unionid").asText(null);
        return new SessionResult(openid, unionid);
    }

    @Override
    public byte[] getQrCodePng(String sceneId, String envVersion) {
        String accessToken = accessToken();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("scene", sceneId);
        payload.put("check_path", false);
        payload.put("width", properties.getQrWidth());
        payload.put("env_version", resolveEnvVersion(envVersion));
        if (properties.getQrPage() != null && !properties.getQrPage().isBlank()) {
            payload.put("page", properties.getQrPage());
        }
        try {
            byte[] body = restClient.post()
                    .uri("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=" + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new BusinessException(ErrorCode.WECHAT_API_ERROR);
            }
            String contentType = sniffContentType(body);
            if (contentType.contains("json")) {
                JsonNode error = objectMapper.readTree(body);
                log.warn("getwxacodeunlimit 上游错误 errcode={}", error.path("errcode").asInt());
                throw WeChatApiClient.mapUpstreamError(error.path("errcode").asInt(),
                        error.path("errmsg").asText());
            }
            return body;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("getwxacodeunlimit 调用失败 type={} msg={}", ex.getClass().getSimpleName(),
                    sanitize(ex.getMessage()));
            throw new BusinessException(ErrorCode.WECHAT_API_ERROR);
        }
    }

    // ------------------------------------------------------------ access_token

    private String accessToken() {
        String cached = redis.opsForValue().get(TOKEN_KEY);
        if (cached != null) {
            return cached;
        }
        // 单飞：抢到锁者刷新，未抢到者短暂等待后读缓存
        Boolean locked = redis.opsForValue().setIfAbsent(TOKEN_LOCK, "1", Duration.ofSeconds(15));
        if (Boolean.TRUE.equals(locked)) {
            try {
                String again = redis.opsForValue().get(TOKEN_KEY);
                if (again != null) {
                    return again;
                }
                String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s"
                        .formatted(properties.getAppId(), properties.getAppSecret());
                JsonNode body = getJson(url);
                if (!body.has("access_token")) {
                    throw new BusinessException(ErrorCode.WECHAT_API_ERROR);
                }
                long ttl = Math.max(60, body.path("expires_in").asLong(7200) - 300);
                redis.opsForValue().set(TOKEN_KEY, body.get("access_token").asText(),
                        Duration.ofSeconds(ttl));
                return body.get("access_token").asText();
            } finally {
                redis.delete(TOKEN_LOCK);
            }
        }
        for (int i = 0; i < 20; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String refreshed = redis.opsForValue().get(TOKEN_KEY);
            if (refreshed != null) {
                return refreshed;
            }
        }
        throw new BusinessException(ErrorCode.WECHAT_API_ERROR);
    }

    private JsonNode getJson(String url) {        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception ex) {
            log.warn("微信上游请求失败 type={} msg={}", ex.getClass().getSimpleName(),
                    sanitize(ex.getMessage()));
            throw new BusinessException(ErrorCode.WECHAT_API_ERROR);
        }
    }

    private String resolveEnvVersion(String requested) {
        String value = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if (List.of("release", "trial", "develop").contains(value)) {
            return value;
        }
        return properties.getEnvVersion();
    }

    /** 微信成功返回图片二进制（image/jpeg），失败返回 JSON——按首字节嗅探。 */
    private static String sniffContentType(byte[] body) {
        if (body.length > 4 && body[0] == (byte) 0xFF && body[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (body.length > 4 && body[0] == (byte) 0x89 && body[1] == 0x50) {
            return "image/png";
        }
        return "application/json";
    }

    /** 异常消息脱敏：剥离 URL（可能内嵌 secret/access_token）。 */
    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("https?://[a-zA-Z0-9.?=/&:_~-]+", "[url]");
    }
}
