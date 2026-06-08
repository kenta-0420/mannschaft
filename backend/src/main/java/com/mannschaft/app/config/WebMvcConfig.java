package com.mannschaft.app.config;

import com.mannschaft.app.team.converter.TeamIdConverter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC カスタム設定。チームIDのUUID→Long変換を登録する。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TeamIdConverter teamIdConverter;

    public WebMvcConfig(@Lazy TeamIdConverter teamIdConverter) {
        this.teamIdConverter = teamIdConverter;
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(teamIdConverter);
    }
}
