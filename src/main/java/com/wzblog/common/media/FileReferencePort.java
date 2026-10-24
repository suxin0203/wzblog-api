package com.wzblog.common.media;

/**
 * M5 文件引用计数端口（00 §9.2）。入参为对象 key，不含域名与公开前缀。
 * <p>M5 未落地前由 no-op 实现接管；M5 提供 {@code FileAppService} 实现后自动替换。</p>
 */
public interface FileReferencePort {

    /**
     * 增加一次引用；文件不存在或不属于 owner 时应抛业务异常（由 M5 实现）。
     */
    void claimByKey(String objectKey, Long ownerId, FileBizType bizType);

    /**
     * 释放一次引用；引用归零后由 M5 标记为可清理。
     */
    void releaseByKey(String objectKey);
}