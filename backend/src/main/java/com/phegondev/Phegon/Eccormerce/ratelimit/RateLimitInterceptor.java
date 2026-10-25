package com.phegondev.Phegon.Eccormerce.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import com.phegondev.Phegon.Eccormerce.dto.Response;
import com.phegondev.Phegon.Eccormerce.telemetry.BusinessMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static net.logstash.logback.argument.StructuredArguments.kv;

/** Per-client rate limit for GET /product/get-all (registered in {@code AppConfig}). */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    static final String ROUTE = "get-all-products";

    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        AppProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return true;
        }

        String client = clientIp(request, config.isTrustForwardedHeaders());
        RateLimiter.Result result = rateLimiter.tryAcquire(
                "ratelimit:" + ROUTE + ":" + client, config.getGetAllProductsLimit(), config.getWindowSeconds());

        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));

        if (result.allowed()) {
            return true;
        }

        BusinessMetrics.rateLimited(ROUTE);
        log.warn("Rate limit exceeded", kv("event", "rate_limited"), kv("route", ROUTE), kv("client", client),
                kv("limit", result.limit()), kv("retryAfterSeconds", result.retryAfterSeconds()));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Response body = Response.builder()
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .message("Too many requests. Try again in " + result.retryAfterSeconds() + " seconds.")
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }

    static String clientIp(HttpServletRequest request, boolean trustForwardedHeaders) {
        if (trustForwardedHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
