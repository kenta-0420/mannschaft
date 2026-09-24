package com.mannschaft.app.billing.api;

import com.mannschaft.app.advertising.operational.MethodSecurityTestConfig;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
import com.mannschaft.app.billing.api.dto.PagedContractResponse;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.1: 課金 API の <b>実 Spring Security（メソッドセキュリティ）経由</b>の認可検証（試練）。
 *
 * <p>{@code BillingAuthorizationAnnotationTest} は {@code @PreAuthorize} 文字列の静的 substring 照合のみで、
 * SpEL のタイポ（誤 bean / メソッド / 引数）を検知できない。本テストは {@link MethodSecurityTestConfig} を
 * {@code @Import} してメソッドセキュリティを点火し、<b>SpEL が実際に評価され認可が拒否/許可する</b>ことを検証する
 * （金型: {@code AdvertiserAdminControllerPreAuthorizeTest}）。{@code @accessGuard} は {@code @MockitoBean} で差し、
 * false→403 / true→到達 の両経路を通す（bean 参照・メソッド・引数が実行時に解決できることが自動で固定される）。</p>
 *
 * <p>{@code @PreAuthorize} 拒否は {@code AuthorizationDeniedException}（{@code AccessDeniedException} のサブクラス）
 * → {@code GlobalExceptionHandler} で 403 に変換される。{@code addFilters=false} で URL ルール層を外し、
 * <b>メソッド注釈単体</b>で守られることを確認する（多層防御の内側レイヤ）。</p>
 */
