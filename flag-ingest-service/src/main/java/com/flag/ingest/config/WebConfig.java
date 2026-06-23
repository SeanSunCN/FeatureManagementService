package com.flag.ingest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration — allows the Web SDK (CDN origin) to POST metrics.
 * In production, restrict allowedOrigins to the actual CDN domain.
 */
@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/v1/ingest/**")
                        .allowedOrigins("*")
                        .allowedMethods("POST", "GET", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}