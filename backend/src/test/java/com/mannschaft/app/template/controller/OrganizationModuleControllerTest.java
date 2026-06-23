package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.template.dto.OrgModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.service.ModuleService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationModuleController} の単体テスト。
 * slug 解決対応（AC-1/AC-2/AC-4/AC-5）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationModuleController 単体テスト")
class OrganizationModuleControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long MODULE_ID = 100L;
    private static final String ORG_SLUG = "org-000001";

    @Mock private ModuleService moduleService;
    @Mock private AccessControlService accessControlService;
    @Mock private OrganizationService organizationService;

    @InjectMocks
    private OrganizationModuleController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------
    // AC-1: GET /organizations/{slug}/modules が slug で 200
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-1: getOrganizationModules – slug を渡すと resolveOrgId 経由で 200 を返す")
    void getOrganizationModules_slugResolves_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(moduleService.getOrganizationModules(ORG_ID)).willReturn(List.of());
        willDoNothing().given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");

        ResponseEntity<ApiResponse<List<OrgModuleResponse>>> resp = controller.getOrganizationModules(ORG_SLUG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(organizationService).resolveOrgId(ORG_SLUG);
        verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        verify(moduleService).getOrganizationModules(ORG_ID);
    }

    // -------------------------------------------------------
    // AC-2: PATCH /organizations/{slug}/modules/{moduleId}/toggle が slug で ADMIN 認可
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-2: toggleOrganizationModule – ADMIN ユーザーが slug でトグル成功 200")
    void toggleOrganizationModule_adminSlug_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        ToggleModuleRequest req = new ToggleModuleRequest(MODULE_ID, true);

        ResponseEntity<Void> resp = controller.toggleOrganizationModule(ORG_SLUG, MODULE_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(organizationService).resolveOrgId(ORG_SLUG);
        verify(accessControlService).isAdmin(USER_ID, ORG_ID, "ORGANIZATION");
        verify(moduleService).toggleOrganizationModule(ORG_ID, req, USER_ID);
    }

    @Test
    @DisplayName("AC-5: toggleOrganizationModule – ADMIN でないと COMMON_002 例外が投げられる")
    void toggleOrganizationModule_nonAdmin_throws() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
        ToggleModuleRequest req = new ToggleModuleRequest(MODULE_ID, true);

        assertThatThrownBy(() -> controller.toggleOrganizationModule(ORG_SLUG, MODULE_ID, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
    }

    // -------------------------------------------------------
    // AC-4: 存在しない slug は resolveOrgId が BusinessException を投げる
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-4: getOrganizationModules – 存在しない slug は resolveOrgId が ORG_001 例外")
    void getOrganizationModules_notFoundSlug_throws() {
        given(organizationService.resolveOrgId("unknown-slug"))
                .willThrow(new BusinessException(OrgErrorCode.ORG_001));

        assertThatThrownBy(() -> controller.getOrganizationModules("unknown-slug"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrgErrorCode.ORG_001));
    }
}
