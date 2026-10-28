package com.wzblog.common.media;

import com.wzblog.common.web.CurrentUser;

/**
 * M5 文件引用计数端口（00 §9.2）。入参为对象 key，不含域名与公开前缀。
 * <p>引用归属区分三种身份：文件上传者（{@code files.uploader_id}）、业务资源所有者
 * （如文章作者）、当前操作人。请求传入的新 key 必须由资源所有者或当前操作人上传；
 * 恢复服务端已存内容时 key 不来自请求，不校验上传者。</p>
 * <p>M5 未落地前由 no-op 实现接管；M5 提供 {@code FileAppService} 实现后自动替换。</p>
 */
public interface FileReferencePort {

    /**
     * 为业务资源新增一次引用；文件不存在、不可用，或上传者既不是 owner 也不是 actor 时
     * 抛业务异常（ADMIN 操作人可越过上传者校验）。
     */
    void claimByKey(String objectKey, Long ownerId, CurrentUser actor, FileBizType bizType);

    /**
     * 恢复服务端已存储内容（文章/修订）时新增引用：key 不来自请求，跳过上传者校验，
     * 仍校验文件存在、状态与用途。
     */
    void claimRecordedByKey(String objectKey, FileBizType bizType);

    /**
     * 释放一次引用；引用归零后由 M5 标记为可清理。
     */
    void releaseByKey(String objectKey);
}
