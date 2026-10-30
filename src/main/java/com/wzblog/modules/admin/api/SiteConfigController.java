package com.wzblog.modules.admin.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.app.SiteConfigAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站点配置路由：公开白名单 + 后台管理。
 */
@RestController
public class SiteConfigController {

    private final SiteConfigAppService siteConfigAppService;

    public SiteConfigController(SiteConfigAppService siteConfigAppService) {
        this.siteConfigAppService = siteConfigAppService;
    }

    @GetMapping("/public/configs")
    public ResponseEntity<Result<Map<String, String>>> publicConfigs() {
        return ResponseEntity.ok(Result.ok(siteConfigAppService.publicMap()));
    }

    @GetMapping("/admin/configs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Map<String, Object>>> adminConfigs() {
        return ResponseEntity.ok(Result.ok(siteConfigAppService.adminMap()));
    }

    @PutMapping("/admin/configs/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Object>> update(@AuthenticationPrincipal CurrentUser admin,
                                                 @PathVariable("key") String key,
                                                 @RequestBody Map<String, String> body) {
        String value = body.get("value");
        if (value == null) {
            // 缺 value 时不能按空串落库，否则会把配置静默清空
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "请求体缺少 value 字段", null);
        }
        siteConfigAppService.update(admin, key, value);
        return ResponseEntity.ok(Result.ok(null));
    }
}
