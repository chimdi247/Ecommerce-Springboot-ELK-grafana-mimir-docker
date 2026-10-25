package com.phegondev.Phegon.Eccormerce.ratelimit;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitInterceptorTest {

    private RateLimiter limiter;
    private AppProperties properties;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        limiter = mock(RateLimiter.class);
        properties = new AppProperties();
        properties.getRateLimit().setGetAllProductsLimit(5);
        properties.getRateLimit().setWindowSeconds(60);
        interceptor = new RateLimitInterceptor(limiter, properties, JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void allowsRequestsWithinTheLimitAndAddsHeaders() throws Exception {
        when(limiter.tryAcquire(anyString(), eq(5), eq(60))).thenReturn(new RateLimiter.Result(true, 2, 5, 40));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new MockHttpServletRequest("GET", "/product/get-all"), response, new Object());

        assertTrue(proceed);
        assertEquals("5", response.getHeader("X-RateLimit-Limit"));
        assertEquals("3", response.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void rejectsWith429RetryAfterAndJsonBodyWhenOverTheLimit() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(false, 6, 5, 42));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new MockHttpServletRequest("GET", "/product/get-all"), response, new Object());

        assertFalse(proceed);
        assertEquals(429, response.getStatus());
        assertEquals("42", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("Too many requests"));
    }

    @Test
    void limitIsPerClientUsingTheFirstForwardedAddress() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(true, 1, 5, 60));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/product/get-all");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.2");
        request.setRemoteAddr("172.18.0.5");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(limiter).tryAcquire(eq("ratelimit:get-all-products:203.0.113.7"), anyInt(), anyInt());
    }

    @Test
    void forwardedHeaderIsIgnoredWhenNotTrusted() throws Exception {
        properties.getRateLimit().setTrustForwardedHeaders(false);
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(true, 1, 5, 60));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/product/get-all");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.setRemoteAddr("172.18.0.5");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(limiter).tryAcquire(eq("ratelimit:get-all-products:172.18.0.5"), anyInt(), anyInt());
    }

    @Test
    void disabledLimiterLetsEverythingThrough() throws Exception {
        properties.getRateLimit().setEnabled(false);

        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(limiter);
    }
}
