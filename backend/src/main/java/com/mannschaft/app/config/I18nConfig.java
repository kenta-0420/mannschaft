package com.mannschaft.app.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 国際化（i18n）設定。
 * MessageSource を構成し、Bean Validation がユーザーの locale でメッセージを返せるようにする。
 */
@Configuration
public class I18nConfig {

    /**
     * MessageSource: messages_{locale}.properties と ValidationMessages_{locale}.properties を読み込む。
     * encoding は UTF-8 を明示（中国語・韓国語等の多バイト文字の文字化け防止）。
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages", "classpath:ValidationMessages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    /**
     * LocalValidatorFactoryBean に MessageSource を紐付ける。
     * これにより @NotBlank 等の Bean Validation エラーが LocaleContextHolder.getLocale() を参照する。
     * この Bean 定義がないと ValidationMessages_{locale}.properties は参照されない。
     */
    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.setValidationMessageSource(messageSource);
        return factory;
    }
}
