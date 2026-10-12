package com.easyshopping.common.web;

import com.easyshopping.common.api.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * traceId 透传拦截器：优先取网关透传的 X-Trace-Id，没有则生成；请求结束后清理 ThreadLocal。
 */
public class TraceIdInterceptor implements HandlerInterceptor {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        TraceIds.set(traceId == null || traceId.isBlank() ? TraceIds.get() : traceId);
        response.setHeader(HEADER_TRACE_ID, TraceIds.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TraceIds.clear();
    }
}
