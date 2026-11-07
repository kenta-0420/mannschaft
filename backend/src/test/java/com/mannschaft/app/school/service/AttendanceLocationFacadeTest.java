package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import com.mannschaft.app.school.error.SchoolErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** {@link AttendanceLocationFacade}: 認可を通ってから業務 Service を呼ぶこと（認可前に副作用を起こさないこと）の検証。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendanceLocationFacade 認可テスト")
class AttendanceLocationFacadeTest {

    @Mock
    private AttendanceLocationService locationService;

    @Mock
    private SchoolAttendanceAccessPolicy policy;

    @InjectMocks
    private AttendanceLocationFacade facade;

    private static final Long TEAM_ID = 1L;
    private static final Long OPERATOR_USER_ID = 100L;
    private static final Long STUDENT_USER_ID = 201L;
    private static final Long OUTSIDER_USER_ID = 999L;
    private static final LocalDate DATE = LocalDate.of(2026, 7, 1);

    private void record(Long operator) {
        facade.recordLocationChange(TEAM_ID, STUDENT_USER_ID, DATE,
                AttendanceLocation.CLASSROOM, AttendanceLocation.SICK_BAY,
                null, null, AttendanceLocationChangeReason.FELT_SICK, null, operator);
    }

    @Test
    @DisplayName("AC-13: 日次登録権（R）の無い operator が記録 → 403、業務 Service は呼ばれない")
    void record_nonWriter_forbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(policy).checkCanRecordDaily(OUTSIDER_USER_ID, TEAM_ID);

        assertThatThrownBy(() -> record(OUTSIDER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verifyNoInteractions(locationService);
    }

    @Test
    @DisplayName("AC-13: 対象生徒がクラスの在籍メンバーでなければ DAILY_RECORD_NOT_FOUND で何も書かない")
    void record_studentNotEnrolled_rejected() {
        given(policy.isEnrolledStudent(TEAM_ID, STUDENT_USER_ID)).willReturn(false);

        assertThatThrownBy(() -> record(OPERATOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(SchoolErrorCode.DAILY_RECORD_NOT_FOUND);

        verify(policy).checkCanRecordDaily(OPERATOR_USER_ID, TEAM_ID);
        verifyNoInteractions(locationService);
    }

    @Test
    @DisplayName("非回帰: R を持つ operator が在籍生徒を記録すると業務 Service へ委譲する")
    void record_writer_delegates() {
        given(policy.isEnrolledStudent(TEAM_ID, STUDENT_USER_ID)).willReturn(true);

        record(OPERATOR_USER_ID);

        verify(policy).checkCanRecordDaily(OPERATOR_USER_ID, TEAM_ID);
        verify(locationService).recordLocationChange(TEAM_ID, STUDENT_USER_ID, DATE,
                AttendanceLocation.CLASSROOM, AttendanceLocation.SICK_BAY,
                null, null, AttendanceLocationChangeReason.FELT_SICK, null, OPERATOR_USER_ID);
    }

    @Test
    @DisplayName("AC-1-5: 閲覧権（V）の無いユーザーのクラス位置一覧 → 403、業務 Service は呼ばれない")
    void teamMap_nonViewer_forbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(policy).checkCanView(OUTSIDER_USER_ID, TEAM_ID);

        assertThatThrownBy(() -> facade.getTeamLocationMap(TEAM_ID, DATE, OUTSIDER_USER_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(locationService);
    }

    @Test
    @DisplayName("非回帰: V を持つユーザーはクラス位置一覧を取得できる")
    void teamMap_viewer_delegates() {
        given(locationService.getTeamLocationMap(TEAM_ID, DATE))
                .willReturn(Map.of(STUDENT_USER_ID, AttendanceLocation.CLASSROOM));

        assertThat(facade.getTeamLocationMap(TEAM_ID, DATE, OPERATOR_USER_ID)).hasSize(1);
        verify(policy).checkCanView(OPERATOR_USER_ID, TEAM_ID);
    }

    @Test
    @DisplayName("AC-4: タイムラインは Policy が解決した返却範囲を業務 Service へ渡す")
    void timeline_passesResolvedScope() {
        given(policy.resolveViewableTeamIds(STUDENT_USER_ID, OPERATOR_USER_ID)).willReturn(Set.of(TEAM_ID));

        facade.getTimeline(STUDENT_USER_ID, DATE, OPERATOR_USER_ID);

        verify(locationService).getTimeline(STUDENT_USER_ID, DATE, Set.of(TEAM_ID));
    }

    @Test
    @DisplayName("AC-4: 閲覧できるクラスが無い者のタイムラインは 403、業務 Service は呼ばれない")
    void timeline_noViewableClass_forbidden() {
        given(policy.resolveViewableTeamIds(STUDENT_USER_ID, OUTSIDER_USER_ID))
                .willThrow(new BusinessException(CommonErrorCode.COMMON_002));

        assertThatThrownBy(() -> facade.getTimeline(STUDENT_USER_ID, DATE, OUTSIDER_USER_ID))
                .isInstanceOf(BusinessException.class);

        verify(locationService, never()).getTimeline(any(), any(), any());
    }
}
