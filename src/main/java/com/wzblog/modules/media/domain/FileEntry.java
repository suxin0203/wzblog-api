package com.wzblog.modules.media.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * files 表实体（V1__schema.sql）。本地磁盘模式下 bucket 固定为 {@code local}（docs/11 §5.1）。
 */
@Getter
@Setter
@TableName("files")
public class FileEntry {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String bucket;
    private String objectKey;
    private String thumbKey;
    private String originalName;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private Integer width;
    private Integer height;
    private Long uploaderId;
    private String bizType;
    private FileStatus status;
    private Integer refCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return status == FileStatus.DELETED;
    }
}
