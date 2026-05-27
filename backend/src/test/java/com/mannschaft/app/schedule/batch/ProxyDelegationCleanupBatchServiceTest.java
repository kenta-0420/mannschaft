package com.mannschaft.app.schedule.batch;

import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ProxyDelegationCleanupBatchService} の単体テスト（§5.8 補完バッチ）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyDelegationCleanupBatchService 単体テスト")
class ProxyDelegationCleanupBatchServiceTest {

    @Mock private ScheduleDelegationService scheduleDelegationService;
    @Mock private EventDelegationService eventDelegationService;
    @Mock private MembershipRepository membershipRepository;

    @InjectMocks
    private ProxyDelegationCleanupBatchService batch;

    @Test
    @DisplayName("代理人が在籍しない孤立委任を CANCELLED にする")
    void 代理人非在籍を取消() {
        ScheduleDelegationEntity orphan = ScheduleDelegationEntity.builder()
                .scheduleId(10L).delegatorId(100L).delegateId(200L).teamId(5L)
                .status(ScheduleDelegationStatus.ACCEPTED).build();
        given(scheduleDelegationService.findAllActive()).willReturn(List.of(orphan));
        given(membershipRepository.existsActiveByUserAndScope(100L, ScopeType.TEAM, 5L)).willReturn(true);
        given(membershipRepository.existsActiveByUserAndScope(200L, ScopeType.TEAM, 5L)).willReturn(false);

        int cancelled = batch.cleanupScheduleDelegations();

        assertThat(cancelled).isEqualTo(1);
        verify(scheduleDelegationService).cancelOnMemberLeft(orphan, 200L);
    }

    @Test
    @DisplayName("両者在籍している委任は取消しない")
    void 両者在籍は温存() {
        ScheduleDelegationEntity active = ScheduleDelegationEntity.builder()
                .scheduleId(10L).delegatorId(100L).delegateId(200L).teamId(5L)
                .status(ScheduleDelegationStatus.ACCEPTED).build();
        given(scheduleDelegationService.findAllActive()).willReturn(List.of(active));
        given(membershipRepository.existsActiveByUserAndScope(eq(100L), eq(ScopeType.TEAM), eq(5L))).willReturn(true);
        given(membershipRepository.existsActiveByUserAndScope(eq(200L), eq(ScopeType.TEAM), eq(5L))).willReturn(true);

        int cancelled = batch.cleanupScheduleDelegations();

        assertThat(cancelled).isZero();
        verify(scheduleDelegationService, never()).cancelOnMemberLeft(active, 200L);
    }
}
