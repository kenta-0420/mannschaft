package com.mannschaft.app.billing.beta.api;

import com.mannschaft.app.advertising.operational.MethodSecurityTestConfig;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.BetaGrantQueryService;
import com.mannschaft.app.billing.beta.BetaGrantService;
import com.mannschaft.app.billing.beta.BetaPerkCandidateService;
import com.mannschaft.app.billing.beta.BetaPerkCriteriaService;
import com.mannschaft.app.billing.beta.dto.BetaGrantPageResponse;
import com.mannschaft.app.billing.beta.dto.MyBetaPerksResponse;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.3 ベータ特典 API の<b>実 Spring Security（メソッドセキュリティ）経由</b>の認可検証（試練）。
 *
 * <p>F20.1 {@code BillingApiRuntimeAuthorizationTest} を金型とする。{@link MethodSecurityTestConfig} を
 * {@code @Import} して {@code @PreAuthorize} を点火し、SpEL（{@code hasRole('SYSTEM_ADMIN')} /
 * {@code @accessGuard.isScopeMember}）が<b>実際に評価され拒否/許可する</b>ことを検証する
 * （memory {@code feedback_new_authz_endpoint_archunit_and_addfilters_it}: addFilters=false でも
 * {@code @PreAuthorize} は効くが、認証コンテキストが無いと 500 化するため必ず認証を張る）。</p>
 */
@DisplayName("F20.3 ベータ特典 API 実 Security 認可検証（試練）")
@WebMvcTest({SystemAdminBetaPerkController.class, BetaPerkController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(MethodSecurityTestConfig.class)
class BetaPerkApiRuntimeAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- 対象 Controller の依存 Service ----
    @MockitoBean
    private BetaGrantService betaGrantService;
    @MockitoBean
    private BetaGrantQueryService betaGrantQueryService;
    @MockitoBean
    private BetaPerkCriteriaService betaPerkCriteriaService;
    @MockitoBean
    private BetaPerkCandidateService betaPerkCandidateService;
    @MockitoBean
    private TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    // ---- SpEL の @accessGuard 参照を実行時に解決させる ----
    @MockitoBean(name = "accessGuard")
    private AccessGuard accessGuard;

    // ---- @WebMvcTest コンテキスト用: フィルタ・SpEL ガードの依存解決 mock ----
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String userId, String... roles) {
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toList();
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null, auths);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ═════════════════════════════════════════════════════════════════════
    // シスアド EP: hasRole('SYSTEM_ADMIN') 実評価（AC-A4）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A4: 一般 USER は付与一覧（GET）で 403")
    void listGrants_nonSystemAdmin_403() throws Exception {
        authenticate("100", "ROLE_USER");
        mockMvc.perform(get("/api/v1/system-admin/beta-perks/grants"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-A4: 一般 USER は手動付与（POST）で 403")
    void createGrant_nonSystemAdmin_403() throws Exception {
        authenticate("100", "ROLE_USER");
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantKind\":\"TEAM_ORG\",\"betaPhase\":2,\"scopeKind\":\"TEAM\",\"scopeId\":123}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-A4: SYSTEM_ADMIN は付与一覧に到達し 200")
    void listGrants_systemAdmin_reaches_200() throws Exception {
        authenticate("1", "ROLE_SYSTEM_ADMIN");
        given(betaGrantQueryService.searchGrants(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(BetaGrantPageResponse.builder()
                        .content(List.of()).page(0).size(20).totalElements(0).build());
        mockMvc.perform(get("/api/v1/system-admin/beta-perks/grants"))
                .andExpect(status().isOk());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 照会 EP: /me は認証のみ・/teams はメンバー（AC-A5 認可・§1.2）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A5: /me は認証済み一般 USER で到達し 200（本人固定・scopeId を受けない）")
    void myBetaPerks_authenticated_reaches_200() throws Exception {
        authenticate("1", "ROLE_USER");
        given(betaGrantQueryService.getMyBetaPerks(1L))
                .willReturn(MyBetaPerksResponse.builder().grants(List.of()).eligibility(null).build());
        mockMvc.perform(get("/api/v1/me/beta-perks"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("§1.2: チーム照会は非メンバー（isScopeMember=false）で 403")
    void teamBetaPerks_nonMember_403() throws Exception {
        authenticate("100", "ROLE_USER");
        given(accessGuard.isScopeMember(any(), eq(123L), eq("TEAM"))).willReturn(false);
        mockMvc.perform(get("/api/v1/teams/{teamId}/beta-perks", 123L))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("§1.2: チーム照会はメンバー（isScopeMember=true）で到達し 200")
    void teamBetaPerks_member_reaches_200() throws Exception {
        authenticate("100", "ROLE_USER");
        given(accessGuard.isScopeMember(any(), eq(123L), eq("TEAM"))).willReturn(true);
        given(betaGrantQueryService.getScopeBetaPerks(EntitlementScopeKind.TEAM, 123L))
                .willReturn(List.of());
        mockMvc.perform(get("/api/v1/teams/{teamId}/beta-perks", 123L))
                .andExpect(status().isOk());
    }
}
