package com.mannschaft.app.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.9 保護者同意ゲート: {@link ParentalConsentGateFilter} の遮断ロジックを Docker 非依存で検証する。
 *
 * <p>フィルターは SecurityContext の details Map の {@code ppc} クレームのみを見て発火するため、
 * 認証状態を SecurityContextHolder へ直接セットして純粋にフィルター単体を駆動する。
 * 受け入れ条件 AC-1〜AC-13, AC-18 を担保する。</p>
 */
@DisplayName("保護者同意ゲートフィルター (F01.9 AUTH_070)")
class ParentalConsentGateFilterTest {

    private final ParentalConsentGateFilter filter = new ParentalConsentGateFilter();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- テスト補助 ----

    /** 保護者同意待ち（ppc==true）ユーザーとして認証をセットする。 */
    private void authenticateAsPendingParentalConsent() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("100", null, List.of());
        auth.setDetails(Map.of("jti", "test-jti", "ppc", true));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** ACTIVE（ppc==false）ユーザーとして認証をセットする。 */
    private void authenticateAsActive() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "100", null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        auth.setDetails(Map.of("jti", "test-jti", "ppc", false));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** ppc クレームを持たない（承認完了後に再発行された）トークン相当の認証。 */
    private void authenticateWithoutPpcClaim() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "100", null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        auth.setDetails(Map.of("jti", "test-jti")); // ppc 不在
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private MockHttpServletResponse invoke(String method, String servletPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
        request.setServletPath(servletPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private boolean chainProceeded(MockFilterChain chain) {
        return chain.getRequest() != null;
    }

    // ---- 遮断ケース（ppc==true・許可リスト外）----

    @Test
    @DisplayName("AC-1: 保護者同意待ちユーザーの GET /api/v1/teams は 403 AUTH_070")
    void ac1_pending_teams_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("GET", "/api/v1/teams");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("AUTH_070");
    }

    @Test
    @DisplayName("AC-9: 保護者同意待ちユーザーの POST /api/v1/schedules は 403 AUTH_070")
    void ac9_pending_schedules_post_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("POST", "/api/v1/schedules");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("AUTH_070");
    }

