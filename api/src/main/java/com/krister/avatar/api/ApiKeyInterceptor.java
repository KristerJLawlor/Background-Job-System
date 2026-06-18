package com.krister.avatar.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

// HandlerInterceptor is Spring MVC's middleware mechanism. preHandle() runs before the
// controller method — returning false short-circuits the pipeline, so the controller
// is never called. This is intentionally NOT a @Component; it's instantiated manually
// in WebConfig so the API key value can be passed via constructor.
class ApiKeyInterceptor implements HandlerInterceptor {

    private final String expectedKey;

    ApiKeyInterceptor(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        String key = request.getHeader("X-Api-Key");
        if (!expectedKey.equals(key)) {
            // Write the JSON error body manually here because Spring's normal exception
            // handling doesn't run when preHandle returns false.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid API key\"}");
            return false;
        }
        return true;
    }
}
