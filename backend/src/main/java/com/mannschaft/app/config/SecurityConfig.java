package com.mannschaft.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * セキュリティ設定（初期版）。
 * Phase 1 で JWT 認証を実装する際に本格的なフィルターチェーンに置き換える。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Swagger UI・OpenAPI ドキュメント
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
                ).permitAll()
                // ヘルスチェック
                .requestMatchers("/actuator/health").permitAll()
                // 開発中は全エンドポイントを許可（Phase 1 で JWT 実装時に制限）
                .anyRequest().permitAll()
            );
        return http.build();
    }
}
