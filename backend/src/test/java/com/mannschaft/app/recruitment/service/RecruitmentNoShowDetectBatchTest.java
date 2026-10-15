package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.event.RecruitmentNoShowNotificationEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecruitmentNoShowDetectBatchTest {

    @Mock private RecruitmentParticipantRepository participantRepository;
    @Mock private RecruitmentNoShowRecordRepository noShowRepository;
    @Mock private RecruitmentPenaltySettingRepository settingRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private RecruitmentNoShowDetectBatch batch;

    @Test
    void rerunDoesNotPublishDuplicateNotification() {
        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM).scopeId(7L).build();
        RecruitmentParticipantEntity participant = RecruitmentParticipantEntity.builder()
                .id(11L).listingId(5L).userId(9L)
                .status(RecruitmentParticipantStatus.CONFIRMED).build();
        RecruitmentNoShowRecordEntity existing = RecruitmentNoShowRecordEntity.builder()
                .participantId(11L).listingId(5L).userId(9L).build();
        given(settingRepository.findByAutoNoShowDetectionTrue(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(setting)));
        given(participantRepository.findConfirmedInEndedListings(
                org.mockito.ArgumentMatchers.eq(RecruitmentScopeType.TEAM),
                org.mockito.ArgumentMatchers.eq(7L), any(LocalDateTime.class)))
                .willReturn(List.of(participant));
        given(noShowRepository.findByParticipantId(11L))
                .willReturn(Optional.empty(), Optional.of(existing));
        given(noShowRepository.save(any(RecruitmentNoShowRecordEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        batch.detectNoShows();
        batch.detectNoShows();

        assertThat(participant.getStatus()).isEqualTo(RecruitmentParticipantStatus.NO_SHOW);
        verify(eventPublisher, times(1)).publishEvent(
                new RecruitmentNoShowNotificationEvent(null, 5L, 9L));
    }
}
