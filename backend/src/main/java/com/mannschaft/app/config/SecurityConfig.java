package com.mannschaft.app.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * セキュリティ設定。JwtAuthenticationFilter を UsernamePasswordAuthenticationFilter の前に挿入し、
 * Bearer トークンによるステートレス認証を実現する。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

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
                // 認証不要エンドポイント
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/password-reset/**",
                    "/api/v1/auth/email-verification/**",
                    "/api/v1/auth/oauth/**",
                    "/api/v1/public/**"
                ).permitAll()
                // F11.3 UI i18n: 対応言語一覧（認証不要）
                .requestMatchers("/api/i18n/**").permitAll()
                // F12.5 フロントエンドエラー追跡（認証不要）
                .requestMatchers(HttpMethod.POST, "/api/v1/error-reports").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/active-incidents").permitAll()
                // F04.8 連絡先招待プレビュー（認証不要）
                .requestMatchers(HttpMethod.GET, "/api/v1/contact-invite/*").permitAll()
                // 開発中は全エンドポイントを許可（本番移行時に .authenticated() に変更）
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
