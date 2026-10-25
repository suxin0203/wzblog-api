package com.wzblog.modules.media.infra.storage;

/**
 * 对象存储端口：本地磁盘与 MinIO 等实现的统一抽象。
 * <p>当前项目文件上限 5MB，接口按字节数组设计；若未来放开大文件，应改为流式。</p>
 */
public interface ObjectStorage {

    /** 写入对象；key 已由服务端生成并校验。 */
    void put(String objectKey, byte[] bytes);

    /** 对象是否存在。 */
    boolean exists(String objectKey);

    /** 读取对象；不存在返回 null。 */
    byte[] get(String objectKey);

    /** 删除对象；不存在视为成功（幂等）。 */
    void delete(String objectKey);
}
