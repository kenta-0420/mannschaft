package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.UpdatePublicSettingsRequest;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AdminPublicSettingsService} の per-scope 認可（認可根治 Phase 3-a / 生穴封鎖）単体テスト。
 *
 * <p>本サービスはかつて Service 層に認可がなく、{@code @EnableMethodSecurity} 未有効ゆえ
 * Controller の {@code @PreAuthorize} も no-op だったため「認証済みなら誰でも他団体の公開設定を変更可能」
 * な生穴であった。本テストは Service 層の明示認可（SYSTEM_ADMIN 短絡 or 当該スコープ ADMIN/DEPUTY_ADMIN）
 * が効くことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPublicSettingsService 認可（生穴封鎖）単体テスト")
class AdminPublicSettingsServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private AdminPublicSettingsService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long OPERATOR_ID = 9L;

    private UpdatePublicSettingsRequest req() {
        return new UpdatePublicSettingsRequest(true, true);
    }

    @Nested
    @DisplayName("チーム公開設定")
    class Team {

        @Test
        @DisplayName("非権限者は COMMON_002（チーム取得より前に弾く）")
        void 非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> service.updateTeamPublicSettings(SCOPE_ID, OPERATOR_ID, req()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(teamRepository, never()).findById(any());
            verify(teamRepository, never()).save(any());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は per-scope 判定なしで認可通過（短絡）")
        void SYSTEM_ADMIN_短絡() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(true);
            // teamRepository.findById は空 → 認可は通り、その後の存在確認で 404（PUBLIC_001）
            given(teamRepository.findById(SCOPE_ID)).willReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> service.updateTeamPublicSettings(SCOPE_ID, OPERATOR_ID, req()))
                    .isInstanceOf(BusinessException.class);

            // SYSTEM_ADMIN 短絡したので per-scope 判定は呼ばれない
            verify(accessControlService, never()).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "TEAM");
            // 認可は通過したので findById まで到達している
            verify(teamRepository).findById(SCOPE_ID);
        }
    }

    @Nested
    @DisplayName("組織公開設定")
    class Organization {

        @Test
        @DisplayName("非権限者は COMMON_002（組織取得より前に弾く）")
        void 非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.updateOrganizationPublicSettings(SCOPE_ID, OPERATOR_ID, req()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(organizationRepository, never()).findById(any());
        }
    }
}
