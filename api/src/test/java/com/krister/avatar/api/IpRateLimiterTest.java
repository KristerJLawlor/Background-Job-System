package com.krister.avatar.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IpRateLimiterTest {

    IpRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new IpRateLimiter();
        ReflectionTestUtils.setField(limiter, "requestsPerMinute", 2);
    }

    @Test
    void withinLimit_requestsAllowed() {
        assertThat(limiter.tryConsume(request("1.2.3.4"))).isTrue();
        assertThat(limiter.tryConsume(request("1.2.3.4"))).isTrue();
    }

    @Test
    void overLimit_requestBlocked() {
        limiter.tryConsume(request("1.2.3.4"));
        limiter.tryConsume(request("1.2.3.4"));
        assertThat(limiter.tryConsume(request("1.2.3.4"))).isFalse();
    }

    @Test
    void differentIps_haveIndependentBuckets() {
        limiter.tryConsume(request("1.1.1.1"));
        limiter.tryConsume(request("1.1.1.1")); // exhausts 1.1.1.1
        assertThat(limiter.tryConsume(request("2.2.2.2"))).isTrue();
    }

    @Test
    void xForwardedFor_usedInsteadOfRemoteAddr() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 10.10.10.10");
        when(req.getRemoteAddr()).thenReturn("172.16.0.1"); // load balancer IP

        // 9.9.9.9 is a fresh bucket — request should be allowed
        assertThat(limiter.tryConsume(req)).isTrue();
    }

    @Test
    void xForwardedFor_firstIpTakenFromCommaList() {
        HttpServletRequest req1 = mock(HttpServletRequest.class);
        when(req1.getHeader("X-Forwarded-For")).thenReturn("5.5.5.5, 99.99.99.99");
        when(req1.getRemoteAddr()).thenReturn("172.16.0.1");

        HttpServletRequest req2 = mock(HttpServletRequest.class);
        when(req2.getHeader("X-Forwarded-For")).thenReturn("5.5.5.5"); // same first IP
        when(req2.getRemoteAddr()).thenReturn("172.16.0.1");

        limiter.tryConsume(req1);
        limiter.tryConsume(req2); // exhausts the 5.5.5.5 bucket
        assertThat(limiter.tryConsume(req1)).isFalse();
    }

    private HttpServletRequest request(String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn(ip);
        return req;
    }
}
