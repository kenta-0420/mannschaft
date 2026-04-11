package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentReminderBatch} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentReminderBatch 単体テスト")
class RecruitmentReminderBatchTest {

    @Mock
    private RecruitmentReminderRepository reminderRepository;
    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private RecruitmentParticipantRepository participantRepository;
    @Mock
    private NotificationHelper notificationHelper;

    @InjectMocks
    private RecruitmentReminderBatch batch;

    @Test
    @DisplayName("未送信リマインダーがない場合 → 通知送信しない")
    void reminderBatch_noPending_noNotification() {
        given(reminderRepository.findTop100BySentAtIsNullAndRemindAtLessThanEqual(any()))
                .willReturn(List.of());

        batch.reminderBatch();

        verify(notificationHelper, never()).notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("未送信リマインダーがある場合 → 通知送信して sent_at 更新")
    void reminderBatch_hasPending_sendsNotification() throws Exception {
        RecruitmentReminderEntity reminder = buildReminder(1L, 10L, 100L);
        RecruitmentListingEntity listing = buildListing(10L);
        RecruitmentParticipantEntity participant = buildParticipant(100L, 5L);

        given(reminderRepository.findTop100BySentAtIsNullAndRemindAtLessThanEqual(any()))
                .willReturn(List.of(reminder));
        given(listingRepository.findById(10L)).willReturn(Optional.of(listing));
        given(participantRepository.findById(100L)).willReturn(Optional.of(participant));
        given(reminderRepository.save(any())).willReturn(null);

        batch.reminderBatch();

        verify(notificationHelper).notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(reminderRepository).save(any());
    }

    @Test
    @DisplayName("募集が削除済みの場合 → 通知スキップして sent_at 更新")
    void reminderBatch_listingDeleted_skipNotification() throws Exception {
        RecruitmentReminderEntity reminder = buildReminder(1L, 10L, 100L);

        given(reminderRepository.findTop100BySentAtIsNullAndRemindAtLessThanEqual(any()))
                .willReturn(List.of(reminder));
        given(listingRepository.findById(10L)).willReturn(Optional.empty());
        given(reminderRepository.save(any())).willReturn(null);

        batch.reminderBatch();

        verify(notificationHelper, never()).notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(reminderRepository).save(any());
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RecruitmentReminderEntity buildReminder(Long id, Long listingId, Long participantId) throws Exception {
        RecruitmentReminderEntity reminder = RecruitmentReminderEntity.builder()
                .listingId(listingId)
                .participantId(participantId)
                .remindAt(LocalDateTime.now().minusMinutes(5))
                .build();
        setField(reminder, "id", id);
        return reminder;
    }

    private RecruitmentListingEntity buildListing(Long id) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .categoryId(1L)
                .title("テスト募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusHours(20))
                .endAt(LocalDateTime.now().plusHours(22))
                .applicationDeadline(LocalDateTime.now().minusHours(4))
                .autoCancelAt(LocalDateTime.now().minusHours(4))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(2L)
                .build();
        setField(listing, "id", id);
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        return listing;
    }

    private RecruitmentParticipantEntity buildParticipant(Long id, Long userId) throws Exception {
        RecruitmentParticipantEntity participant = RecruitmentParticipantEntity.builder()
                .listingId(10L)
                .participantType(RecruitmentParticipantType.USER)
                .userId(userId)
                .appliedBy(userId)
                .build();
        setField(participant, "id", id);
        return participant;
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
