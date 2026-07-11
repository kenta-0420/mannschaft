package com.mannschaft.app.safetycheck;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.safetycheck.dto.CreateSafetyCheckRequest;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import com.mannschaft.app.safetycheck.service.SafetyCheckService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SafetyCheckService} の認可契約テスト（束3 AC-1-4）。
 *
 * <p>安否確認の作成・クローズ・リマインド送信・結果閲覧・未回答者一覧閲覧は
 * スコープADMIN/DEPUTY_ADMINのみ許可する。非メンバー・非ADMINは403（COMMON_002）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafetyCheckService 認可契約テスト（AC-1-4）")
class SafetyCheckServiceAuthzTest {

    @Mock
    private SafetyCheckRepository safetyCheckRepository;

    @Mock
    private SafetyResponseRepository safetyResponseRepository;

    @Mock
    private SafetyCheckTemplateRepository templateRepository;

    @Mock
    private SafetyCheckMapper mapper;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SafetyCheckService safetyCheckService;

    private static final Long SAFETY_CHECK_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long NON_MEMBER_USER_ID = 999L;

    private SafetyCheckEntity createActiveCheck() {
        return SafetyCheckEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("地震発生")
                .status(SafetyCheckStatus.ACTIVE)
                .totalTargetCount(10)
                .createdBy(1L)
                .build();
    }

    @Nested
    @DisplayName("createSafetyCheck")
    class CreateSafetyCheck {

        // AC-1-4: 非ADMINが安否確認を発信 → 403
        @Test
        @DisplayName("安否確認発信_非ADMIN_403でリポジトリ非委譲")
        void 安否確認発信_非ADMIN_403() {
            // Given
            CreateSafetyCheckRequest req = new CreateSafetyCheckRequest(
                    "地震発生", "安否を報告してください", "TEAM", SCOPE_ID,
                    false, 30, null);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(NON_MEMBER_USER_ID, SCOPE_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.createSafetyCheck(req, NON_MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(safetyCheckRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("closeSafetyCheck")
    class CloseSafetyCheck {

        // AC-1-4: 非ADMINが安否確認をクローズ → 403
        @Test
        @DisplayName("クローズ_非ADMIN_403でリポジトリ非委譲")
        void クローズ_非ADMIN_403() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(NON_MEMBER_USER_ID, SCOPE_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.closeSafetyCheck(SAFETY_CHECK_ID, NON_MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            assertThat(entity.getStatus()).isEqualTo(SafetyCheckStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("sendReminder")
    class SendReminder {

        // AC-1-4: 非ADMINがリマインド送信 → 403
        @Test
        @DisplayName("リマインド送信_非ADMIN_403")
        void リマインド送信_非ADMIN_403() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(NON_MEMBER_USER_ID, SCOPE_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.sendReminder(SAFETY_CHECK_ID, NON_MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    @Nested
    @DisplayName("getResults")
    class GetResults {

        // AC-1-4: 非ADMINが結果集計を閲覧 → 403
        @Test
        @DisplayName("結果集計取得_非ADMIN_403")
        void 結果集計取得_非ADMIN_403() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(NON_MEMBER_USER_ID, SCOPE_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.getResults(SAFETY_CHECK_ID, NON_MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(safetyResponseRepository, never()).findBySafetyCheckIdOrderByRespondedAtAsc(any());
        }
    }

    @Nested
    @DisplayName("getUnrespondedUsers")
    class GetUnrespondedUsers {

        // AC-1-4: 非メンバーが未回答者一覧を取得 → 403
        @Test
        @DisplayName("未回答者一覧取得_非メンバー_403でリポジトリ非委譲")
        void 未回答者一覧取得_非メンバー_403() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(NON_MEMBER_USER_ID, SCOPE_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.getUnrespondedUsers(SAFETY_CHECK_ID, NON_MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(safetyResponseRepository, never()).findRespondedUserIdsBySafetyCheckId(any());
        }
    }
}
