package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.SupporterNameDisclosurePatchRequest;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.repository.OrganizationNameDisclosureChangeLogRepository;
import com.mannschaft.app.publicview.repository.TeamNameDisclosureChangeLogRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SupporterNameDisclosureService} の per-scope 認可（認可根治 Phase 3-a / 生穴封鎖）単体テスト。
 *
 * <p>本サービスはかつて Service 層に認可がなく「認証済みなら誰でも他団体の投稿者識別モードを切替・
 * 履歴閲覧できる」生穴であった。本テストは Service 層明示認可（SYSTEM_ADMIN 短絡 or
 * 当該スコープ ADMIN/DEPUTY_ADMIN）が効くことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupporterNameDisclosureService 認可（生穴封鎖）単体テスト")
class SupporterNameDisclosureServiceAuthzTest {

    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private TeamNameDisclosureChangeLogRepository teamChangeLogRepository;
    @Mock
    private OrganizationNameDisclosureChangeLogRepository orgChangeLogRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SupporterNameDisclosureService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long OPERATOR_ID = 9L;

    private SupporterNameDisclosurePatchRequest patch() {
        return new SupporterNameDisclosurePatchRequest(NameDisclosureMode.REAL_NAME, true);
    }

    @Nested
    @DisplayName("チーム切替・履歴")
    class Team {

        @Test
        @DisplayName("非権限者の patch は COMMON_002（confirmed チェック・チーム取得より前に弾く）")
        void patch_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> service.patchTeamDisclosure(SCOPE_ID, OPERATOR_ID, patch()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(teamRepository, never()).findById(any());
        }

        @Test
        @DisplayName("非権限者の history は COMMON_002")
        void history_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> service.getTeamChangeHistory(SCOPE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(teamChangeLogRepository, never()).findByTeamIdOrderByChangedAtDesc(any());
        }
    }

    @Nested
    @DisplayName("組織切替・履歴")
    class Organization {

        @Test
        @DisplayName("非権限者の patch は COMMON_002")
        void patch_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.patchOrganizationDisclosure(SCOPE_ID, OPERATOR_ID, patch()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(organizationRepository, never()).findById(any());
        }

        @Test
        @DisplayName("非権限者の history は COMMON_002")
        void history_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.getOrganizationChangeHistory(SCOPE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(orgChangeLogRepository, never()).findByOrganizationIdOrderByChangedAtDesc(any());
        }
    }
}
