package com.mannschaft.app.config;

import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter;
import com.mannschaft.app.event.EventDelegationRateLimitFilter;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * セキュリティ設定。JwtAuthenticationFilter を UsernamePasswordAuthenticationFilter の前に挿入し、
 * Bearer トークンによるステートレス認証を実現する。
 * ProxyInputContextFilter は JwtAuthenticationFilter の直後に実行される。
 *
 * <p>{@code @EnableMethodSecurity(prePostEnabled = true)} により {@code @PreAuthorize} /
 * {@code @PostAuthorize} が有効化される（認可基盤根治 Phase 3）。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AdminImpersonationFilter adminImpersonationFilter;
    private final ProxyInputContextFilter proxyInputContextFilter;
    private final PublicApiRateLimitFilter publicApiRateLimitFilter;
    private final AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter;
    private final ScheduleDelegationRateLimitFilter scheduleDelegationRateLimitFilter;
    private final EventDelegationRateLimitFilter eventDelegationRateLimitFilter;
    private final DashboardScopeTabRateLimitFilter dashboardScopeTabRateLimitFilter;

    /**
     * F10.1: AdminImpersonationFilter の @Component によるサーブレットフィルター自動登録を無効化。
     * Spring Security フィルターチェーン経由（addFilterAfter）のみで動作させ、
     * JWT 認証後の確定した SecurityContext を使って SYSTEM_ADMIN 判定を行う。
     */
    @Bean
    public FilterRegistrationBean<AdminImpersonationFilter> adminImpersonationFilterRegistration() {
        FilterRegistrationBean<AdminImpersonationFilter> registration =
                new FilterRegistrationBean<>(adminImpersonationFilter);
        registration.setEnabled(false);
        return registration;
    }

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

    /**
     * F03.10 第三陣: {@link ScheduleDelegationRateLimitFilter} の @Component による
     * サーブレットフィルター自動登録を無効化。
     * Spring Security フィルターチェーン経由（addFilterAfter）のみで動作させ、
     * JWT 認証後の確定した SecurityContext から userId を解決できるようにする。
     */
    @Bean
    public FilterRegistrationBean<ScheduleDelegationRateLimitFilter>
            scheduleDelegationRateLimitFilterRegistration() {
        FilterRegistrationBean<ScheduleDelegationRateLimitFilter> registration =
                new FilterRegistrationBean<>(scheduleDelegationRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * F03.10 第三陣: {@link EventDelegationRateLimitFilter} の @Component による
     * サーブレットフィルター自動登録を無効化。
     * Spring Security フィルターチェーン経由（addFilterAfter）のみで動作させる。
     */
    @Bean
    public FilterRegistrationBean<EventDelegationRateLimitFilter>
            eventDelegationRateLimitFilterRegistration() {
        FilterRegistrationBean<EventDelegationRateLimitFilter> registration =
                new FilterRegistrationBean<>(eventDelegationRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * F22.1: {@link DashboardScopeTabRateLimitFilter} の @Component による
     * サーブレットフィルター自動登録を無効化。
     * Spring Security フィルターチェーン経由（addFilterAfter）のみで動作させ、
     * JWT 認証後の確定した SecurityContext から userId を解決できるようにする。
     */
    @Bean
    public FilterRegistrationBean<DashboardScopeTabRateLimitFilter>
            dashboardScopeTabRateLimitFilterRegistration() {
        FilterRegistrationBean<DashboardScopeTabRateLimitFilter> registration =
                new FilterRegistrationBean<>(dashboardScopeTabRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // CORS プリフライトリクエストは認証不要
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
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
                // 認可基盤完全根治 Phase 1（docs/security/03_role_authority_model.md §3.2）:
                // 発行時に RoleClaimResolver が user_roles から SYSTEM_ADMIN を判定して roles に載せ、
                // JwtAuthenticationFilter がそれを "ROLE_SYSTEM_ADMIN" authority へ変換するため hasRole を使用
                .requestMatchers(EndpointRequest.toAnyEndpoint().excluding(HealthEndpoint.class))
                    .hasRole("SYSTEM_ADMIN")
                // 認証不要エンドポイント（auth 系）
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/password-reset/**",
                    "/api/v1/auth/email-verification/**",
                    "/api/v1/auth/oauth/**",
                    // 2FA ログインフロー（MFA セッショントークンで認証するため既存セッション不要）
                    "/api/v1/auth/2fa/validate",
                    "/api/v1/auth/2fa/recovery/request",
                    "/api/v1/auth/2fa/recovery/confirm",
                    // WebAuthn パスキーログイン（第一要素として未認証で呼ばれるため公開必須）
                    "/api/v1/auth/webauthn/login/begin",
                    "/api/v1/auth/webauthn/login/complete"
                ).permitAll()
                // F11.3 UI i18n: 対応言語一覧（認証不要）
                .requestMatchers("/api/i18n/**").permitAll()
                // F02.10 §391 郵便番号検証ポリシー（認証不要・register 画面が未ログインで参照）
                // フォーマット規則のみで機微情報なし。FE の単一真実源。
                .requestMatchers(HttpMethod.GET, "/api/v1/postal-code/policies").permitAll()
                // F12.5 フロントエンドエラー追跡（認証不要）
                .requestMatchers(HttpMethod.POST, "/api/v1/error-reports").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/active-incidents").permitAll()
                // CSP 違反レポート受信（ブラウザ自動送信のため認証不要）
                .requestMatchers(HttpMethod.POST, "/api/v1/security/csp-reports").permitAll()
                // F02.12 Phase 4: Google Calendar Webhook 受信（Google からの外部コールバック・認証不要）
                // トークン検証は GoogleCalendarWebhookService 内で行う（定数時間比較 + DB 照合）
                .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/google-calendar").permitAll()
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
                // F08.7.1 / 04 リーグ単位ファイル置き場: 大会・ディビジョンフォルダ一覧 GET は
                // 公開トグル ON のスペースで未ログインでも閲覧可（read-only）。非公開スコープは
                // Service 層の checkView が 403 を返す（多層防御）。POST（作成）は
                // .anyRequest().authenticated() ＋ Service 層 checkPost で認証＋認可必須。
                // 設計書: docs/features/F08.7.1_tournament_extensions/04_file_storage.md §3 / §5
                .requestMatchers(HttpMethod.GET, "/api/v1/tournaments/*/folders").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/tournaments/*/divisions/*/folders").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/posts").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/posts/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/events").permitAll()
                // F19.1 Phase 7: タイムライン投稿公開 API（認証不要・レート制限あり）
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/timeline-posts").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/posts").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/posts/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/events").permitAll()
                // F19.1 Phase 7: 組織タイムライン投稿公開 API（認証不要・レート制限あり）
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/timeline-posts").permitAll()
                // F08.7 項目① 公開大会参照 API（認証不要）。
                // PublicTournamentController（visibility=PUBLIC のみ verifyPublicAccess で 404 ガード）と
                // EmbedController（埋め込みウィジェット・未ログイン前提）の全 GET パスを permitAll する。
                // これを怠ると deny-by-default の .anyRequest().authenticated() が未認証を 401 で弾き、
                // PUBLIC 大会が未ログイン者に見えない（PUBLIC=誰でも閲覧の約束違反）+ viewer=null 名前解決経路が
                // production で dead code になる。
                // 設計書 §7.4 / F19.1 §17.8 案 B 統合の流儀どおり、IDOR 防止のため `*`（1 階層厳格）で限定。
                // `/**`（再帰）は使わず、POST/PATCH/DELETE（書込）は permitAll しない。
                // 非 PUBLIC 大会は Service 層 canView(TOURNAMENT, tId, null) が PUBLIC のみ true のため 404 で隠蔽。
                // 設計書: docs/features/F08.7_tournament_league.md（順位UI 項目①）
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/tournaments").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/tournaments/*").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/public/organizations/*/tournaments/*/divisions/*/standings").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/public/organizations/*/tournaments/*/divisions/*/matrix").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/public/organizations/*/tournaments/*/rankings/*").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/public/organizations/*/tournaments/*/bracket").permitAll()
                // F08.7 項目① 埋め込みウィジェット（EmbedController・未ログイン前提）。
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/embed/organizations/*/tournaments/*/standings/*").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/embed/organizations/*/tournaments/*/bracket").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/embed/organizations/*/tournaments/*/rankings/*").permitAll()
                // F21.1 §5.5 公開FAQ API（認証不要・レート制限あり）
                // 設計書: docs/features/F21.1_geo_optimization.md §5.5.6
                // IDOR 防止のため `*`（1 階層厳格）で限定。回答済み FAQ のみ返す。
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/*/faqs").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/*/faqs").permitAll()
                // F19.1 Phase 4 公開チーム・組織検索 API（認証不要・レート制限あり）
                // 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.x Phase 4
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/search").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/search").permitAll()
                // F01.2 §5.9.5 slug 解決（旧slug→新slug 301判定・認証不要・レート制限あり）
                // ブックマーク／被リンクされた旧URLの 301 リダイレクト判定に未ログインで到達可能にする。
                // 実データは返さず canonicalSlug のみ（スコープ漏洩防止）。設計書 §5.9.5
                .requestMatchers(HttpMethod.GET, "/api/v1/public/teams/slug-resolve").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/organizations/slug-resolve").permitAll()
                // F22.1 市（Market）公開閲覧 API（認証不要・レート制限あり・PII抑制）
                // 設計書: docs/features/F22.1_market/04_security.md §1.6
                // これを怠ると deny-by-default 反転時に市の公開検索/詳細が 401 で死ぬ。
                .requestMatchers(HttpMethod.GET, "/api/v1/public/market/listings").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/market/listings/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/market/regions").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/market/summary").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/market/categories").permitAll()
                // F19.1 Phase 6 公開ユーザープロフィール API（認証不要・レート制限あり）
                // 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6
                .requestMatchers(HttpMethod.GET, "/api/v1/public/users/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/public/users/*/posts").permitAll()
                // F19.1 Phase 6-B 公開投稿コメント一覧 GET（認証不要・レート制限あり）
                // POST（投稿）/ DELETE（削除）は認証必須（anyRequest().permitAll() のフォールバック + JwtFilter が制御）
                // 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B
                .requestMatchers(HttpMethod.GET, "/api/v1/public/blog-posts/*/comments").permitAll()
                // F09.7 クリック計測（認証不要・未ログインユーザーのクリックにも対応）
                // TODO: 将来 IP ベースのレート制限を AdPublicEndpointRateLimitFilter に追加して不正クリックを防止する
                .requestMatchers(HttpMethod.POST, "/api/v1/ads/*/click").permitAll()
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
                // F01.10 履歴書・職務経歴書（本人のみ完全非公開・全エンドポイント認証必須）
                .requestMatchers("/api/v1/resumes/**").authenticated()
                // Phase E: GDPR パージ状況管理 API（SYSTEM_ADMIN 限定）
                .requestMatchers("/api/v1/system-admin/gdpr/**").hasRole("SYSTEM_ADMIN")
                // 外部 webhook（署名検証で認証＝JWT 非依存。docs/security/01 §3.6）
                // JWT を持たない外部システムが叩くため deny-by-default 反転後も permitAll が必須。
                // 各 Controller が署名/トークンを検証する（Stripe-Signature /
                // X-Line-Signature + パスシークレット / パストークン DB 照合）。
                // ※ SES バウンス/苦情通知は F09.6 Phase 8a で SQS リスナー方式へ移行済み。
                //    HTTP 受け口（/api/v1/webhooks/ses）を廃止したため permitAll 行も撤去した
                //    （SQS は AWS 内部認証のため公開 HTTP エンドポイント不要・deny-by-default に戻す）。
                .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/line/webhook/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/incoming/*").permitAll()
                // WebSocket ハンドシェイク（SockJS）。STOMP CONNECT 時に
                // WebSocketAuthChannelInterceptor が JWT を検証するため、ハンドシェイク自体は
                // permitAll で許容する（docs/security/01 §5）。
                .requestMatchers("/ws/**").permitAll()
                // F19.1 Phase 2: Admin 向け投稿者識別モード切替 API（ADMIN / SYSTEM_ADMIN 限定）
                // は Controller 側の @PreAuthorize で制御。下記 system-admin 包括ルールと
                // .anyRequest().authenticated() で二重にガードされる。
                // system-admin 包括ルール（既存 gdpr ルールに加え二重ガード。docs/security/01 §4）。
                // SecurityConfig:173-178 の旧 TODO（email-outbox 等の permitAll フォールバック依存）を解消。
                .requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
                // deny-by-default: 上記許可リスト / ロール必須に該当しない全リクエストは認証必須。
                // 新規 Controller を追加した際に認可設定を忘れても無防備に公開されない
                // フェイルセーフ構造（docs/security/01 §1）。
                .anyRequest().authenticated()
            )
            // 未認証リクエストには 403 ではなく 401 を返す。
            // Spring Security のデフォルトでは匿名ユーザーが認証必須エンドポイントに
            // アクセスした場合 AccessDeniedException → 403 になる。
            // REST API ではアクセストークン期限切れ = 401 が正しいセマンティクスであり、
            // フロントエンドの refreshAccessToken() フローが 401 をトリガーとして
            // リフレッシュトークンによる再認証を行う設計のため明示的に 401 を設定する。
            //
            // accessDeniedHandler: 認証済みユーザーが権限不足のエンドポイントにアクセスした場合
            // 403 を返す。設定しないと Spring Security デフォルトの動作になり、
            // 一部ケースで 401 が返る（ExceptionTranslationFilter が 401 として処理する）ため
            // 明示的に COMMON_002（権限なし → 403 Forbidden）をレスポンスする。
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"error\":{\"code\":\"COMMON_002\","
                            + "\"message\":\"この操作を行う権限がありません\","
                            + "\"fieldErrors\":[]}}");
                }))
            // API JSON 応答向けのセキュリティヘッダー（docs/security/03 §3）。
            // - frameOptions(deny): クリックジャッキング防止（X-Frame-Options: DENY）
            // - contentTypeOptions: MIME スニッフィング防止（X-Content-Type-Options: nosniff）
            // - referrerPolicy: クロスオリジンへの Referer 漏洩を最小化
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(publicApiRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adPublicEndpointRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(proxyInputContextFilter, JwtAuthenticationFilter.class)
            // F10.1 管理者変身: JWT 認証後に動かし、SYSTEM_ADMIN 判定後に principal を置き換える
            .addFilterAfter(adminImpersonationFilter, ProxyInputContextFilter.class)
            // F03.10 代理指定レートリミット（§6・10req/分/ユーザー）。
            // JWT 認証後に動かし、確定した SecurityContext から userId を解決する。
            .addFilterAfter(scheduleDelegationRateLimitFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(eventDelegationRateLimitFilter, JwtAuthenticationFilter.class)
            // F22.1 scope-tabs 並べ替え連打防止（§5・30req/分/ユーザー）。JWT 認証後に動かす。
            .addFilterAfter(dashboardScopeTabRateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
