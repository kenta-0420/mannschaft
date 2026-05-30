package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.security.QuickMemoAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可基盤完全根治 Phase 2（点火 / docs/security/03_role_authority_model.md §8 Phase 3・§10）:
 * {@code @EnableMethodSecurity} 点火が <b>実機で効くこと</b>を検証する統合テスト。
 *
 * <p><b>狙い</b>: 既存の {@code @WebMvcTest} スライス（{@code AdminFaqControllerTest} 等）は
 * {@code @AutoConfigureMockMvc(addFilters = false)} かつ {@code SecurityConfig} を取り込まないため、
 * 点火後もメソッド層認可が <em>そのスライス内では発火しない</em>（注釈の宣言存在を Reflection で担保している）。
 * 本テストは逆に {@link SecurityConfig}（{@code @EnableMethodSecurity} 付与済み）を取り込み、
 * メソッド層認可が <b>実際に 403 を返す</b>ことを MockMvc で End-to-End に検証する。</p>
 *
 * <p><b>ロード戦略（Docker 不要）</b>: {@link SecurityConfigAuthorizationTest} と同方針で、
 * MannschaftApplication の {@code @ComponentScan} を回避し SecurityConfig と Spring Boot 自動構成のみを
 * 取り込む。Datasource / JPA / Flyway / Redis を除外する。SpEL ガード Bean
 * （{@link AccessGuard} / {@link QuickMemoAccessGuard}）は本物を供給し、判定の元になる
 * {@link AccessControlService} / {@link QuickMemoRepository} はモックして可否を確定させる。
 * 検証対象 EP は本テスト専用の極小コントローラ {@link IgnitionProbeController} で、
 * 本番と同形の {@code @PreAuthorize} を宣言する（実コントローラの重い依存を回避しつつ点火経路を検証）。</p>
 *
 * <p>検証する §10 マトリクス:
 * <ul>
 *   <li>SYSTEM_ADMIN 系 EP: SYSTEM_ADMIN で 2xx・一般で 403</li>
 *   <li>per-scope: 自団体 ADMIN で 2xx・他団体 ADMIN で 403・SYSTEM_ADMIN で 2xx（短絡）</li>
 *   <li>所有者ガード: 本人で 2xx・他人で 403</li>
 *   <li>未認証: 401（フィルタ層 deny-by-default）</li>
 * </ul></p>
 */
