package com.wzblog.modules.media.infra.image;

import com.wzblog.WzBlogApplication;
import com.wzblog.modules.media.infra.mapper.FileMapper;
import com.wzblog.modules.media.infra.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一次性维护工具（默认禁用，不带系统属性时整体跳过，不影响全量门禁）：
 * 按当前 FILE_THUMB_LONGEST 规格为存量 ACTIVE 图片重新生成缩略图，规格统一。
 * 运行：mvn test -Dtest=ThumbBackfillManualTest -Dbackfill.thumbs=true
 * （local profile，直连 .env 的 MySQL 与 ./data/uploads 磁盘存储，勿在 CI/生产库上直接运行）。
 * 口径见 decisions.md【待分配编号】列表封面缩略图展示接线。
 */
@EnabledIfSystemProperty(named = "backfill.thumbs", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ThumbBackfillManualTest {

    @Autowired
    private ThumbnailGenerator generator;
    @Autowired
    private FileMapper fileMapper;
    @Autowired
    private ObjectStorage objectStorage;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void regenerateThumbsForActiveImages() {
        record Row(long id, String objectKey, String thumbKey) {
        }
        List<Row> rows = jdbc.query("""
                SELECT id, object_key, thumb_key FROM files
                WHERE status = 'ACTIVE' AND deleted_at IS NULL
                  AND mime_type IN ('image/jpeg', 'image/png')
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("object_key"), rs.getString("thumb_key")));
        long before = rows.stream().filter(r -> r.thumbKey() != null).count();

        int regenerated = 0;
        for (Row row : rows) {
            byte[] bytes = objectStorage.get(row.objectKey());
            if (bytes == null) {
                System.out.printf("[thumb-backfill] 磁盘缺失，跳过 id=%d key=%s%n", row.id(), row.objectKey());
                continue; // B09 类磁盘缺失行，不处理
            }
            generator.generate(row.id(), row.objectKey(), bytes);
            String newThumbKey = jdbc.queryForObject(
                    "SELECT thumb_key FROM files WHERE id = ?", String.class, row.id());
            // 旧缩略图仅在成功换新 key 后删除（generate 对够小/不可解码的源会跳过，保留旧对象）
            if (row.thumbKey() != null && newThumbKey != null && !newThumbKey.equals(row.thumbKey())) {
                objectStorage.delete(row.thumbKey());
            }
            regenerated++;
        }

        Integer after = jdbc.queryForObject("""
                SELECT COUNT(*) FROM files
                WHERE status = 'ACTIVE' AND deleted_at IS NULL
                  AND mime_type IN ('image/jpeg', 'image/png') AND thumb_key IS NOT NULL
                """, Integer.class);
        System.out.printf("[thumb-backfill] active-images=%d before-with-thumb=%d regenerated=%d after-with-thumb=%d%n",
                rows.size(), before, regenerated, after);
        assertThat(after == null ? 0L : after.longValue()).isGreaterThanOrEqualTo(before);
    }
}
