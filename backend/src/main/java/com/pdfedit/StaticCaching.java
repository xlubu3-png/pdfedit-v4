package com.pdfedit;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the browser keep the screen's files instead of asking for them again: above all the HWP editor's
 * engine (some 12 MB) and fonts, which would otherwise be read on every opening of a Hangul document.
 *
 * <p>Built files carry a hash of their content in their name, so a new version of the app has new names and
 * they can be kept for a year. The bundled fonts keep their names, so they are kept for a month. The pages
 * themselves (index.html) are not covered: an update of the app shows at once.
 */
@Configuration
public class StaticCaching implements WebMvcConfigurer {

    static final CacheControl HASHED = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    static final CacheControl FONTS = CacheControl.maxAge(Duration.ofDays(30)).cachePublic();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(HASHED);
        registry.addResourceHandler("/hwp-studio/assets/**")
                .addResourceLocations("classpath:/static/hwp-studio/assets/")
                .setCacheControl(HASHED);
        registry.addResourceHandler("/hwp-studio/fonts/**")
                .addResourceLocations("classpath:/static/hwp-studio/fonts/")
                .setCacheControl(FONTS);
    }
}
