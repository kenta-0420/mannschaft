package com.mannschaft.app.schedule.listener;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.event.MembershipEndedEvent;
import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.service.ScheduleDelegationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleDelegationMembershipListener} の単体テスト（§5.8）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleDelegationMembershipListener 単体テスト")
class ScheduleDelegationMembershipListenerTest {

    @Mock private ScheduleDelegationService delegationService;

    @InjectMocks
    private ScheduleDelegationMembershipListener listener;

    @Test
    @DisplayName("TEAM 退会: 当事者のアクティブ代理を全件取消する")
    void チーム退会で取消() {
        Long userId = 100L;
        Long teamId = 5L;
        ScheduleDelegationEntity d = ScheduleDelegationEntity.builder()
                .scheduleId(10L).delegatorId(userId).delegateId(200L).teamId(teamId)
                .status(ScheduleDelegationStatus.ACCEPTED).build();
        given(delegationService.findActiveByScopeAndInvolvedUser(isNull(), eq(teamId), eq(userId)))
                .willReturn(List.of(d));

        listener.handleMembershipEnded(new MembershipEndedEvent(userId, ScopeType.TEAM, teamId));

        verify(delegationService).cancelOnMemberLeft(d, userId);
    }

    @Test
    @DisplayName("ORGANIZATION 退会: organizationId で絞り込む")
    void 組織退会で取消() {
        Long userId = 100L;
        Long orgId = 7L;
        given(delegationService.findActiveByScopeAndInvolvedUser(eq(orgId), isNull(), eq(userId)))
                .willReturn(List.of());

        listener.handleMembershipEnded(new MembershipEndedEvent(userId, ScopeType.ORGANIZATION, orgId));

        verify(delegationService).findActiveByScopeAndInvolvedUser(orgId, null, userId);
    }
}
