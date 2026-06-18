package com.krister.avatar.api;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// WebMvcConfigurer is a Spring extension point for customising the MVC framework.
// By implementing it here we can register interceptors, CORS rules, resource handlers, etc.
// without touching the auto-configured defaults Spring sets up for us.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Value("${api.key}")
    private String apiKey;

    @PostConstruct
    void warnIfDefault() {
        if ("changeme".equals(apiKey)) {
            log.warn("API key is set to the default 'changeme' — set API_KEY before exposing to the internet");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Restrict auth enforcement to /api/** only — static frontend files (/) and the
        // actuator health endpoint (/actuator/health) are intentionally left open.
        registry.addInterceptor(new ApiKeyInterceptor(apiKey))
                .addPathPatterns("/api/**");
    }
}
