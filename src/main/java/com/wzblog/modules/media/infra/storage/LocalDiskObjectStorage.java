package com.wzblog.modules.media.infra.storage;

import com.wzblog.modules.media.infra.config.FileStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘对象存储（默认实现，wzblog.file.storage=local）。
 * <p>对象 key 形如 {@code articles/2026/10/uuid.jpg}，直接映射为 {@code {root}/{key}}；
 * 解析后强制校验真实路径位于根目录内，杜绝路径穿越。</p>
 */
@Component
@ConditionalOnProperty(name = "wzblog.file.storage", havingValue = "local", matchIfMissing = true)
public class LocalDiskObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskObjectStorage.class);

    private final Path root;

    public LocalDiskObjectStorage(FileStorageProperties properties) {
        this.root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建文件存储根目录: " + root, ex);
        }
        log.info("Local object storage ready, root={}", root);
    }

    @Override
    public void put(String objectKey, byte[] bytes) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("写入对象失败: " + objectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    @Override
    public byte[] get(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new IllegalStateException("读取对象失败: " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException ex) {
            log.warn("删除对象失败（将由清理任务补偿）: {}", objectKey, ex);
        }
    }

    private Path resolve(String objectKey) {
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法对象 key: " + objectKey);
        }
        return resolved;
    }
}
