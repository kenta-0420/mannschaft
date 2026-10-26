package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.event.RecruitmentPenaltyLiftedNotificationEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecruitmentPenaltyLiftBatchTest {

    @Mock
    private RecruitmentUserPenaltyRepository penaltyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RecruitmentPenaltyLiftBatch batch;

    @Test
    void expiredPenaltiesAreLiftedAndPublishedOncePerPenalty() {
        RecruitmentUserPenaltyEntity first = expiredPenalty(101L, 201L, RecruitmentScopeType.TEAM, 301L);
        RecruitmentUserPenaltyEntity second = expiredPenalty(102L, 202L, RecruitmentScopeType.ORGANIZATION, 302L);
        given(penaltyRepository.findExpiredPenalties(any())).willReturn(List.of(first, second));

        batch.liftExpiredPenalties();

        verify(penaltyRepository).saveAll(List.of(first, second));
        ArgumentCaptor<RecruitmentPenaltyLiftedNotificationEvent> events =
                ArgumentCaptor.forClass(RecruitmentPenaltyLiftedNotificationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).containsExactly(
                new RecruitmentPenaltyLiftedNotificationEvent(101L, 201L, RecruitmentScopeType.TEAM, 301L,
                        PenaltyLiftReason.AUTO_EXPIRED),
                new RecruitmentPenaltyLiftedNotificationEvent(102L, 202L, RecruitmentScopeType.ORGANIZATION, 302L,
                        PenaltyLiftReason.AUTO_EXPIRED));
        assertThat(first.getLiftReason()).isEqualTo(com.mannschaft.app.recruitment.PenaltyLiftReason.AUTO_EXPIRED);
        assertThat(second.getLiftReason()).isEqualTo(com.mannschaft.app.recruitment.PenaltyLiftReason.AUTO_EXPIRED);
    }

    @Test
    void noExpiredPenaltyDoesNotSaveOrPublish() {
        given(penaltyRepository.findExpiredPenalties(any())).willReturn(List.of());

        batch.liftExpiredPenalties();

        verify(penaltyRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private RecruitmentUserPenaltyEntity expiredPenalty(Long id, Long userId, RecruitmentScopeType scopeType, Long scopeId) {
        LocalDateTime now = LocalDateTime.now();
        RecruitmentUserPenaltyEntity penalty = RecruitmentUserPenaltyEntity.builder()
                .userId(userId).scopeType(scopeType).scopeId(scopeId)
                .triggeredBySettingId(1L).triggeredNoShowCount(3)
                .startedAt(now.minusDays(10)).expiresAt(now.minusDays(1)).build();
        ReflectionTestUtils.setField(penalty, "id", id);
        return penalty;
    }
}
