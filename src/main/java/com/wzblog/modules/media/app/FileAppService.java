package com.wzblog.modules.media.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.media.FileBizType;
import com.wzblog.common.media.FileReferencePort;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.media.api.dto.PresignRequest;
import com.wzblog.modules.media.api.vo.AdminFileVO;
import com.wzblog.modules.media.api.vo.FileVO;
import com.wzblog.modules.media.api.vo.PresignVO;
import com.wzblog.modules.media.api.vo.UrlVO;
import com.wzblog.modules.media.domain.FileEntry;
import com.wzblog.modules.media.domain.FileStatus;
import com.wzblog.modules.media.domain.event.FileActivatedEvent;
import com.wzblog.modules.media.infra.config.FileStorageProperties;
import com.wzblog.modules.media.infra.image.ImageAnalyzer;
import com.wzblog.modules.media.infra.mapper.FileMapper;
import com.wzblog.modules.media.infra.storage.ObjectStorage;
import com.wzblog.modules.media.infra.storage.UrlSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 媒体用例编排：预签名（本地模式为 HMAC 能力 URL）、multipart 兜底、确认去重、
 * URL 换取、删除与引用计数端口实现（M5 §8/§9/§10）。
 */
@Service
public class FileAppService implements FileReferencePort {

    private static final Logger log = LoggerFactory.getLogger(FileAppService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT);
    /** 本地模式桶名常量（docs/11 §5.1）。 */
    private static final String LOCAL_BUCKET = "local";
    /** key 正则兜底（M5 §6.2）。 */
    private static final java.util.regex.Pattern KEY_PATTERN = java.util.regex.Pattern.compile(
            "^(articles|avatars|banners|goods)/\\d{4}/\\d{2}/[0-9a-f]{32}\\.(jpg|png|webp)$");

    private final FileMapper fileMapper;
    private final ObjectStorage objectStorage;
    private final ImageAnalyzer imageAnalyzer;
    private final UrlSigner urlSigner;
    private final FileStorageProperties properties;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher eventPublisher;

    public FileAppService(FileMapper fileMapper, ObjectStorage objectStorage,
                          ImageAnalyzer imageAnalyzer, UrlSigner urlSigner,
                          FileStorageProperties properties, StringRedisTemplate redis,
                          ApplicationEventPublisher eventPublisher) {
        this.fileMapper = fileMapper;
        this.objectStorage = objectStorage;
        this.imageAnalyzer = imageAnalyzer;
        this.urlSigner = urlSigner;
        this.properties = properties;
        this.redis = redis;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------ 预签名

    @Transactional
    public PresignVO presign(CurrentUser user, PresignRequest request) {
        requireBizAllowed(user.role(), request.bizType(), true);
        imageAnalyzer.precheck(request.filename(), request.contentType(), request.size());
        checkPresignRate(user.id());

        String objectKey = generateKey(request.bizType(), request.filename());
        FileEntry entry = new FileEntry();
        entry.setBucket(LOCAL_BUCKET);
        entry.setObjectKey(objectKey);
        entry.setOriginalName(request.filename());
        entry.setMimeType(request.contentType());
        entry.setSizeBytes(request.size());
        entry.setUploaderId(user.id());
        entry.setBizType(request.bizType().name());
        entry.setStatus(FileStatus.TEMP);
        entry.setRefCount(0);
        fileMapper.insert(entry);

        Instant expiresAt = Instant.now().plus(properties.getPresignTtl());
        String token = encodeUploadToken(objectKey, expiresAt);
        String uploadUrl = stripTrailingSlash(properties.getUploadBaseUrl())
                + "/media-upload/" + token;
        return new PresignVO(String.valueOf(entry.getId()), objectKey, uploadUrl, expiresAt);
    }

    /** 本地模式的直传令牌：{objectKey}|{exp}|{hmac}，URL-safe Base64。 */
    private String encodeUploadToken(String objectKey, Instant expiresAt) {
        String signature = urlSigner.sign(objectKey, expiresAt);
        String payload = objectKey + "|" + expiresAt.getEpochSecond() + "|" + signature;
        return Base64Url.encode(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** PUT /media-upload/{token} 的入口：校验签名后落对象。返回对象 key，失败抛业务异常。 */
    public String storeDirect(String token, byte[] body) {
        String payload;
        try {
            payload = new String(Base64Url.decode(token), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "直传地址非法", null);
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 3) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "直传地址非法", null);
        }
        String objectKey = parts[0];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "直传地址非法", null);
        }
        if (!KEY_PATTERN.matcher(objectKey).matches()
                || !urlSigner.verify(objectKey, expiresAt, parts[2])) {
            throw new BusinessException(ErrorCode.FILE_PRESIGN_FORBIDDEN, "直传地址无效或已过期", null);
        }
        if (body.length > properties.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        objectStorage.put(objectKey, body);
        return objectKey;
    }

