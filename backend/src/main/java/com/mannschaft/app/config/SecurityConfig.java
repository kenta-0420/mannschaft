package com.mannschaft.app.config;

import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
 * ProxyInputContextFilter は JwtAuthenticationFilter の直後に実行される。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ProxyInputContextFilter proxyInputContextFilter;
    private final PublicApiRateLimitFilter publicApiRateLimitFilter;
    private final AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter;

    /**
     * ProxyInputContextFilter の @Component によるサーブレットフィルター自動登録を無効化。
     * Spring Security フィルターチェーン経由（addFilterAfter）のみで動作させる。
     */
    @Bean
    public FilterRegistrationBean<ProxyInputContextFilter> proxyInputContextFilterRegistration() {
        FilterRegistrationBean<ProxyInputContextFilter> registration =
                new FilterRegistrationBean<>(proxyInputContextFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * F19.1 Phase 1: {@link PublicApiRateLimitFilter} の @Component による
     * サーブレットフィルター自動登録を無効化。
     * Spring Security フィルターチェーン経由（addFilterBefore）のみで動作させる。
     *
     * <p>クラス遷移履歴:</p>
     * <ul>
     *   <li>F15.4 Phase 1: {@code OrganizationTeamSearchRateLimitFilter}</li>
     *   <li>F15.4 Phase 5-α: {@code PublicTeamApiRateLimitFilter}（リネーム + 店舗詳細追加）</li>
     *   <li>F19.1 Phase 1: {@link PublicApiRateLimitFilter}（リネーム + organizations/posts/events 拡張）</li>
     * </ul>
     */
    @Bean
    public FilterRegistrationBean<PublicApiRateLimitFilter>
            publicApiRateLimitFilterRegistration() {
        FilterRegistrationBean<PublicApiRateLimitFilter> registration =
                new FilterRegistrationBean<>(publicApiRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * F09.17 Phase 11-b: AdPublicEndpointRateLimitFilter の @Component 由来の
     * サーブレットフィルター自動登録を無効化。
     * Spring Security フィルターチェーン経由（addFilterBefore）のみで動作させる。
     */
    @Bean
    public FilterRegistrationBean<AdPublicEndpointRateLimitFilter>
            adPublicEndpointRateLimitFilterRegistration() {
        FilterRegistrationBean<AdPublicEndpointRateLimitFilter> registration =
                new FilterRegistrationBean<>(adPublicEndpointRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

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
                // ヘルスチェック（匿名公開）
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                // F10.5 Phase 10-α: それ以外の Actuator エンドポイントは SYSTEM_ADMIN 限定
                // info / metrics / prometheus / caches / threaddump / loggers が対象
                // JwtAuthenticationFilter が "ROLE_SYSTEM_ADMIN" として authority を付与するため hasRole を使用
                .requestMatchers(EndpointRequest.toAnyEndpoint().excluding(HealthEndpoint.class))
                    .hasRole("SYSTEM_ADMIN")
                // 認証不要エンドポイント（auth 系）
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/password-reset/**",
                    "/api/v1/auth/email-verification/**",
                    "/api/v1/auth/oauth/**"
                ).permitAll()
                // F11.3 UI i18n: 対応言語一覧（認証不要）
                .requestMatchers("/api/i18n/**").permitAll()
                // F12.5 フロントエンドエラー追跡（認証不要）
                .requestMatchers(HttpMethod.POST, "/api/v1/error-reports").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/active-incidents").permitAll()
                // F10.6 Phase 10-γ-③-a: SSR エラー受信（認証不要。コントローラーが内部トークンで検証）
                .requestMatchers(HttpMethod.POST, "/api/internal/ssr-logs").permitAll()
                // F04.8 連絡先招待プレビュー（認証不要）
                .requestMatchers(HttpMethod.GET, "/api/v1/contact-invite/*").permitAll()
                // F15.4 組織内チーム（店舗）検索（認証不要・レート制限あり）
                .requestMatchers(HttpMethod.GET, "/api/v1/organizations/*/teams/search").permitAll()
                // F19.1 Phase 3 SEO: sitemap.xml / robots.txt（認証不要）
                // 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2 / §9.3
                .requestMatchers(HttpMethod.GET, "/sitemap.xml", "/robots.txt").permitAll()
                .requestMatchers(HttpMethod.GET, "/sitemap-*.xml").permitAll()
                // F19.1 Phase 1 公開ページ API（認証不要・レート制限あり）。
                // F15.4 Phase 5-α `/api/v1/public/teams/*` も本 F19.1 規約に統合・置換した。
                // 設計書 §7.4: パターンは `*`（1 階層厳格）で限定。`/**`（再帰）は使わない。
                // IDOR 防止と整合（F15.4 Phase 5 §4.2 / F19.1 §17.8 案 B 統合）。
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/posts").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/posts/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/events").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/posts").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/posts/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/events").permitAll()
                // F19.1 Phase 4 公開チーム・組織検索 API（認証不要・レート制限あり）
                // 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.x Phase 4
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/search").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/search").permitAll()
                // F09.17 Phase 11-b 広告 unsubscribe / 開封ピクセル（認証不要・IP レート制限あり）
                .requestMatchers(HttpMethod.GET, "/api/v1/ads/unsubscribe").permitAll()
                // F09.17 残課題 4 公開 unsubscribe SPA POST（認証不要）
                .requestMatchers(HttpMethod.POST, "/api/v1/ads/unsubscribe").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/ads/pixels/open").permitAll()
                // F01.9 保護者同意 API（保護者がメールリンクから直接アクセスする承認・否認）
                .requestMatchers(
                    "/api/v1/parental-consent/approve",
                    "/api/v1/parental-consent/reject"
                ).permitAll()
                // F01.9 年齢区分設定管理（SYSTEM_ADMIN 限定）
                .requestMatchers("/api/v1/admin/age-group-settings/**").hasRole("SYSTEM_ADMIN")
                // Phase E: GDPR パージ状況管理 API（SYSTEM_ADMIN 限定）
                // TODO(F09.18 Phase 18-d): /api/v1/system-admin/email-outbox/** に SYSTEM_ADMIN
                //   ロール限定の包括認可ルールを追加すること。現在は `.anyRequest().permitAll()`
                //   が末尾でフォールバックしており Controller 側の @PreAuthorize に依存している。
                //   本番移行時 (.anyRequest().authenticated() 化) 前に
                //   `.requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")` 系の
                //   明示ルールを追加して二重ガードとすること。設計書 §6.2 / §8 参照。
                .requestMatchers("/api/v1/system-admin/gdpr/**").hasRole("SYSTEM_ADMIN")
                // F19.1 Phase 2: Admin 向け投稿者識別モード切替 API（ADMIN / SYSTEM_ADMIN 限定）
                // 現状は Controller 側の @PreAuthorize に委ねているが、
                // 本番移行時に明示的なルールを追加すること（上記 TODO と同様）。
                // 開発中は全エンドポイントを許可（本番移行時に .authenticated() に変更）
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(publicApiRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adPublicEndpointRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(proxyInputContextFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
