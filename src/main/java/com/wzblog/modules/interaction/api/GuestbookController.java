package com.wzblog.modules.interaction.api;

import com.wzblog.common.api.CursorPage;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.interaction.api.dto.GuestbookCreateRequest;
import com.wzblog.modules.interaction.api.vo.GuestbookVO;
import com.wzblog.modules.interaction.app.GuestbookAppService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 留言板路由。
 */
@RestController
@RequestMapping("/guestbook")
public class GuestbookController {

    private final GuestbookAppService guestbookAppService;

    public GuestbookController(GuestbookAppService guestbookAppService) {
        this.guestbookAppService = guestbookAppService;
    }

    @GetMapping
    public ResponseEntity<Result<CursorPage<GuestbookVO>>> list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(Result.ok(guestbookAppService.publicList(user, cursor, size)));
    }

    @PostMapping
    public ResponseEntity<Result<GuestbookVO>> create(
            @AuthenticationPrincipal CurrentUser user,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @Valid @RequestBody GuestbookCreateRequest request) {
        GuestbookVO created = guestbookAppService.create(user, ClientInfo.from(httpRequest),
                request.content(), request.nickname());
        return ResponseEntity.ok(Result.ok(created));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Object>> delete(@AuthenticationPrincipal CurrentUser user,
                                                 @PathVariable("id") Long id) {
        guestbookAppService.deleteByOwner(user, id);
        return ResponseEntity.ok(Result.ok(java.util.Map.of("id", String.valueOf(id))));
    }
}
