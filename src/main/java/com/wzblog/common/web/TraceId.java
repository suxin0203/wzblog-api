package com.wzblog.common.web;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * TraceId 工具：基于 SLF4J MDC 的链路追踪号存取（教学详解见 docs/学习文档/04 与 13）。
 *
 * <p>【教学：MDC 是什么】Mapped Diagnostic Context —— SLF4J 提供的<b>线程级</b>键值上下文。
 * 线程里 put 过的值，此后该线程打出的每一行日志都会自动携带（配合 logback pattern 里的
 * {@code %X{traceId:-}} 占位符）。这是"给日志加请求维度"的标准手法，不需要给每个
 * log 语句手工传参。</p>
 *
 * <p>【典型排障流】用户报障给出响应头里的 traceId → {@code grep traceId server.log}
 * → 这次请求经过的所有日志行按序呈现。生产系统的第一基础设施。</p>
 *
 * <p>【线程模型注意】MDC 是 ThreadLocal 实现：线程池复用线程时会读到上一个请求的
 * 残留值 —— 所以必须由 {@link TraceIdFilter} 在 finally 里清理（见其注释）。</p>
 */
public final class TraceId {

    /** MDC 键名，与 application.yml 日志 pattern 中的 %X{traceId:-} 占位符对应。 */
    public static final String MDC_KEY = "traceId";

    /** 私有构造：工具类禁止实例化。 */
    private TraceId() {
    }

    /** 取当前线程的 traceId；没有时返回空串（而不是 null，调用方免判空）。 */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "" : value;
    }

    /** 生成并记录新的 traceId（UUID 去横线，32 字符），返回值供响应头回写。 */
    public static String start() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    /** 清理当前线程的 traceId（防线程池串号，由 TraceIdFilter 的 finally 调用）。 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
