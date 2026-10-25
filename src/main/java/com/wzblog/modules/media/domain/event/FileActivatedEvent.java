package com.wzblog.modules.media.domain.event;

/**
 * 文件确认/上传成功事件（AFTER_COMMIT）：缩略图异步生成的触发器。
 */
public record FileActivatedEvent(Long fileId, String objectKey) {
}
