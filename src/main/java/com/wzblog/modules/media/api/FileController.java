package com.wzblog.modules.media.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.media.FileBizType;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.media.api.dto.PresignRequest;
import com.wzblog.modules.media.api.vo.FileVO;
import com.wzblog.modules.media.api.vo.PresignVO;
import com.wzblog.modules.media.api.vo.UrlVO;
import com.wzblog.modules.media.app.FileAppService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件路由（M5 §3，5 条用户路由）。
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private final FileAppService fileAppService;

    public FileController(FileAppService fileAppService) {
        this.fileAppService = fileAppService;
    }

    /** 角色限制由服务层的角色×用途矩阵给出精确的 FILE_PRESIGN_FORBIDDEN（TC-M5-002）。 */
    @PostMapping("/presign")
    public ResponseEntity<Result<PresignVO>> presign(@AuthenticationPrincipal CurrentUser user,
                                                     @Valid @RequestBody PresignRequest request) {
        return ResponseEntity.ok(Result.ok(fileAppService.presign(user, request)));
    }

    @PostMapping("/upload")
    public ResponseEntity<Result<FileVO>> upload(@AuthenticationPrincipal CurrentUser user,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam("bizType") FileBizType bizType) {
        return ResponseEntity.ok(Result.ok(fileAppService.upload(user, file, bizType)));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Result<FileVO>> confirm(@AuthenticationPrincipal CurrentUser user,
                                                  @PathVariable("id") Long id) {
        return ResponseEntity.ok(Result.ok(fileAppService.confirm(user, id)));
    }

    @GetMapping("/{id}/url")
    public ResponseEntity<Result<UrlVO>> url(@AuthenticationPrincipal CurrentUser user,
                                             @PathVariable("id") Long id,
                                             @RequestParam(value = "expires", required = false) Integer expires) {
        return ResponseEntity.ok(Result.ok(fileAppService.url(user, id, expires)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Object>> delete(@AuthenticationPrincipal CurrentUser user,
                                                 @PathVariable("id") Long id) {
        fileAppService.delete(user, id);
        return ResponseEntity.ok(Result.ok(null));
    }
}
