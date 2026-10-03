package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.dto.AdminActionRequiredResponse;
import com.mannschaft.app.dashboard.service.AdminActionRequiredFacade;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F10.1.1 / P1: {@link AdminActionRequiredController} の契約テスト（@WebMvcTest 流儀・Facade はモック）。
 *
 * <p>本アプリは {@code @EnableMethodSecurity} 未有効のため、コントローラを直接呼び出す方式で
 * GET 200 形状（team=3ドメイン / org=payment のみ）・認可 403 伝播・IDOR 403・preview_size 範囲外 400
 * を検証する（設計書 03 §8）。{@code SecurityUtils.getCurrentUserId()} は authentication.getName() を
 * userId として読むため、テストでは "1" を設定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminActionRequiredController 契約テスト")
class AdminActionRequiredControllerTest {

    @Mock
    private AdminActionRequiredFacade adminActionRequiredFacade;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private AdminActionRequiredController controller;

    private static final Long USER_ID = 1L;
    private static final String TEAM_SLUG = "dev-team";
    private static final Long TEAM_ID = 10L;
    private static final String ORG_SLUG = "dev-org";
    private static final Long ORG_ID = 20L;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private AdminActionRequiredResponse teamResponse() {
        return AdminActionRequiredResponse.builder()
                .scopeType("TEAM").scopeId(TEAM_ID).totalPending(6)
                .domains(List.of(
                        section("RESERVATION", 2), section("SHIFT_REQUEST", 3), section("MATCHING", 1)))
                .build();
    }

    private AdminActionRequiredResponse orgResponse() {
        return AdminActionRequiredResponse.builder()
                .scopeType("ORGANIZATION").scopeId(ORG_ID).totalPending(4)
                .domains(List.of(section("PAYMENT", 4)))
                .build();
    }

    private AdminActionRequiredResponse.DomainSection section(String domain, long count) {
        return AdminActionRequiredResponse.DomainSection.builder()
                .domain(domain).pendingCount(count).degraded(false)
                .listRoute("/x").items(List.of()).build();
    }

    @Test
    @DisplayName("GET team/{slug}/admin-action-required: 200・予約/シフト/マッチングの3ドメイン")
    void teamReturns200WithThreeDomains() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminActionRequiredFacade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3))
                .willReturn(teamResponse());

        ResponseEntity<ApiResponse<AdminActionRequiredResponse>> res =
                controller.getTeamAdminActionRequired(TEAM_SLUG, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData().domains())
                .extracting(AdminActionRequiredResponse.DomainSection::domain)
                .containsExactlyInAnyOrder("RESERVATION", "SHIFT_REQUEST", "MATCHING");
        verify(adminActionRequiredFacade).getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3);
    }

    @Test
    @DisplayName("GET organization/{slug}/admin-action-required: 200・payment のみ")
    void orgReturns200WithPaymentOnly() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(adminActionRequiredFacade.getAdminActionRequired(USER_ID, "ORGANIZATION", ORG_ID, ORG_SLUG, 3))
                .willReturn(orgResponse());

        ResponseEntity<ApiResponse<AdminActionRequiredResponse>> res =
                controller.getOrgAdminActionRequired(ORG_SLUG, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getData().domains())
                .extracting(AdminActionRequiredResponse.DomainSection::domain)
                .containsExactly("PAYMENT");
    }

    @Test
    @DisplayName("非 ADMIN（MEMBER）→ Facade の checkAdminOrAbove が COMMON_002 を投げ 403 伝播")
    void nonAdminPropagates403() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(adminActionRequiredFacade).getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3);

        assertThatThrownBy(() -> controller.getTeamAdminActionRequired(TEAM_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("他テナント slug（IDOR）→ checkAdminOrAbove が非所属判定で COMMON_002 → 403")
    void idorPropagates403() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(adminActionRequiredFacade).getAdminActionRequired(USER_ID, "ORGANIZATION", ORG_ID, ORG_SLUG, 3);

        assertThatThrownBy(() -> controller.getOrgAdminActionRequired(ORG_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("preview_size=0 → そのまま 0 を Facade に渡す（件数のみ）")
    void previewSizeZeroPassThrough() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(adminActionRequiredFacade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 0))
                .willReturn(teamResponse());

        controller.getTeamAdminActionRequired(TEAM_SLUG, 0);

        verify(adminActionRequiredFacade).getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 0);
    }

    @Test
    @DisplayName("preview_size=6（上限超過）→ COMMON_001（400）・Facade も slug 解決も呼ばれない")
    void previewSizeAboveMaxRejected() {
        assertThatThrownBy(() -> controller.getTeamAdminActionRequired(TEAM_SLUG, 6))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_001);

        verify(teamService, never()).resolveTeamId(eq(TEAM_SLUG));
        verifyNoInteractions(adminActionRequiredFacade);
    }

    @Test
    @DisplayName("preview_size=-1（負数）→ COMMON_001（400）")
    void previewSizeNegativeRejected() {
        assertThatThrownBy(() -> controller.getTeamAdminActionRequired(TEAM_SLUG, -1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_001);
        verifyNoInteractions(adminActionRequiredFacade);
    }
}
