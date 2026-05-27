package com.shortlink.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.UUID;

/**
 * Access log interceptor that adds MDC fields (requestId, userId) to every request.
 * Logs structured access info at request start and completion.
 */
@Component
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AccessLogInterceptor.class);
    private static final String START_TIME_ATTR = "startTimeMs";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String userId = request.getHeader("X-User-Id");
        if (userId == null) userId = "anonymous";

        MDC.put("requestId", requestId);
        MDC.put("userId", userId);
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());

        log.info("Incoming request method={} uri={} ip={}",
                request.getMethod(), request.getRequestURI(), getClientIp(request));
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        // no-op
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        long elapsed = startTime != null ? System.currentTimeMillis() - startTime : -1;
        log.info("Completed request status={} elapsed={}ms", response.getStatus(), elapsed);
        MDC.clear();
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // Take first IP if comma-separated
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
