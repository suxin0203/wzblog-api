package com.wzblog.modules.media.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.media.domain.FileEntry;
import com.wzblog.modules.media.domain.FileStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件元数据读写。状态迁移一律条件更新，影响行数由调用方判定。
 */
@Mapper
public interface FileMapper extends BaseMapper<FileEntry> {

    String COLUMNS = """
            id, bucket, object_key, thumb_key, original_name, mime_type, size_bytes, sha256,
            width, height, uploader_id, biz_type, status, ref_count, created_at, updated_at, deleted_at
            """;

    @Select("SELECT " + COLUMNS + " FROM files WHERE id = #{id} FOR UPDATE")
    FileEntry selectForUpdateById(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM files WHERE id = #{id}")
    FileEntry selectByIdIncludeDeleted(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM files WHERE object_key = #{objectKey} LIMIT 1")
    FileEntry selectByObjectKey(@Param("objectKey") String objectKey);

    /** 去重查询：同 sha256 + size 的可用文件（ACTIVE/ORPHAN）。 */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM files
            WHERE sha256 = #{sha256} AND size_bytes = #{sizeBytes}
              AND status IN ('ACTIVE', 'ORPHAN')
            ORDER BY id
            LIMIT 1
            """)
    FileEntry selectDedupCandidate(@Param("sha256") String sha256, @Param("sizeBytes") Long sizeBytes);

    /** TEMP → ACTIVE，写入探测结果；条件更新保证并发确认仅一次生效。 */
    @Update("""
            UPDATE files
            SET status = 'ACTIVE', mime_type = #{mimeType}, size_bytes = #{sizeBytes},
                sha256 = #{sha256}, width = #{width}, height = #{height}, updated_at = #{updatedAt}
            WHERE id = #{id} AND status = 'TEMP'
            """)
    int activate(@Param("id") Long id, @Param("mimeType") String mimeType,
                 @Param("sizeBytes") Long sizeBytes, @Param("sha256") String sha256,
                 @Param("width") Integer width, @Param("height") Integer height,
                 @Param("updatedAt") LocalDateTime updatedAt);

    /** 引用 +1 并把 ORPHAN 拉回 ACTIVE；uk_object 定位（M5 §9.1）。 */
    @Update("""
            UPDATE files
            SET ref_count = ref_count + 1, status = 'ACTIVE', updated_at = #{updatedAt}
            WHERE object_key = #{objectKey} AND status IN ('ACTIVE', 'ORPHAN')
            """)
    int claimByKey(@Param("objectKey") String objectKey, @Param("updatedAt") LocalDateTime updatedAt);

    /** 引用 -1，不减到 0 以下；不改状态（M5 §9.2）。 */
    @Update("""
            UPDATE files
            SET ref_count = ref_count - 1, updated_at = #{updatedAt}
            WHERE object_key = #{objectKey} AND ref_count > 0
            """)
    int releaseByKey(@Param("objectKey") String objectKey, @Param("updatedAt") LocalDateTime updatedAt);

    /** OWNER/ADMIN 删除：仅 ref_count=0 且未删除的行可转 DELETED。 */
    @Update("""
            UPDATE files
            SET status = 'DELETED', deleted_at = #{deletedAt}, updated_at = #{deletedAt}
            WHERE id = #{id} AND status IN ('TEMP', 'ACTIVE', 'ORPHAN') AND ref_count = 0
            """)
    int softDelete(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);

    @Update("UPDATE files SET thumb_key = #{thumbKey}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateThumbKey(@Param("id") Long id, @Param("thumbKey") String thumbKey,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** 后台列表：按状态/上传者过滤的偏移分页。 */
    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM files
            <where>
                <if test="status != null"> AND status = #{status}</if>
                <if test="uploaderId != null"> AND uploader_id = #{uploaderId}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<FileEntry> selectAdminPage(@Param("status") FileStatus status,
                                    @Param("uploaderId") Long uploaderId,
                                    @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM files
            <where>
                <if test="status != null"> AND status = #{status}</if>
                <if test="uploaderId != null"> AND uploader_id = #{uploaderId}</if>
            </where>
            </script>
            """)
    long countAdminPage(@Param("status") FileStatus status, @Param("uploaderId") Long uploaderId);

    /** 清理 A 步：ACTIVE ref=0 超宽限 → ORPHAN。 */
    @Update("""
            UPDATE files
            SET status = 'ORPHAN', updated_at = #{now}
            WHERE status = 'ACTIVE' AND ref_count = 0 AND updated_at < #{threshold}
            """)
    int markOrphans(@Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    /** 清理 B/C 步候选：TEMP 超 24h 或 ORPHAN 超宽限，逐行删除。 */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM files
            WHERE (status = 'TEMP' AND created_at < #{tempThreshold})
               OR (status = 'ORPHAN' AND ref_count = 0 AND updated_at < #{orphanThreshold})
            ORDER BY id
            LIMIT #{limit}
            """)
    List<FileEntry> selectCleanupCandidates(@Param("tempThreshold") LocalDateTime tempThreshold,
                                            @Param("orphanThreshold") LocalDateTime orphanThreshold,
                                            @Param("limit") int limit);

    /** 清理 D 步候选：已置 DELETED 但对象删除可能失败的行（7 天内）。 */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM files
            WHERE status = 'DELETED' AND deleted_at > #{threshold}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<FileEntry> selectDeletedRecent(@Param("threshold") LocalDateTime threshold,
                                        @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM files WHERE status = 'TEMP' AND created_at < #{tempThreshold}")
    long countExpiredTemp(@Param("tempThreshold") LocalDateTime tempThreshold);

    @Select("""
            SELECT COUNT(*) FROM files
            WHERE status = 'ACTIVE' AND ref_count = 0 AND updated_at < #{threshold}
            """)
    long countOrphanCandidates(@Param("threshold") LocalDateTime threshold);

    @Select("""
            SELECT COUNT(*) FROM files
            WHERE status = 'ORPHAN' AND ref_count = 0 AND updated_at < #{threshold}
            """)
    long countOrphanExpired(@Param("threshold") LocalDateTime threshold);
}
