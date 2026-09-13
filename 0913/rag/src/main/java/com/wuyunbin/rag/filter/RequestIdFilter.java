package com.wuyunbin.rag.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个 HTTP 请求生成一个短 ID 放进 MDC，日志格式里以 {@code [req=xxxxxxxx]} 呈现。
 *
 * <p>为什么需要：一次对话会跨 Controller → Advisor → Service 三个类，
 * 并发时日志会交错在一起，没有这个 ID 就无法判断哪几行属于同一次请求。</p>
 *
 * <p>同时把该 ID 回写到响应头 {@code X-Request-Id}，方便前端/调用方在报错时直接提供这个值。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 调用方自带则沿用，便于跨服务串联
        String requestId = request.getHeader(HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：线程池会复用线程，残留会让后续请求带上错误的 ID
            MDC.remove(MDC_KEY);
        }
    }
}
