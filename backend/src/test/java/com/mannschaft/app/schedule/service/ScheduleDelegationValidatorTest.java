package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link ScheduleDelegationValidator} の単体テスト（§5.6）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleDelegationValidator 単体テスト")
class ScheduleDelegationValidatorTest {

    @Mock private MembershipRepository membershipRepository;
    @Mock private ScheduleDelegationRepository delegationRepository;

    @InjectMocks
    private ScheduleDelegationValidator validator;

    private static final Long TEAM_ID = 1L;
    private static final Long DELEGATOR = 100L;
    private static final Long DELEGATE = 200L;

    private ScheduleEntity baseSchedule() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .status(ScheduleStatus.SCHEDULED)
                .build();
    }

    @Test
    @DisplayName("#1: allow_proxy_attendance=FALSE で 422")
    void 代理不許可() {
        ScheduleEntity s = baseSchedule().toBuilder().allowProxyAttendance(false).build();
        assertThatThrownBy(() -> validator.validateForCreate(s, DELEGATOR, DELEGATE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("#7: CANCELLED で 422")
    void キャンセル済み() {
        ScheduleEntity s = baseSchedule().toBuilder().status(ScheduleStatus.CANCELLED).build();
        assertThatThrownBy(() -> validator.validateForCreate(s, DELEGATOR, DELEGATE))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_INVALID_SCHEDULE_STATUS);
    }

    @Test
    @DisplayName("#8: 親（繰り返し）スケジュールで 422")
    void 親スケジュール() {
        ScheduleEntity s = baseSchedule().toBuilder()
                .parentScheduleId(null).recurrenceRule("{\"freq\":\"WEEKLY\"}").build();
        assertThatThrownBy(() -> validator.validateForCreate(s, DELEGATOR, DELEGATE))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_PARENT_SCHEDULE);
    }

    @Test
    @DisplayName("#4: 自己代理で 422")
    void 自己代理() {
        assertThatThrownBy(() -> validator.validateForCreate(baseSchedule(), DELEGATOR, DELEGATOR))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_SELF_DELEGATION);
    }

    @Test
    @DisplayName("#2: 委任者がスコープ外で 403")
    void 委任者非メンバー() {
        given(membershipRepository.existsActiveByUserAndScope(DELEGATOR, ScopeType.TEAM, TEAM_ID)).willReturn(false);
        assertThatThrownBy(() -> validator.validateForCreate(baseSchedule(), DELEGATOR, DELEGATE))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_DELEGATOR_NOT_MEMBER);
    }

    @Test
    @DisplayName("#3: 代理人がスコープ外で 422")
    void 代理人非メンバー() {
        given(membershipRepository.existsActiveByUserAndScope(DELEGATOR, ScopeType.TEAM, TEAM_ID)).willReturn(true);
        given(membershipRepository.existsActiveByUserAndScope(DELEGATE, ScopeType.TEAM, TEAM_ID)).willReturn(false);
        assertThatThrownBy(() -> validator.validateForCreate(baseSchedule(), DELEGATOR, DELEGATE))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_DELEGATE_NOT_MEMBER);
    }

    @Test
    @DisplayName("#5: 委任者のアクティブ代理重複で 409")
    void 重複() {
        given(membershipRepository.existsActiveByUserAndScope(any(), eq(ScopeType.TEAM), eq(TEAM_ID))).willReturn(true);
        given(delegationRepository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(any(), eq(DELEGATOR), any()))
                .willReturn(Optional.of(ScheduleDelegationEntity.builder().build()));
        assertThatThrownBy(() -> validator.validateForCreate(baseSchedule(), DELEGATOR, DELEGATE))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("#6: 連鎖代理禁止で 422")
    void 連鎖代理() {
        given(membershipRepository.existsActiveByUserAndScope(any(), eq(ScopeType.TEAM), eq(TEAM_ID))).willReturn(true);
        given(delegationRepository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(any(), eq(DELEGATOR), any()))
                .willReturn(Optional.empty());
        given(delegationRepository.existsByDelegateIdAndStatusIn(eq(DELEGATE), any())).willReturn(true);
        assertThatThrownBy(() -> validator.validateForCreate(baseSchedule(), DELEGATOR, DELEGATE))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_CHAINED);
    }
}
