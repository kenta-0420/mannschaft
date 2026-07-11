package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.repository.AttendanceTransitionAlertRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TransitionAlertService} 認可テスト（認可根治戦役 束4・移動検知アラート）。
 *
 * <p>マスター御裁可済み方針: 閲覧（getAlerts）は checkMembership、
 * 確認/解決（resolveAlert）は checkAdminOrAbove。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransitionAlertService 認可テスト（束4）")
class TransitionAlertServiceTest {

    @Mock
    private AttendanceTransitionAlertRepository alertRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TransitionAlertService transitionAlertService;

    private static final Long TEAM_ID = 1L;
    private static final Long MEMBER_USER_ID = 100L;
    private static final Long ADMIN_USER_ID = 200L;
    private static final Long OUTSIDER_USER_ID = 999L;
    private static final Long ALERT_ID = 10L;
    private static final LocalDate ATTENDANCE_DATE = LocalDate.of(2026, 7, 1);

    @Nested
    @DisplayName("getAlerts（閲覧＝所属者のみ）")
    class GetAlerts {

        @Test
        @DisplayName("red→green: 対象チーム非所属ユーザーが GET 移動検知アラート一覧 → 403 (COMMON_002)")
        void nonMember_forbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(OUTSIDER_USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> transitionAlertService
                    .getAlerts(TEAM_ID, ATTENDANCE_DATE, false, OUTSIDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            verify(alertRepository, never())
                    .findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("非回帰: チーム所属ユーザーは従来どおり一覧取得可能")
        void member_success() {
            doNothing().when(accessControlService).checkMembership(MEMBER_USER_ID, TEAM_ID, "TEAM");
            given(alertRepository.findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            var response = transitionAlertService.getAlerts(TEAM_ID, ATTENDANCE_DATE, false, MEMBER_USER_ID);

            assertThat(response.getAlerts()).isEmpty();
            verify(accessControlService).checkMembership(MEMBER_USER_ID, TEAM_ID, "TEAM");
        }
    }

    @Nested
    @DisplayName("resolveAlert（確認/解決＝ADMIN以上のみ）")
    class ResolveAlert {

        @Test
        @DisplayName("red→green: 非ADMINが POST 移動検知アラート解決 → 403 (COMMON_002)")
        void nonAdmin_forbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> transitionAlertService
                    .resolveAlert(TEAM_ID, ALERT_ID, MEMBER_USER_ID, "解決しました"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            verify(alertRepository, never()).findById(any());
        }

        @Test
        @DisplayName("非回帰: ADMINは従来どおりアラートを解決可能")
        void admin_success() {
            doNothing().when(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM");

            AttendanceTransitionAlertEntity entity = AttendanceTransitionAlertEntity.builder()
                    .teamId(TEAM_ID)
                    .studentUserId(300L)
                    .attendanceDate(ATTENDANCE_DATE)
                    .build();
            ReflectionTestUtils.setField(entity, "id", ALERT_ID);
            given(alertRepository.findById(ALERT_ID)).willReturn(Optional.of(entity));
            given(alertRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            var response = transitionAlertService
                    .resolveAlert(TEAM_ID, ALERT_ID, ADMIN_USER_ID, "解決しました");

            assertThat(response).isNotNull();
            verify(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM");
        }
    }
}
