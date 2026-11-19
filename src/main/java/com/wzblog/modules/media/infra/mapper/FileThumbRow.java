package com.wzblog.modules.media.infra.mapper;

import lombok.Getter;
import lombok.Setter;

/**
 * 缩略图批量查询的投影行（object_key → thumb_key，列表封面展示接线）。
 */
@Getter
@Setter
public class FileThumbRow {

    private String objectKey;
    private String thumbKey;
}
