package com.mannschaft.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 設定。
 *
 * <p>開発環境では localhost:3000（Nuxt）と localhost:8080 を許可。
 * 本番環境では環境変数 {@code MANNSCHAFT_ALLOWED_ORIGINS} からカンマ区切りで取得する。</p>
 */
@Configuration
public class CorsConfig {

    /** 許可するオリジン。未設定時は開発用デフォルト値を使用。 */
    @Value("${mannschaft.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // オリジン設定
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        config.setAllowedOrigins(origins);

        // 許可メソッド
        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // 全ヘッダーを許可
        config.setAllowedHeaders(List.of("*"));

        // Cookie・Authorization ヘッダーの送信を許可
        config.setAllowCredentials(true);

        // プリフライトキャッシュ: 1時間
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
