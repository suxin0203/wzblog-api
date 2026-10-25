package com.wzblog.modules.media.api;

import com.wzblog.common.web.InfrastructureRoute;
import com.wzblog.modules.media.app.FileAppService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 本地磁盘模式的基础设施路由（非业务契约，不加 /api/v1 前缀）：
 * <ul>
 *   <li>{@code PUT /media-upload/{token}} —— 模拟预签名 PUT，令牌自校验（HMAC + TTL）；</li>
 *   <li>{@code GET /media/{*objectKey}} —— 公开读；TEMP 对象需 ?exp&sig 签名。</li>
 * </ul>
 */
@InfrastructureRoute
@RestController
public class MediaServeController {

    private final FileAppService fileAppService;

    public MediaServeController(FileAppService fileAppService) {
        this.fileAppService = fileAppService;
    }

    @PutMapping("/media-upload/{token}")
    public ResponseEntity<Void> uploadDirect(@PathVariable("token") String token,
                                             @RequestBody(required = false) byte[] body) {
        fileAppService.storeDirect(token, body == null ? new byte[0] : body);
        return ResponseEntity.ok().build();
    }

    /** {*objectKey} 捕获含斜杠的剩余路径；公开对象长缓存（key 为 UUID 不可变）。 */
    @GetMapping("/media/{*objectKey}")
    public ResponseEntity<byte[]> serve(@PathVariable("objectKey") String objectKey,
                                        @RequestParam(value = "exp", required = false) Long exp,
                                        @RequestParam(value = "sig", required = false) String sig) {
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        FileAppService.MediaContent content = fileAppService.readForServe(key, exp, sig);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        content.contentType() == null ? "application/octet-stream" : content.contentType()));
        if (content.cacheable()) {
            builder.cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        } else {
            builder.cacheControl(CacheControl.noStore());
        }
        return builder.body(content.bytes());
    }
}
