package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.event.RecruitmentReminderNotificationEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentReminderRunner} の単体テスト（Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>検証する受け入れ条件:</p>
 * <ul>
 *   <li><b>AC-3</b>: 通知は publish するだけで {@code NotificationService} を直接呼ばない
 *       （＝この TX がロールバックすれば {@code AFTER_COMMIT} は発火せず通知は作られない）。</li>
 *   <li><b>AC-4</b>: 独立TX内で読み直して {@code sentAt == null} を再判定し、再実行しても二重送信しない。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentReminderRunner 単体テスト（Issue #2834 / CMP-056）")
class RecruitmentReminderRunnerTest {

    @Mock
    private RecruitmentReminderRepository reminderRepository;
    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private RecruitmentParticipantRepository participantRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RecruitmentReminderRunner runner;

    @Test
    @DisplayName("AC-3: sent_at を確定し、通知配送要求（ID のみ）を publish する")
    void processOne_marksSentAndPublishesEvent() throws Exception {
        given(reminderRepository.findById(1L)).willReturn(Optional.of(buildReminder(1L, 10L, 100L, null)));
        given(listingRepository.findById(10L)).willReturn(Optional.of(buildListing(10L)));
        given(participantRepository.findById(100L)).willReturn(Optional.of(buildParticipant(100L, 5L)));

        boolean published = runner.processOne(1L);

        assertThat(published).isTrue();
        verify(reminderRepository).save(any());
        ArgumentCaptor<RecruitmentReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(RecruitmentReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reminderId()).isEqualTo(1L);
        assertThat(captor.getValue().listingId()).isEqualTo(10L);
        assertThat(captor.getValue().recipientUserId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("AC-4: 読み直した時点で sent_at が入っていれば二重送信しない")
    void processOne_alreadySent_isIdempotent() throws Exception {
        given(reminderRepository.findById(1L))
                .willReturn(Optional.of(buildReminder(1L, 10L, 100L, LocalDateTime.now().minusMinutes(1))));

        boolean published = runner.processOne(1L);

        assertThat(published).isFalse();
        verify(reminderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(RecruitmentReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("募集が削除済みなら sent_at だけ確定して通知は publish しない")
    void processOne_listingDeleted_marksSentWithoutEvent() throws Exception {
        given(reminderRepository.findById(1L)).willReturn(Optional.of(buildReminder(1L, 10L, 100L, null)));
        given(listingRepository.findById(10L)).willReturn(Optional.empty());

        boolean published = runner.processOne(1L);

        assertThat(published).isFalse();
        verify(reminderRepository).save(any());
        verify(eventPublisher, never()).publishEvent(any(RecruitmentReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("参加者がチーム参加（userId=null）なら sent_at だけ確定して通知は publish しない")
    void processOne_teamParticipant_marksSentWithoutEvent() throws Exception {
        given(reminderRepository.findById(1L)).willReturn(Optional.of(buildReminder(1L, 10L, 100L, null)));
        given(listingRepository.findById(10L)).willReturn(Optional.of(buildListing(10L)));
        given(participantRepository.findById(100L)).willReturn(Optional.of(buildParticipant(100L, null)));

        boolean published = runner.processOne(1L);

        assertThat(published).isFalse();
        verify(reminderRepository).save(any());
        verify(eventPublisher, never()).publishEvent(any(RecruitmentReminderNotificationEvent.class));
    }

    private RecruitmentReminderEntity buildReminder(Long id, Long listingId, Long participantId,
                                                    LocalDateTime sentAt) throws Exception {
        RecruitmentReminderEntity reminder = RecruitmentReminderEntity.builder()
                .listingId(listingId)
                .participantId(participantId)
                .remindAt(LocalDateTime.now().minusMinutes(5))
                .build();
        setField(reminder, "id", id);
        if (sentAt != null) {
            setField(reminder, "sentAt", sentAt);
        }
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
