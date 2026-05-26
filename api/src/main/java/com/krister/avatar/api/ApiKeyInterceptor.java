package com.krister.avatar.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

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
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid API key\"}");
            return false;
        }
        return true;
    }
}
