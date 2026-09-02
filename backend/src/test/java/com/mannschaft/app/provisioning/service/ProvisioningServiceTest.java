package com.mannschaft.app.provisioning.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.provisioning.dto.ProvisioningOrganizationCreateRequest;
import com.mannschaft.app.provisioning.dto.ProvisioningTeamCreateRequest;
import com.mannschaft.app.provisioning.repository.ProvisioningInvitationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

/**
 * 柱②-2 試練（AC1〜AC3・AC13・AC14）: 販促プロビジョニング SYSTEM_ADMIN 側サービスの UT。
 *
 * <p>正本: .claude/campaigns/2026-09-01-org-governance.md 柱②。骨格は
 * {@link UnsupportedOperationException} を投げるため、以下は red が正しい。
 * 実装は後続 PR（出陣）で行う。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningServiceTest {

    @Mock
    private ProvisioningInvitationRepository invitationRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ProvisioningService provisioningService;

    private static final Long SYSTEM_ADMIN_ID = 1L;
    private static final Long NON_ADMIN_ID = 2L;

    @Test
    @DisplayName("AC2: Service層単体でも非SYSTEM_ADMINは403（COMMON_002）で拒否する")
    void createOrganizationRejectsNonSystemAdminAtServiceLayer() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkSystemAdmin(NON_ADMIN_ID);
        ProvisioningOrganizationCreateRequest request =
                new ProvisioningOrganizationCreateRequest("新規組織", "admin-to-be@example.com");

        assertThatThrownBy(() -> provisioningService.createOrganization(NON_ADMIN_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("AC2: チーム作成でもService層単体で非SYSTEM_ADMINは403")
    void createTeamRejectsNonSystemAdminAtServiceLayer() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkSystemAdmin(NON_ADMIN_ID);
        ProvisioningTeamCreateRequest request =
                new ProvisioningTeamCreateRequest("新規チーム", "admin-to-be@example.com");

        assertThatThrownBy(() -> provisioningService.createTeam(NON_ADMIN_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("AC13: inviteEmailが不正な組織作成は400（COMMON_001）")
    void createOrganizationRejectsInvalidInviteEmail() {
        lenient().when(accessControlService.isSystemAdmin(SYSTEM_ADMIN_ID)).thenReturn(true);
        ProvisioningOrganizationCreateRequest request =
                new ProvisioningOrganizationCreateRequest("新規組織", "not-an-email");

        assertThatThrownBy(() -> provisioningService.createOrganization(SYSTEM_ADMIN_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_001);
    }

    @Test
    @DisplayName("AC14: 招待0件の一覧は空配列で200相当（例外を投げない）")
    void listReturnsEmptyArrayWhenNoInvitations() {
        lenient().when(accessControlService.isSystemAdmin(SYSTEM_ADMIN_ID)).thenReturn(true);
        lenient().when(invitationRepository.findAll()).thenReturn(List.of());

        List<?> result = provisioningService.list(SYSTEM_ADMIN_ID);

        assertThat(result).isEmpty();
    }
}
