package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TransitionAlertService} の業務ロジックテスト。
 *
 * <p>認可（閲覧 V・解決 R）はトランザクションの外の {@link TransitionAlertFacade} の責務であり、
 * {@link TransitionAlertFacadeTest} が検証する。本テストは、一覧取得・解決・path の teamId 配下確認（存在秘匿）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransitionAlertService 業務ロジックテスト")
class TransitionAlertServiceTest {

    @Mock
    private AttendanceTransitionAlertRepository alertRepository;

    @InjectMocks
    private TransitionAlertService transitionAlertService;

    private static final Long TEAM_ID = 1L;
    /** 別テナントのチーム（BOLA クロステナント検証用）。 */
    private static final Long OTHER_TEAM_ID = 777L;
    private static final Long ADMIN_USER_ID = 200L;
    private static final Long ALERT_ID = 10L;
    private static final LocalDate ATTENDANCE_DATE = LocalDate.of(2026, 7, 1);

    /** 指定チームに属する未解決アラート（id 付き）を生成する。 */
    private AttendanceTransitionAlertEntity buildAlert(Long teamId) {
        AttendanceTransitionAlertEntity entity = AttendanceTransitionAlertEntity.builder()
                .teamId(teamId)
                .studentUserId(300L)
                .attendanceDate(ATTENDANCE_DATE)
                .build();
        ReflectionTestUtils.setField(entity, "id", ALERT_ID);
        return entity;
    }

    @Nested
    @DisplayName("getAlerts")
    class GetAlerts {

        @Test
        @DisplayName("アラートが無ければ空の一覧")
        void empty() {
            given(alertRepository.findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            var response = transitionAlertService.getAlerts(TEAM_ID, ATTENDANCE_DATE, false);

            assertThat(response.getAlerts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("requireAlertInTeam / resolveAlert（entity 由来スコープの照合・存在秘匿）")
    class ResolveAlert {

        @Test
        @DisplayName("BOLA: path=自team で他チームの alertId → 404 存在秘匿（解決処理に到達しない）")
        void crossTenant_pathOwnTeam_notFound() {
            given(alertRepository.findById(ALERT_ID)).willReturn(Optional.of(buildAlert(OTHER_TEAM_ID)));

            assertThatThrownBy(() -> transitionAlertService.requireAlertInTeam(TEAM_ID, ALERT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND);
            assertThatThrownBy(() -> transitionAlertService
                    .resolveAlert(TEAM_ID, ALERT_ID, ADMIN_USER_ID, "他チームを握り潰す"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND);

            verify(alertRepository, never()).save(any());
        }

        @Test
        @DisplayName("存在しない alertId → 404")
        void notFound() {
            given(alertRepository.findById(ALERT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> transitionAlertService.requireAlertInTeam(TEAM_ID, ALERT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND);
        }

        @Test
        @DisplayName("path=自team・alertも自team ならアラートを解決できる")
        void resolves() {
            given(alertRepository.findById(ALERT_ID)).willReturn(Optional.of(buildAlert(TEAM_ID)));
            given(alertRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            var response = transitionAlertService.resolveAlert(TEAM_ID, ALERT_ID, ADMIN_USER_ID, "解決しました");

            assertThat(response).isNotNull();
        }
    }
}
