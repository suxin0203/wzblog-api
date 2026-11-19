package com.wzblog.common.api;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 游标编解码：{@code Base64URL("{published_at_epoch_ms}:{id}")}（M2 §8）。
 *
 * <p>【教学：游标分页的"不透明令牌"设计】</p>
 * <p>游标里编码的是"翻页锚点"——上一页最后一条记录的（发布时间, id）二元组。
 * 三个关键设计：</p>
 * <ul>
 *   <li><b>对客户端不透明</b>：只暴露 Base64 混淆串，内部格式（时间+id）将来想换
 *       （比如换成"热度+id"）客户端零改动。切勿让前端理解/拼装游标内容。</li>
 *   <li><b>复合键 tie-breaker</b>：同一毫秒可能发布多篇文章，光按时间定位会漏/重；
 *       追加 id 做第二排序键后，{@code (published_at, id) < (cursor_time, cursor_id)}
 *       的元组比较能精确"从最后一条之后继续"（见 ArticleMapper.selectFeed）。</li>
 *   <li><b>非法输入转业务异常</b>：伪造/损坏的游标得到干净的 400 而不是 500 ——
 *       catch 里统一翻译，不把底层 IllegalArgumentException 泄出去。</li>
 * </ul>
 *
 * <p>时间用 epoch 毫秒存储而非字符串：紧凑、无时区歧义、解析零成本。</p>
 */
public final class CursorCodec {

    /** 私有构造：工具类禁止实例化（final + 私有构造 + 静态方法三件套）。 */
    private CursorCodec() {
    }

    /** 解码后的游标值对象：翻页锚点 =（发布时间, 同刻并列时的决胜 id）。 */
    public record Cursor(Instant publishedAt, long id) {
    }

    /**
     * 把"最后一条记录"编码为不透明游标字符串。
     * LocalDateTime 按 UTC 解释（本项目 DB 全 UTC 口径），转 epoch 毫秒后与 id 拼接再 Base64URL。
     * 用 URL 安全字母表（- 和 _ 替换 + 和 /）：游标会出现在 query string 里，
     * 标准 Base64 的 +/ 需要转义，URL 版不用。
     */
    public static String encode(LocalDateTime publishedAt, long id) {
        long epochMillis = publishedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        String raw = epochMillis + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码客户端回传的游标。
     * @return null 表示"第一页"（未传游标）；否则返回锚点
     * @throws BusinessException COMMON_VALIDATION_ERROR 游标格式非法/被伪造
     */
    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;                       // 首页请求：没有锚点，从头开始
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            // 分隔符必须在中间：靠头说明时间缺失，靠尾说明 id 缺失 —— 都是坏游标
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("bad cursor");
            }
            long epochMillis = Long.parseLong(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            if (id <= 0) {
                throw new IllegalArgumentException("bad cursor id");
            }
            return new Cursor(Instant.ofEpochMilli(epochMillis), id);
        } catch (RuntimeException ex) {
            // 统一出口：任何解析失败（Base64 坏、数字解析失败、自抛的 IAE）都翻译成 400
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "cursor 非法", null);
        }
    }

    /** 辅助：把游标里的 Instant 转回 DB 层使用的 UTC LocalDateTime（Times 工具的本地版）。 */
    public static LocalDateTime toUtc(Cursor cursor) {
        return LocalDateTime.ofInstant(cursor.publishedAt(), ZoneOffset.UTC);
    }
}
