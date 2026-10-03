package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.error.SchoolErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** {@link TransitionAlertFacade}: 閲覧 V・解決 R の認可を業務 Service より前に行うことの検証。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransitionAlertFacade 認可テスト")
class TransitionAlertFacadeTest {

    @Mock
    private TransitionAlertService alertService;

    @Mock
    private SchoolAttendanceAccessPolicy policy;

    @InjectMocks
    private TransitionAlertFacade facade;

    private static final Long TEAM_ID = 1L;
    private static final Long OTHER_TEAM_ID = 777L;
    private static final Long ALERT_ID = 10L;
    private static final Long OUTSIDER_USER_ID = 999L;
    private static final Long TEACHER_USER_ID = 100L;
    private static final LocalDate DATE = LocalDate.of(2026, 7, 1);

    @Test
    @DisplayName("閲覧権（V）の無いユーザーの一覧取得 → 403、業務 Service は呼ばれない")
    void getAlerts_nonViewer_forbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(policy).checkCanView(OUTSIDER_USER_ID, TEAM_ID);

        assertThatThrownBy(() -> facade.getAlerts(TEAM_ID, DATE, false, OUTSIDER_USER_ID))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("閲覧権（V）を持つユーザーは一覧を取得できる")
    void getAlerts_viewer_delegates() {
        facade.getAlerts(TEAM_ID, DATE, true, TEACHER_USER_ID);

        verify(policy).checkCanView(TEACHER_USER_ID, TEAM_ID);
        verify(alertService).getAlerts(TEAM_ID, DATE, true);
    }

    @Test
    @DisplayName("AC-14: 日次登録権（R）の無い者の解決 → 403、解決処理は走らない")
    void resolve_nonWriter_forbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(policy).checkCanRecordDaily(OUTSIDER_USER_ID, TEAM_ID);

        assertThatThrownBy(() -> facade.resolveAlert(TEAM_ID, ALERT_ID, OUTSIDER_USER_ID, "解決"))
                .isInstanceOf(BusinessException.class);

        verify(alertService, never()).resolveAlert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("BOLA: path の teamId 配下でないアラートは認可判定に到達せず 404（存在秘匿）")
    void resolve_otherTeamAlert_hiddenBeforeAuthz() {
        doThrow(new BusinessException(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND))
                .when(alertService).requireAlertInTeam(TEAM_ID, ALERT_ID);

        assertThatThrownBy(() -> facade.resolveAlert(TEAM_ID, ALERT_ID, TEACHER_USER_ID, "他チームを握り潰す"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND);

        verifyNoInteractions(policy);
        verify(alertService, never()).resolveAlert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("BOLA: 他チームの ADMIN が victim team を path に指定しても、その team の R が無ければ 403")
    void resolve_victimTeam_forbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(policy).checkCanRecordDaily(TEACHER_USER_ID, OTHER_TEAM_ID);

        assertThatThrownBy(() -> facade.resolveAlert(OTHER_TEAM_ID, ALERT_ID, TEACHER_USER_ID, "解決"))
                .isInstanceOf(BusinessException.class);

        verify(alertService, never()).resolveAlert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("R を持つ者は、スコープ照合 → 認可 → 解決の順で解決できる")
    void resolve_writer_orderedDelegation() {
        facade.resolveAlert(TEAM_ID, ALERT_ID, TEACHER_USER_ID, "解決しました");

        InOrder inOrder = Mockito.inOrder(alertService, policy);
        inOrder.verify(alertService).requireAlertInTeam(TEAM_ID, ALERT_ID);
        inOrder.verify(policy).checkCanRecordDaily(TEACHER_USER_ID, TEAM_ID);
        inOrder.verify(alertService).resolveAlert(TEAM_ID, ALERT_ID, TEACHER_USER_ID, "解決しました");
    }
}