@SpringBootTest(
        classes = {
                MethodSecurityIgnitionTest.MinimalMethodSecurityTestConfig.class,
                MethodSecurityIgnitionTest.IgnitionProbeController.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("認可: @EnableMethodSecurity 点火 End-to-End (Phase 2)")
class MethodSecurityIgnitionTest {

    private static final Long OWN_TEAM_ID = 100L;
    private static final Long OTHER_TEAM_ID = 999L;
    private static final Long OWNED_MEMO_ID = 10L;
    private static final Long FOREIGN_MEMO_ID = 20L;
    private static final Long OPERATOR_USER_ID = 1L;

    /**
     * 点火の検証専用 minimal context。SecurityConfig（@EnableMethodSecurity 付与済み）を取り込み、
     * SpEL ガード Bean を本物で供給する。判定の元になるサービス/リポジトリのみモック化する。
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @Import(SecurityConfig.class)
    static class MinimalMethodSecurityTestConfig {

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(AuthTokenService.class));
        }

        @Bean
        ProxyInputContextFilter proxyInputContextFilter() {
            return new ProxyInputContextFilter(
                    mock(ProxyInputConsentRepository.class),
                    mock(ProxyInputContext.class),
                    mock(ObjectMapper.class));
        }

        @Bean
        @SuppressWarnings("unchecked")
        PublicApiRateLimitFilter publicApiRateLimitFilter() {
            return new PublicApiRateLimitFilter(
                    mock(org.springframework.beans.factory.ObjectProvider.class),
                    mock(org.springframework.beans.factory.ObjectProvider.class));
        }

        @Bean
        AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter() {
            return new AdPublicEndpointRateLimitFilter();
        }

        @Bean
        com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter scheduleDelegationRateLimitFilter() {
            return new com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter();
        }

        @Bean
        com.mannschaft.app.event.EventDelegationRateLimitFilter eventDelegationRateLimitFilter() {
            return new com.mannschaft.app.event.EventDelegationRateLimitFilter();
        }

        @Bean
        com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter dashboardScopeTabRateLimitFilter() {
            return new com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter();
        }

        /** SpEL ガード本体は本物を供給（点火経路を本番と同一にする）。 */
        @Bean
        AccessGuard accessGuard(AccessControlService accessControlService) {
            return new AccessGuard(accessControlService);
        }

        @Bean
        QuickMemoAccessGuard quickMemoAccessGuard(QuickMemoRepository quickMemoRepository) {
            return new QuickMemoAccessGuard(quickMemoRepository);
        }
    }

    /**
     * 点火検証専用コントローラ。本番と同形の {@code @PreAuthorize} を宣言する。
     * ハンドラ本体は 200 を返すだけで、認可が通ったか否かのみを判定する。
     */
    @RestController
    static class IgnitionProbeController {

        /** (A) SYSTEM_ADMIN グローバル権限。 */
        @PreAuthorize("hasRole('SYSTEM_ADMIN')")
        @GetMapping("/test-ignition/system-admin")
        ResponseEntity<String> systemAdminOnly() {
            return ResponseEntity.ok("ok");
        }

        /** (B) per-scope ADMIN（パス変数 teamId を参照）。 */
        @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
        @GetMapping("/test-ignition/teams/{teamId}/admin")
        ResponseEntity<String> teamScopeAdmin(@PathVariable Long teamId) {
            return ResponseEntity.ok("ok");
        }

        /** (F) 所有者ガード。 */
        @PreAuthorize("@quickMemoAccessGuard.canAccess(#memoId, authentication)")
        @GetMapping("/test-ignition/memos/{memoId}")
        ResponseEntity<String> ownerOnly(@PathVariable Long memoId) {
            return ResponseEntity.ok("ok");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessControlService accessControlService;

    @MockitoBean
    private QuickMemoRepository quickMemoRepository;

    @BeforeEach
    void setUpGuards() {
        // 既定: 全て不許可。各テストで許可ケースを上書きする。
        given(accessControlService.isSystemAdmin(OPERATOR_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(OPERATOR_USER_ID, OWN_TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.isAdminOrAbove(OPERATOR_USER_ID, OTHER_TEAM_ID, "TEAM")).willReturn(false);
        given(quickMemoRepository.findByIdAndUserId(OWNED_MEMO_ID, OPERATOR_USER_ID))
                .willReturn(Optional.of(mock(com.mannschaft.app.quickmemo.entity.QuickMemoEntity.class)));
        given(quickMemoRepository.findByIdAndUserId(FOREIGN_MEMO_ID, OPERATOR_USER_ID))
                .willReturn(Optional.empty());
    }

    // ─────────────────────────────────────────────────────────────────
    // (A) SYSTEM_ADMIN グローバル権限
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("(A) hasRole('SYSTEM_ADMIN')")
    class SystemAdminGuard {

        @Test
        @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
        @DisplayName("SYSTEM_ADMIN は 2xx（点火後 hasRole が機能）")
        void systemAdmin_allowed() throws Exception {
            mockMvc.perform(get("/test-ignition/system-admin"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "1", roles = "MEMBER")
        @DisplayName("一般ユーザーは 403（点火でメソッド層認可が発火）")
        void member_forbidden() throws Exception {
            mockMvc.perform(get("/test-ignition/system-admin"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("未認証は 401（フィルタ層 deny-by-default）")
        void anonymous_unauthorized() throws Exception {
            mockMvc.perform(get("/test-ignition/system-admin"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // (B) per-scope ADMIN（@accessGuard.isScopeAdmin）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("(B) @accessGuard.isScopeAdmin per-scope")
    class PerScopeAdminGuard {

        @Test
        @WithMockUser(username = "1", roles = "MEMBER")
        @DisplayName("自団体 ADMIN は 2xx")
        void ownTeamAdmin_allowed() throws Exception {
            mockMvc.perform(get("/test-ignition/teams/{teamId}/admin", OWN_TEAM_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "1", roles = "MEMBER")
        @DisplayName("他団体 ADMIN は 403（IDOR 防止・per-scope 判定）")
        void otherTeamAdmin_forbidden() throws Exception {
            mockMvc.perform(get("/test-ignition/teams/{teamId}/admin", OTHER_TEAM_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "1", roles = "MEMBER")
        @DisplayName("SYSTEM_ADMIN は他団体でも 2xx（ガード内部の短絡）")
        void systemAdmin_shortCircuit_allowed() throws Exception {
            given(accessControlService.isSystemAdmin(OPERATOR_USER_ID)).willReturn(true);
            mockMvc.perform(get("/test-ignition/teams/{teamId}/admin", OTHER_TEAM_ID))
                    .andExpect(status().isOk());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // (F) 所有者ガード（@quickMemoAccessGuard.canAccess）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("(F) @quickMemoAccessGuard.canAccess 所有者ガード")
    class OwnerGuard {

        @Test
        @WithMockUser(username = "1", roles = "MEMBER")
        @DisplayName("本人の所有メモは 2xx")
        void owner_allowed() throws Exception {
            mockMvc.perform(get("/test-ignition/memos/{memoId}", OWNED_MEMO_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "1", roles = "MEMBER")
        @DisplayName("他人の所有メモは 403")
        void nonOwner_forbidden() throws Exception {
            mockMvc.perform(get("/test-ignition/memos/{memoId}", FOREIGN_MEMO_ID))
                    .andExpect(status().isForbidden());
        }
    }
}
