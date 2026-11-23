package com.wzblog.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 链路追踪过滤器：为每个请求分配/继承 traceId 并写入 MDC 与响应头（教学详解见 monorepo 根 docs/学习文档/04）。
 *
 * <p>【教学：Servlet Filter 的实战样板】这是"进入应用的第一道门"，做了三件事：</p>
 * <ol>
 *   <li><b>继承外部 traceId 或生成新的</b>：上游（前端/网关）带了 X-Trace-Id 就延续
 *       （跨服务串联），否则 {@link TraceId#start()} 生成；</li>
 *   <li><b>写 MDC + 回写响应头</b>：此后该请求线程的所有日志自动带 traceId，
 *       响应头也返回给前端（用户报障时有据可查）；</li>
 *   <li><b>finally 清理</b>：Tomcat 线程池会复用线程，不清理会把上个请求的 traceId
 *       串到下个请求的日志里（张冠李戴的日志比没有日志更可怕）。</li>
 * </ol>
 *
 * <p>【注解细节】</p>
 * <ul>
 *   <li>{@code @Order(HIGHEST_PRECEDENCE)}：数字越小越先执行 —— 必须排在所有过滤器
 *       （包括安全过滤器）之前，这样后续环节的日志就都已带上 traceId；</li>
 *   <li>{@code OncePerRequestFilter}：Servlet 的 forward/include 派发会导致同一请求
 *       多次穿过过滤器，这个基类保证"一次请求只执行一次"；</li>
 *   <li>{@code SAFE_TRACE_ID} 正则白名单校验外部传入的 traceId：<b>任何进入日志的
 *       外部输入都要消毒</b> —— 恶意注入 \r\n 可以伪造日志行（日志注入攻击），
 *       限定 [A-Za-z0-9-]{8,64} 后既安全又格式统一。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 外部 traceId 的白名单格式：8~64 个字母数字连字符（防日志注入，见类注释）。 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        // 合法则延续上游 traceId（跨服务链路串联），非法/缺失则新生成
        String traceId = incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()
                ? incoming
                : TraceId.start();
        MDC.put(TraceId.MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：线程池复用线程，残留 MDC 会污染下一个请求的日志
            MDC.remove(TraceId.MDC_KEY);
        }
    }
}
