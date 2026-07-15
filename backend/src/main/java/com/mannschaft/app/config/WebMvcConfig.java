package com.mannschaft.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC カスタム設定。
 *
 * <p>team / organization のスラッグ（slug）→ 内部 BIGINT ID 変換を
 * {@link ScopeSlugIdConverter} 1 本で登録する。String→Long 変換器は 1 つしか選べないため、
 * team・organization を統合した本コンバータを用いる。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ScopeSlugIdConverter scopeSlugIdConverter;

    public WebMvcConfig(@Lazy ScopeSlugIdConverter scopeSlugIdConverter) {
        this.scopeSlugIdConverter = scopeSlugIdConverter;
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(scopeSlugIdConverter);
    }
}
