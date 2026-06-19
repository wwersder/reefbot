package com.reefbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Static resource configuration.
 *
 * Mini App files (/mini/**) are served with Cache-Control: no-store so that
 * Telegram WebView (and any other browser) always fetches the latest version.
 * All other static resources keep the default Spring Boot caching behaviour.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/mini/**")
                .addResourceLocations("classpath:/static/mini/")
                .setCacheControl(CacheControl.noStore());
    }
}
