package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.service.AdminPublicSettingsService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.organization.OrgErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 公開閲覧APIに依存しない管理設定GETのHTTP・スコープ認可契約。 */
class AdminPublicSettingsReadTest {
    private static final long USER_ID = 9L;
    private static final long SCOPE_ID = 1217L;
    private final TeamRepository teams = mock(TeamRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final TeamService teamService = mock(TeamService.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("9", null, List.of()));
        var service = new AdminPublicSettingsService(teams, organizations, access, teamService, organizationService);
        mvc = MockMvcBuilders.standaloneSetup(new AdminPublicSettingsController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private String scopeType(String scope) { return scope.equals("teams") ? "TEAM" : "ORGANIZATION"; }

    private void existingPrivateScope(String scope) {
        if (scope.equals("teams")) {
            var team = mock(TeamEntity.class);
            when(team.getId()).thenReturn(SCOPE_ID);
            when(team.isTimelinePostsPublic()).thenReturn(true);
            when(team.getPublicEventsEnabled()).thenReturn(false);
            when(teamService.resolveTeamId("private-scope")).thenReturn(SCOPE_ID);
            when(teams.findById(SCOPE_ID)).thenReturn(Optional.of(team));
        } else {
            var org = mock(OrganizationEntity.class);
            when(org.getId()).thenReturn(SCOPE_ID);
            when(org.isTimelinePostsPublic()).thenReturn(true);
            when(org.isPublicEventsEnabled()).thenReturn(false);
            when(organizationService.resolveOrgId("private-scope")).thenReturn(SCOPE_ID);
            when(organizations.findById(SCOPE_ID)).thenReturn(Optional.of(org));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("非公開スコープでも管理権限を確認して数値IDと現在の2設定を返す")
    void privateScopeSettings(String scope) throws Exception {
        existingPrivateScope(scope);
        mvc.perform(get("/api/v1/admin/{scope}/private-scope/public-settings", scope))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.numericId").value(SCOPE_ID))
                .andExpect(jsonPath("$.data.timelinePostsPublic").value(true))
                .andExpect(jsonPath("$.data.publicEventsEnabled").value(false));
        verify(access).checkAdminOrAbove(USER_ID, SCOPE_ID, scopeType(scope));
    }

    @ParameterizedTest @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("非管理者・他テナントの管理者は取得値を返さず403")
    void scopeDenied(String scope) throws Exception {
        existingPrivateScope(scope);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(access).checkAdminOrAbove(USER_ID, SCOPE_ID, scopeType(scope));
        mvc.perform(get("/api/v1/admin/{scope}/private-scope/public-settings", scope))
                .andExpect(status().isForbidden());
        verify(teams, never()).findById(anyLong());
        verify(organizations, never()).findById(anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("SYSTEM_ADMINは既存の短絡契約で私有スコープの設定を取得できる")
    void systemAdmin(String scope) throws Exception {
        existingPrivateScope(scope);
        when(access.isSystemAdmin(USER_ID)).thenReturn(true);
        mvc.perform(get("/api/v1/admin/{scope}/private-scope/public-settings", scope))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.numericId").value(SCOPE_ID));
        verify(access, never()).checkAdminOrAbove(anyLong(), anyLong(), anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("存在しない・削除済み・正準ACTIVE解決外のslugは404")
    void missingScope(String scope) throws Exception {
        if (scope.equals("teams")) {
            when(teamService.resolveTeamId("missing"))
                    .thenThrow(new BusinessException(TeamErrorCode.TEAM_001));
        } else {
            when(organizationService.resolveOrgId("missing"))
                    .thenThrow(new BusinessException(OrgErrorCode.ORG_001));
        }
        mvc.perform(get("/api/v1/admin/{scope}/missing/public-settings", scope))
                .andExpect(status().isNotFound());
        verify(teams, never()).findById(anyLong());
        verify(organizations, never()).findById(anyLong());
        verify(access, never()).checkAdminOrAbove(anyLong(), anyLong(), anyString());
    }
    @ParameterizedTest @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("正準ID解決後に対象がなくなった場合も404")
    void missingAfterResolution(String scope) throws Exception {
        if (scope.equals("teams")) {
            when(teamService.resolveTeamId("private-scope")).thenReturn(SCOPE_ID);
        } else {
            when(organizationService.resolveOrgId("private-scope")).thenReturn(SCOPE_ID);
        }
        mvc.perform(get("/api/v1/admin/{scope}/private-scope/public-settings", scope))
                .andExpect(status().isNotFound());
        verify(access).checkAdminOrAbove(USER_ID, SCOPE_ID, scopeType(scope));
    }

    @ParameterizedTest @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("正準ID解決後に論理削除された対象は404")
    void deletedAfterResolution(String scope) throws Exception {
        existingPrivateScope(scope);
        if (scope.equals("teams")) {
            when(teams.findById(SCOPE_ID).orElseThrow().getDeletedAt())
                    .thenReturn(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        } else {
            when(organizations.findById(SCOPE_ID).orElseThrow().getDeletedAt())
                    .thenReturn(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        }
        mvc.perform(get("/api/v1/admin/{scope}/private-scope/public-settings", scope))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("実GETの認可式は正準slugを数値IDへ解決しSYS・管理者だけを許可する")
    void canonicalSlugAuthorizationExpression(String scope) throws Exception {
        var teamService = new SlugResolverFixture();
        var organizationService = new SlugResolverFixture();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("accessGuard", new AccessGuard(access));
        beans.registerSingleton("teamService", teamService);
        beans.registerSingleton("organizationService", organizationService);
        var method = AdminPublicSettingsController.class.getMethod(
                scope.equals("teams") ? "getTeamPublicSettings" : "getOrganizationPublicSettings", String.class);
        assertEquals("slug", method.getParameters()[0].getName());
        var root = new SecurityExpressionRoot(SecurityContextHolder.getContext().getAuthentication()) {};
        var context = new StandardEvaluationContext(root);
        context.setBeanResolver(new BeanFactoryResolver(beans));
        context.setVariable(method.getParameters()[0].getName(), "private-scope");
        var expression = new SpelExpressionParser().parseExpression(method.getAnnotation(PreAuthorize.class).value());
        when(access.isAdminOrAbove(USER_ID, SCOPE_ID, scopeType(scope))).thenReturn(false);
        assertEquals(false, expression.getValue(context, Boolean.class), "MEMBER・他tenant管理者は拒否");
        when(access.isAdminOrAbove(USER_ID, SCOPE_ID, scopeType(scope))).thenReturn(true);
        assertEquals(true, expression.getValue(context, Boolean.class), "ADMIN/DEPUTYは既存guardで許可");
        when(access.isAdminOrAbove(USER_ID, SCOPE_ID, scopeType(scope))).thenReturn(false);
        when(access.isSystemAdmin(USER_ID)).thenReturn(true);
        assertEquals(true, expression.getValue(context, Boolean.class), "SYSは既存guardの短絡で許可");
    }

    @ParameterizedTest
    @ValueSource(strings = {"teams", "organizations"})
    @DisplayName("実method-security経由でも未知slugの正準解決は404を維持する")
    void unknownSlugThroughMethodSecurity(String scope) throws Exception {
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("accessGuard", new AccessGuard(access));
            var resolver = new SlugResolverFixture();
            context.getBeanFactory().registerSingleton("teamService", resolver);
            context.getBeanFactory().registerSingleton("organizationService", resolver);
            context.refresh();
            var handler = new org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler();
            handler.setApplicationContext(context);
            var manager = new org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager();
            manager.setExpressionHandler(handler);
            var controller = new AdminPublicSettingsController(new AdminPublicSettingsService(teams, organizations, access, teamService, organizationService));
            var factory = new org.springframework.aop.framework.ProxyFactory(controller);
            factory.setProxyTargetClass(true);
            factory.addAdvisor(org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize(manager));
            var securedMvc = MockMvcBuilders.standaloneSetup(factory.getProxy())
                    .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
            securedMvc.perform(get("/api/v1/admin/{scope}/missing/public-settings", scope))
                    .andExpect(status().isNotFound());
            assertEquals(1, resolver.calls, "resolverはService本体の前に実認可式から呼ばれる");
        }
    }

    /** 認可式の Bean 名・slug 引数束縛を検証する局所 fixture。正準実装自体は既存 Service を使用する。 */
    public static class SlugResolverFixture {
        int calls;
        public Long resolveTeamId(String slug) {
            calls++;
            if (slug.equals("missing")) throw new BusinessException(com.mannschaft.app.publicview.error.PublicViewErrorCode.PUBLIC_001);
            assertEquals("private-scope", slug);
            return SCOPE_ID;
        }
        public Long resolveOrgId(String slug) {
            calls++;
            if (slug.equals("missing")) throw new BusinessException(com.mannschaft.app.publicview.error.PublicViewErrorCode.PUBLIC_001);
            assertEquals("private-scope", slug);
            return SCOPE_ID;
        }
    }

}
