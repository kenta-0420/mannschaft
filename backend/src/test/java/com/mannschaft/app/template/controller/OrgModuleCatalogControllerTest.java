package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.template.dto.OrgModuleCatalogResponse;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationModuleController} のカタログ取得エンドポイント単体テスト。
 * 認可（MEMBER 以上）・slug 解決・404 を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationModuleController カタログ取得 単体テスト")
class OrgModuleCatalogControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;
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

    @Test
    @DisplayName("MEMBER がカタログを取得 – slug 解決＋認可後 200")
    void getOrganizationModuleCatalog_member_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        willDoNothing().given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        OrgModuleCatalogResponse body = OrgModuleCatalogResponse.builder()
                .planLimit(10).enabledCount(0L).hasPaidPlan(false).modules(List.of()).build();
        given(moduleService.getOrganizationModuleCatalog(ORG_ID)).willReturn(body);

        ResponseEntity<ApiResponse<OrgModuleCatalogResponse>> resp =
                controller.getOrganizationModuleCatalog(ORG_SLUG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(organizationService).resolveOrgId(ORG_SLUG);
        verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        verify(moduleService).getOrganizationModuleCatalog(ORG_ID);
    }

    @Test
    @DisplayName("AC-9: 非メンバーは checkMembership が COMMON_002 を投げる")
    void getOrganizationModuleCatalog_nonMember_403() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> controller.getOrganizationModuleCatalog(ORG_SLUG))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("AC-8: 存在しない slug は resolveOrgId が ORG_001 例外")
    void getOrganizationModuleCatalog_notFoundSlug_throws() {
        given(organizationService.resolveOrgId("unknown-slug"))
                .willThrow(new BusinessException(OrgErrorCode.ORG_001));

        assertThatThrownBy(() -> controller.getOrganizationModuleCatalog("unknown-slug"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrgErrorCode.ORG_001));
    }
}