    @Test
    @DisplayName("AC-10: 遮断応答は 401 ではなく 403（無限リフレッシュ回帰防止）")
    void ac10_block_is_403_not_401() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("GET", "/api/v1/teams");
        assertThat(res.getStatus()).isNotEqualTo(401);
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("AC-13: 403 ボディは {error:{code:AUTH_070, message:非空, fieldErrors:[]}} 形式")
    void ac13_block_body_schema() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("GET", "/api/v1/teams");
        String body = res.getContentAsString();
        assertThat(body).contains("\"error\"");
        assertThat(body).contains("\"code\":\"AUTH_070\"");
        assertThat(body).contains("\"fieldErrors\":[]");
        // message が非空であること
        assertThat(body).containsPattern("\"message\":\"[^\"]+\"");
        assertThat(res.getContentType()).contains("application/json");
    }

    @Test
    @DisplayName("未成年本人の退会 DELETE /api/v1/users/me は遮断される（GET のみ許可）")
    void pending_delete_users_me_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("DELETE", "/api/v1/users/me");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("AUTH_070");
    }

    @Test
    @DisplayName("未成年本人のプロフィール編集 PATCH /api/v1/users/me は遮断される")
    void pending_patch_users_me_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("PATCH", "/api/v1/users/me");
        assertThat(res.getStatus()).isEqualTo(403);
    }

    // ---- 許可ケース（ppc==true・許可リスト内）----

    @Test
    @DisplayName("AC-2: POST /api/v1/parental-consent/invitations は許可リスト通過（403 にならない）")
    void ac2_invitations_post_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("POST", "/api/v1/parental-consent/invitations");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("AC-3: GET /api/v1/parental-consent/invitations は許可")
    void ac3_invitations_get_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("GET", "/api/v1/parental-consent/invitations");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("AC-4: DELETE /api/v1/parental-consent/invitations/{linkId} はゲート通過")
    void ac4_invitations_delete_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("DELETE",
                "/api/v1/parental-consent/invitations/abc-123");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("AC-5: GET /api/v1/parental-consent/parents は許可")
    void ac5_parents_get_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("GET", "/api/v1/parental-consent/parents");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("AC-6: DELETE /api/v1/parental-consent/parents/{linkId} はゲート通過")
    void ac6_parents_delete_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("DELETE",
                "/api/v1/parental-consent/parents/abc-123");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("AC-7: GET /api/v1/users/me は許可（本人状態確認）")
    void ac7_users_me_get_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("GET", "/api/v1/users/me");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("AC-8: POST /api/v1/auth/logout は許可")
    void ac8_logout_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("POST", "/api/v1/auth/logout");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("認証ライフサイクル（POST /api/v1/auth/refresh）は許可（proactive refresh 対応）")
    void auth_refresh_allowed() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("POST", "/api/v1/auth/refresh");
        assertThat(res.getStatus()).isNotEqualTo(403);
    }

    // ---- /api/v1/auth/** の過剰許可是正: 同意前の未成年に不要な認証系は遮断する ----

    @Test
    @DisplayName("過剰許可是正: POST /api/v1/auth/2fa/setup は 403 AUTH_070（一括許可撤回）")
    void auth_2fa_setup_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("POST", "/api/v1/auth/2fa/setup");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("AUTH_070");
    }

    @Test
    @DisplayName("過剰許可是正: POST /api/v1/auth/oauth/link/confirm は 403 AUTH_070")
    void auth_oauth_link_confirm_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("POST", "/api/v1/auth/oauth/link/confirm");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("AUTH_070");
    }

    @Test
    @DisplayName("過剰許可是正: DELETE /api/v1/auth/sessions は 403 AUTH_070（セッション管理は遮断）")
    void auth_sessions_delete_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("DELETE", "/api/v1/auth/sessions");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("AUTH_070");
    }

    @Test
    @DisplayName("メソッド厳密一致: GET /api/v1/auth/logout（非POST）は遮断される")
    void auth_logout_non_post_is_blocked() throws Exception {
        authenticateAsPendingParentalConsent();
        MockHttpServletResponse res = invoke("GET", "/api/v1/auth/logout");
        assertThat(res.getStatus()).isEqualTo(403);
    }

    // ---- ゲート無発火（ppc != true）----

    @Nested
    @DisplayName("ゲート無発火")
    class NoGate {

        @Test
        @DisplayName("AC-11: ACTIVE（ppc==false）ユーザーの GET /api/v1/teams はゲート無発火で通過")
        void ac11_active_teams_pass_through() throws Exception {
            authenticateAsActive();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/teams");
            request.setServletPath("/api/v1/teams");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isNotEqualTo(403);
            assertThat(chainProceeded(chain)).isTrue();
        }

        @Test
        @DisplayName("AC-12: 承認完了後の再発行トークン（ppc 不在）で GET /api/v1/teams は通過")
        void ac12_no_ppc_claim_pass_through() throws Exception {
            authenticateWithoutPpcClaim();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/teams");
            request.setServletPath("/api/v1/teams");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isNotEqualTo(403);
            assertThat(chainProceeded(chain)).isTrue();
        }

        @Test
        @DisplayName("AC-18: 未認証（トークン無し）は許可リスト外でもゲート無発火で通過（401 は後段が返す）")
        void ac18_unauthenticated_pass_through() throws Exception {
            // SecurityContext は空（clearContext 済み）
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/teams");
            request.setServletPath("/api/v1/teams");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isNotEqualTo(403);
            assertThat(chainProceeded(chain)).isTrue();
        }

        @Test
        @DisplayName("匿名認証（anonymousUser）もゲート無発火で通過")
        void anonymous_pass_through() throws Exception {
            AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                    "key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            SecurityContextHolder.getContext().setAuthentication(anon);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/teams");
            request.setServletPath("/api/v1/teams");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isNotEqualTo(403);
            assertThat(chainProceeded(chain)).isTrue();
        }
    }
}
