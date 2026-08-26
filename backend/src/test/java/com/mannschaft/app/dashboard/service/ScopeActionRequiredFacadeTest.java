package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * F22.1 第二波: {@link ScopeActionRequiredFacade} の単体テスト。
 *
 * <p>3 ドメイン（回覧板/アンケート/出欠）の集約・合計算出・per-scope 認可・縮退を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeActionRequiredFacade 単体テスト")
class ScopeActionRequiredFacadeTest {

    @Mock
    private CirculationService circulationService;

    @Mock
    private SurveyService surveyService;

    @Mock
    private ScheduleAttendanceService scheduleAttendanceService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ScopeActionRequiredFacade facade;

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    private CirculationDocumentEntity circDoc() {
        return CirculationDocumentEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(2L)
                .title("5月度 回覧").body("本文").build();
    }

    private SurveyEntity survey() {
        return SurveyEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).title("懇親会の出欠").build();
    }

    private ScheduleEntity schedule() {
        return ScheduleEntity.builder()
                .teamId(SCOPE_ID).title("定例ミーティング")
                .startAt(LocalDateTime.now().plusDays(3))
                .eventType(com.mannschaft.app.schedule.EventType.MEETING)
                .visibility(com.mannschaft.app.schedule.ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(com.mannschaft.app.schedule.MinViewRole.MEMBER_PLUS)
                .status(com.mannschaft.app.schedule.ScheduleStatus.SCHEDULED)
                .build();
    }

    @Nested
    @DisplayName("getActionRequired: 正常系")
    class Normal {

        @Test
        @DisplayName("3 区分の件数と合計が集約される")
        void 集約と合計() {
            given(circulationService.getUnconfirmedForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new CirculationService.UnconfirmedCirculations(2, List.of(circDoc())));
            given(surveyService.getUnansweredForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new SurveyService.UnansweredSurveys(1, List.of(survey())));
            given(scheduleAttendanceService.getUnansweredForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new ScheduleAttendanceService.UnansweredAttendances(3, List.of(schedule())));

            ActionRequiredSummaryResponse result = facade.getActionRequired(USER_ID, SCOPE_TYPE, SCOPE_ID);

            assertThat(result.circulation().unconfirmedCount()).isEqualTo(2);
            assertThat(result.survey().unansweredCount()).isEqualTo(1);
            assertThat(result.attendance().unansweredCount()).isEqualTo(3);
            assertThat(result.totalActionCount()).isEqualTo(6);
            assertThat(result.circulation().items()).hasSize(1);
            assertThat(result.survey().items()).hasSize(1);
            assertThat(result.attendance().items()).hasSize(1);
            // 入口の所属検証が呼ばれること（配信＝受信権: 集約入口は広め includeSupporters=true で通す）
            verify(accessControlService).checkMembershipOrDescendant(USER_ID, SCOPE_ID, SCOPE_TYPE, true);
        }

        @Test
        @DisplayName("全区分 0 件なら total=0")
        void 全件ゼロ() {
            given(circulationService.getUnconfirmedForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new CirculationService.UnconfirmedCirculations(0, List.of()));
            given(surveyService.getUnansweredForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new SurveyService.UnansweredSurveys(0, List.of()));
            given(scheduleAttendanceService.getUnansweredForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new ScheduleAttendanceService.UnansweredAttendances(0, List.of()));

            ActionRequiredSummaryResponse result = facade.getActionRequired(USER_ID, SCOPE_TYPE, SCOPE_ID);

            assertThat(result.totalActionCount()).isZero();
            assertThat(result.circulation().items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getActionRequired: 認可・縮退")
    class AuthAndDegrade {

        @Test
        @DisplayName("入口の checkMembershipOrDescendant が 403 を投げると全体が拒否される")
        void 非所属は拒否() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembershipOrDescendant(USER_ID, SCOPE_ID, SCOPE_TYPE, true);

            assertThatThrownBy(() -> facade.getActionRequired(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("1 区分が例外でも当該区分のみ 0 件に縮退し他区分は返る")
        void 区分縮退() {
            given(circulationService.getUnconfirmedForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willThrow(new RuntimeException("回覧ドメイン一時障害"));
            given(surveyService.getUnansweredForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new SurveyService.UnansweredSurveys(1, List.of(survey())));
            given(scheduleAttendanceService.getUnansweredForUserInScope(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(USER_ID), anyInt()))
                    .willReturn(new ScheduleAttendanceService.UnansweredAttendances(2, List.of(schedule())));

            ActionRequiredSummaryResponse result = facade.getActionRequired(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // 回覧は 0 件縮退、他は返る
            assertThat(result.circulation().unconfirmedCount()).isZero();
            assertThat(result.circulation().items()).isEmpty();
            assertThat(result.survey().unansweredCount()).isEqualTo(1);
            assertThat(result.attendance().unansweredCount()).isEqualTo(2);
            assertThat(result.totalActionCount()).isEqualTo(3);
        }
    }
}