@DisplayName("F20.1 課金 API 実 Security 認可検証（試練）")
@WebMvcTest({BillingContractController.class, BillingEntitlementSummaryController.class,
        SystemAdminBillingController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(MethodSecurityTestConfig.class)
class BillingApiRuntimeAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- 対象 Controller の依存 Service（@MockitoBean で差す） ----
    @MockitoBean
    private BillingContractApplicationService contractApplicationService;
    @MockitoBean
    private BillingEntitlementQueryService entitlementQueryService;
    @MockitoBean
    private SystemAdminBillingService systemAdminBillingService;

    // ---- SpEL の @billingAccessGuard 参照を実行時に解決させる（false/true をスタブして経路を通す） ----
    //   Bean 名を "billingAccessGuard" に固定しないと SpEL が EL1058E で解決失敗し 500 になる。
    //   WebMvcTest スライスは本 Guard を走査しないため、mock を同名で登録する。
    @MockitoBean(name = "billingAccessGuard")
    private BillingAccessGuard billingAccessGuard;

    // 権利サマリ閲覧は従来どおり汎用scope member guardを使う。
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

    private String createBody() {
        return "{\"contractKind\":\"PLAN\",\"planKey\":\"FULL\"}";
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 契約作成: isScopeAdmin が実際に効く（非ADMIN→403 / ADMIN→到達）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("契約作成 TEAM: BillingAccessGuard=false は 403（SpEL 実評価）")
    void createForTeam_nonAdmin_403() throws Exception {
        authenticate("100", "ROLE_ADMIN"); // 一般スコープロール（SYSTEM_ADMIN ではない）
        given(billingAccessGuard.canManage(any(), eq(EntitlementScopeKind.TEAM), eq(123L))).willReturn(false);

        mockMvc.perform(post("/api/v1/teams/{teamId}/billing/contracts", 123L)
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("契約作成 TEAM: BillingAccessGuard=true はメソッドに到達し 201")
    void createForTeam_admin_reaches_201() throws Exception {
        authenticate("100", "ROLE_ADMIN");
        given(billingAccessGuard.canManage(any(), eq(EntitlementScopeKind.TEAM), eq(123L))).willReturn(true);
        given(contractApplicationService.create(eq(EntitlementScopeKind.TEAM), eq(123L), eq(100L), any(), eq("idem-2")))
                .willReturn(ContractResponse.builder()
                        .contractId(UUID.randomUUID().toString()).scopeKind("TEAM").scopeId(123L)
                        .contractKind("PLAN").planKey("FULL").status("ACTIVE")
                        .contractedAt(LocalDateTime.now()).grantedFeatureKeys(List.of("ads.hide")).build());

        mockMvc.perform(post("/api/v1/teams/{teamId}/billing/contracts", 123L)
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("契約作成 TEAM: 明示付与された DEPUTY は BillingAccessGuard=true で 201")
    void createForTeam_explicitlyGrantedDeputy_reaches_201() throws Exception {
        authenticate("100", "ROLE_DEPUTY_ADMIN");
        given(billingAccessGuard.canManage(any(), eq(EntitlementScopeKind.TEAM), eq(123L))).willReturn(true);
        given(contractApplicationService.create(eq(EntitlementScopeKind.TEAM), eq(123L), eq(100L), any(), eq("idem-deputy")))
                .willReturn(ContractResponse.builder()
                        .contractId(UUID.randomUUID().toString()).scopeKind("TEAM").scopeId(123L)
                        .contractKind("PLAN").planKey("FULL").status("ACTIVE")
                        .contractedAt(LocalDateTime.now()).grantedFeatureKeys(List.of("ads.hide")).build());

        mockMvc.perform(post("/api/v1/teams/{teamId}/billing/contracts", 123L)
                        .header("Idempotency-Key", "idem-deputy")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("契約作成 TEAM: SYSTEM_ADMIN でも BillingAccessGuard=false なら 403")
    void createForTeam_systemAdminWithoutScopeAccess_403() throws Exception {
        authenticate("1", "ROLE_SYSTEM_ADMIN");
        given(billingAccessGuard.canManage(any(), eq(EntitlementScopeKind.TEAM), eq(123L))).willReturn(false);

        mockMvc.perform(post("/api/v1/teams/{teamId}/billing/contracts", 123L)
                        .header("Idempotency-Key", "idem-system-admin")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("契約作成 ORG: BillingAccessGuard=false, 'ORGANIZATION' は 403（SpEL 引数まで実評価）")
    void createForOrg_nonAdmin_403() throws Exception {
        authenticate("100", "ROLE_ADMIN");
        given(billingAccessGuard.canManage(any(), eq(EntitlementScopeKind.ORG), eq(55L))).willReturn(false);

        mockMvc.perform(post("/api/v1/organizations/{orgId}/billing/contracts", 55L)
                        .header("Idempotency-Key", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isForbidden());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 権利サマリ: isScopeMember が実際に効く（非メンバー→403 / メンバー→到達）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("権利サマリ TEAM: 非メンバー（isScopeMember=false）は 403（SpEL 実評価）")
    void summaryTeam_nonMember_403() throws Exception {
        authenticate("100", "ROLE_ADMIN");
        given(accessGuard.isScopeMember(any(), eq(123L), eq("TEAM"))).willReturn(false);

        mockMvc.perform(get("/api/v1/teams/{teamId}/entitlements", 123L))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("権利サマリ TEAM: メンバー（isScopeMember=true）はメソッドに到達し 200")
    void summaryTeam_member_reaches_200() throws Exception {
        authenticate("100", "ROLE_ADMIN");
        given(accessGuard.isScopeMember(any(), eq(123L), eq("TEAM"))).willReturn(true);
        given(entitlementQueryService.getSummary(EntitlementScopeKind.TEAM, 123L))
                .willReturn(EntitlementSummaryResponse.builder()
                        .scopeKind("TEAM").scopeId(123L).activePlan(null)
                        .activeAddons(List.of()).entitledFeatures(List.of()).build());

        mockMvc.perform(get("/api/v1/teams/{teamId}/entitlements", 123L))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("権利サマリ ORG: 非メンバー（isScopeMember=false, 'ORGANIZATION'）は 403")
    void summaryOrg_nonMember_403() throws Exception {
        authenticate("100", "ROLE_ADMIN");
        given(accessGuard.isScopeMember(any(), eq(55L), eq("ORGANIZATION"))).willReturn(false);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/entitlements", 55L))
                .andExpect(status().isForbidden());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. シスアド: hasRole('SYSTEM_ADMIN') が実際に効く（非SYSTEM_ADMIN→403 / SYSTEM_ADMIN→到達）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("シスアド プラン一覧: 非 SYSTEM_ADMIN は 403（hasRole 実評価・AC-17）")
    void sysadminListPlans_nonSystemAdmin_403() throws Exception {
        authenticate("100", "ROLE_ADMIN"); // 一般 ADMIN は SYSTEM_ADMIN ではない
        mockMvc.perform(get("/api/v1/system-admin/billing/plans"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("シスアド 契約検索: 非 SYSTEM_ADMIN は 403（hasRole 実評価・AC-17）")
    void sysadminSearchContracts_nonSystemAdmin_403() throws Exception {
        authenticate("100", "ROLE_ADMIN");
        mockMvc.perform(get("/api/v1/system-admin/billing/contracts"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("シスアド プラン一覧: SYSTEM_ADMIN はメソッドに到達し 200")
    void sysadminListPlans_systemAdmin_reaches_200() throws Exception {
        authenticate("1", "ROLE_SYSTEM_ADMIN");
        given(systemAdminBillingService.listPlans()).willReturn(List.of());
        mockMvc.perform(get("/api/v1/system-admin/billing/plans"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("シスアド 手動付与: 非 SYSTEM_ADMIN は 403（POST も hasRole 実評価）")
    void sysadminGrant_nonSystemAdmin_403() throws Exception {
        authenticate("100", "ROLE_ADMIN");
        mockMvc.perform(post("/api/v1/system-admin/billing/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeKind\":\"TEAM\",\"scopeId\":123,\"contractKind\":\"PLAN\",\"planKey\":\"FULL\"}"))
                .andExpect(status().isForbidden());
    }
}