    // ------------------------------------------------------------ multipart 兜底

    @Transactional
    public FileVO upload(CurrentUser user, MultipartFile file, FileBizType bizType) {
        requireBizAllowed(user.role(), bizType, false);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "文件不能为空", null);
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        imageAnalyzer.precheck(filename, contentType, file.getSize());

        byte[] bytes = readAll(file);
        ImageAnalyzer.ImageMeta meta = imageAnalyzer.analyze(bytes);
        String sha256 = sha256Hex(bytes);

        FileEntry existing = fileMapper.selectDedupCandidate(sha256, (long) bytes.length);
        if (existing != null) {
            ensureOwnerAndBiz(existing, user, bizType);
            return toVo(existing);
        }

        String objectKey = generateKey(bizType, filename);
        objectStorage.put(objectKey, bytes);

        FileEntry entry = new FileEntry();
        entry.setBucket(LOCAL_BUCKET);
        entry.setObjectKey(objectKey);
        entry.setOriginalName(filename);
        entry.setMimeType(meta.mimeType());
        entry.setSizeBytes((long) bytes.length);
        entry.setSha256(sha256);
        entry.setWidth(meta.width() == 0 ? null : meta.width());
        entry.setHeight(meta.height() == 0 ? null : meta.height());
        entry.setUploaderId(user.id());
        entry.setBizType(bizType.name());
        entry.setStatus(FileStatus.ACTIVE);
        entry.setRefCount(0);
        fileMapper.insert(entry);
        eventPublisher.publishEvent(new FileActivatedEvent(entry.getId(), objectKey));
        return toVo(entry);
    }

    // ------------------------------------------------------------ 确认

    /**
     * 校验失败路径在抛出前已把行置 DELETED 并删对象，业务异常不能回滚这些清理动作。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public FileVO confirm(CurrentUser user, Long id) {
        FileEntry entry = fileMapper.selectForUpdateById(id);
        if (entry == null || entry.isDeleted()) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        requireOwnerOrAdmin(user, entry);

        if (entry.getStatus() == FileStatus.ACTIVE || entry.getStatus() == FileStatus.ORPHAN) {
            return toVo(entry); // 幂等
        }
        LocalDateTime createdAt = entry.getCreatedAt();
        if (createdAt != null
                && createdAt.isBefore(LocalDateTime.now(ZoneOffset.UTC).minus(properties.getConfirmTimeout()))) {
            fileMapper.softDelete(id, Times.nowUtc());
            objectStorage.delete(entry.getObjectKey());
            throw new BusinessException(ErrorCode.FILE_CONFIRM_TIMEOUT);
        }

        byte[] bytes = objectStorage.get(entry.getObjectKey());
        if (bytes == null) {
            throw new BusinessException(ErrorCode.FILE_OBJECT_MISSING);
        }
        if (bytes.length > properties.getMaxSizeBytes()
                || entry.getSizeBytes() != null && entry.getSizeBytes() != bytes.length) {
            markDeletedAndRemoveObject(entry);
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        ImageAnalyzer.ImageMeta meta;
        try {
            meta = imageAnalyzer.analyze(bytes);
        } catch (BusinessException ex) {
            markDeletedAndRemoveObject(entry);
            throw ex;
        }
        String sha256 = sha256Hex(bytes);

        FileEntry canonical = fileMapper.selectDedupCandidate(sha256, (long) bytes.length);
        if (canonical != null && !canonical.getId().equals(entry.getId())) {
            ensureOwnerAndBiz(canonical, user, FileBizType.valueOf(entry.getBizType()));
            markDeletedAndRemoveObject(entry);
            return toVo(canonical);
        }

        int updated = fileMapper.activate(id, meta.mimeType(), (long) bytes.length, sha256,
                meta.width() == 0 ? null : meta.width(), meta.height() == 0 ? null : meta.height(),
                Times.nowUtc());
        if (updated == 0) {
            FileEntry refreshed = fileMapper.selectByIdIncludeDeleted(id);
            if (refreshed != null && refreshed.getStatus() == FileStatus.ACTIVE) {
                return toVo(refreshed); // 并发确认：另一请求已生效
            }
            throw new BusinessException(ErrorCode.FILE_CONFIRM_TIMEOUT);
        }
        eventPublisher.publishEvent(new FileActivatedEvent(id, entry.getObjectKey()));
        return toVo(fileMapper.selectByIdIncludeDeleted(id));
    }

    // ------------------------------------------------------------ URL

    @Transactional(readOnly = true)
    public UrlVO url(CurrentUser user, Long id, Integer expiresSeconds) {
        FileEntry entry = fileMapper.selectByIdIncludeDeleted(id);
        if (entry == null || entry.isDeleted()) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        if (entry.getStatus() == FileStatus.ACTIVE || entry.getStatus() == FileStatus.ORPHAN) {
            return new UrlVO(String.valueOf(entry.getId()), publicUrlOf(entry.getObjectKey()),
                    thumbUrlOf(entry), null);
        }
        // TEMP：仅上传者/管理员可换取短期签名 URL
        requireOwnerOrAdmin(user, entry);
        int ttl = clamp(expiresSeconds == null ? 300 : expiresSeconds, 60, 3600);
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        String signature = urlSigner.sign(entry.getObjectKey(), expiresAt);
        String url = publicUrlOf(entry.getObjectKey()) + "?exp=" + expiresAt.getEpochSecond()
                + "&sig=" + signature;
        return new UrlVO(String.valueOf(entry.getId()), url, null, expiresAt);
    }

    // ------------------------------------------------------------ 删除

    @Transactional
    public void delete(CurrentUser user, Long id) {
        FileEntry entry = fileMapper.selectForUpdateById(id);
        if (entry == null || entry.isDeleted()) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        requireOwnerOrAdmin(user, entry);
        if (entry.getRefCount() != null && entry.getRefCount() > 0) {
            throw new BusinessException(ErrorCode.FILE_IN_USE);
        }
        if (fileMapper.softDelete(id, Times.nowUtc()) == 0) {
            throw new BusinessException(ErrorCode.FILE_IN_USE);
        }
        objectStorage.delete(entry.getObjectKey());
        if (entry.getThumbKey() != null) {
            objectStorage.delete(entry.getThumbKey());
        }
    }

    // ------------------------------------------------------------ 后台

    @Transactional(readOnly = true)
    public PageResult<AdminFileVO> adminList(FileStatus status, Long uploaderId, int page, int pageSize) {
        long total = fileMapper.countAdminPage(status, uploaderId);
        int offset = (page - 1) * pageSize;
        List<AdminFileVO> list = fileMapper.selectAdminPage(status, uploaderId, pageSize, offset)
                .stream().map(this::toAdminVo).toList();
        boolean hasNext = offset + list.size() < total;
        return new PageResult<>(list, total, page, pageSize, hasNext);
    }

    // ------------------------------------------------------------ FileReferencePort

    @Override
    public void claimByKey(String objectKey, Long ownerId, FileBizType bizType) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        String key = objectKey.trim();
        // 先校验归属与用途，再原子递增；必须运行在调用方事务内，校验失败由其回滚
        FileEntry entry = fileMapper.selectByObjectKey(key);
        if (entry == null || entry.isDeleted()) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "引用的图片不存在: " + objectKey, null);
        }
        if (entry.getStatus() == FileStatus.TEMP) {
            throw new BusinessException(ErrorCode.FILE_CONFIRM_TIMEOUT,
                    "图片尚未确认上传: " + objectKey, null);
        }
        if (entry.getUploaderId() != null && ownerId != null && !entry.getUploaderId().equals(ownerId)) {
            throw new BusinessException(ErrorCode.FILE_PRESIGN_FORBIDDEN, "不能引用他人的文件", null);
        }
        if (bizType != null && entry.getBizType() != null && !entry.getBizType().equals(bizType.name())) {
            throw new BusinessException(ErrorCode.FILE_PRESIGN_FORBIDDEN, "文件用途不匹配", null);
        }
        if (fileMapper.claimByKey(key, Times.nowUtc()) == 0) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "引用的图片不存在: " + objectKey, null);
        }
    }

    @Override
    public void releaseByKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        int released = fileMapper.releaseByKey(objectKey.trim(), Times.nowUtc());
        if (released == 0) {
            log.debug("releaseByKey 未生效（引用已为 0 或不存在）: {}", objectKey);
        }
    }

    // ------------------------------------------------------------ 对象读取（media 路由）

    /** GET /media/{key}：ACTIVE/ORPHAN 公开；TEMP 需有效签名。 */
    public MediaContent readForServe(String objectKey, Long exp, String sig) {
        FileEntry entry = fileMapper.selectByObjectKey(objectKey);
        if (entry == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        if (entry.getStatus() == FileStatus.ACTIVE || entry.getStatus() == FileStatus.ORPHAN) {
            byte[] bytes = objectStorage.get(objectKey);
            if (bytes == null) {
                throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
            }
            return new MediaContent(bytes, entry.getMimeType(), true);
        }
        if (entry.getStatus() == FileStatus.TEMP
                && exp != null && sig != null && urlSigner.verify(objectKey, exp, sig)) {
            byte[] bytes = objectStorage.get(objectKey);
            if (bytes == null) {
                throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
            }
            return new MediaContent(bytes, entry.getMimeType(), false);
        }
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }

    public record MediaContent(byte[] bytes, String contentType, boolean cacheable) {
    }

    // ------------------------------------------------------------ 内部

    private void requireBizAllowed(Role role, FileBizType bizType, boolean presignContext) {
        boolean allowed = switch (bizType) {
            case ARTICLE_COVER, CONTENT_IMAGE -> role == Role.EDITOR || role == Role.ADMIN;
            case AVATAR -> !presignContext; // presign 不开放 AVATAR，multipart 可用
            case BANNER, GOODS -> role == Role.ADMIN;
        };
        if (!allowed) {
            throw new BusinessException(ErrorCode.FILE_PRESIGN_FORBIDDEN);
        }
    }

    private void ensureOwnerAndBiz(FileEntry entry, CurrentUser user, FileBizType bizType) {
        if (entry.getUploaderId() != null && !entry.getUploaderId().equals(user.id())
                && user.role() != Role.ADMIN) {
            throw new BusinessException(ErrorCode.FILE_PRESIGN_FORBIDDEN, "不能复用他人的文件", null);
        }
        if (bizType != null && entry.getBizType() != null && !entry.getBizType().equals(bizType.name())) {
            throw new BusinessException(ErrorCode.FILE_PRESIGN_FORBIDDEN, "文件用途不匹配", null);
        }
    }

    private void requireOwnerOrAdmin(CurrentUser user, FileEntry entry) {
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        if (user.role() != Role.ADMIN && !user.id().equals(entry.getUploaderId())) {
            throw new BusinessException(ErrorCode.FILE_PRESIGN_FORBIDDEN);
        }
    }

    private void checkPresignRate(Long userId) {
        String key = "wzblog:media:presign-rate:" + userId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > properties.getPresignLimitPerHour()) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "上传过于频繁，请稍后再试", 3600L);
        }
    }

    private String generateKey(FileBizType bizType, String filename) {
        String biz = switch (bizType) {
            case ARTICLE_COVER, CONTENT_IMAGE -> "articles";
            case AVATAR -> "avatars";
            case BANNER -> "banners";
            case GOODS -> "goods";
        };
        String ext = switch (imageAnalyzer.extensionOf(filename)) {
            case "jpeg", "jpg" -> "jpg";
            case "png" -> "png";
            case "webp" -> "webp";
            default -> throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        };
        String month = MONTH.format(LocalDateTime.now(ZoneOffset.UTC));
        return biz + "/" + month + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    private void markDeletedAndRemoveObject(FileEntry entry) {
        fileMapper.softDelete(entry.getId(), Times.nowUtc());
        objectStorage.delete(entry.getObjectKey());
    }

    private byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "读取上传文件失败", null);
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String publicUrlOf(String objectKey) {
        return stripTrailingSlash(properties.getPublicBaseUrl()) + "/" + objectKey;
    }

    private String thumbUrlOf(FileEntry entry) {
        return entry.getThumbKey() == null ? null
                : stripTrailingSlash(properties.getPublicBaseUrl()) + "/" + entry.getThumbKey();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private FileVO toVo(FileEntry entry) {
        return new FileVO(String.valueOf(entry.getId()), entry.getObjectKey(),
                entry.getStatus().name(), Times.toInstant(entry.getCreatedAt()),
                entry.getMimeType(), entry.getSizeBytes(), entry.getWidth(), entry.getHeight(),
                entry.getStatus() == FileStatus.ACTIVE || entry.getStatus() == FileStatus.ORPHAN
                        ? publicUrlOf(entry.getObjectKey()) : null,
                thumbUrlOf(entry));
    }

    private AdminFileVO toAdminVo(FileEntry entry) {
        return new AdminFileVO(String.valueOf(entry.getId()), entry.getObjectKey(),
                entry.getStatus().name(), Times.toInstant(entry.getCreatedAt()),
                entry.getMimeType(), entry.getSizeBytes(), entry.getWidth(), entry.getHeight(),
                publicUrlOf(entry.getObjectKey()), thumbUrlOf(entry),
                entry.getOriginalName(), entry.getSha256(),
                entry.getUploaderId() == null ? null : String.valueOf(entry.getUploaderId()),
                entry.getBizType(), entry.getRefCount(), entry.getThumbKey(),
                Times.toInstant(entry.getDeletedAt()));
    }

    /** URL-safe Base64 工具（无填充）。 */
    static final class Base64Url {
        static String encode(byte[] bytes) {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        static byte[] decode(String value) {
            return java.util.Base64.getUrlDecoder().decode(value);
        }
    }
}
