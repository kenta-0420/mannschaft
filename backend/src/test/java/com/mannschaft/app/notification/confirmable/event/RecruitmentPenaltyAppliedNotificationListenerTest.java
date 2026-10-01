package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.SystemUsers;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.event.RecruitmentPenaltyAppliedNotificationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecruitmentPenaltyAppliedNotificationListenerTest {

    @Mock
    private ConfirmableNotificationService confirmableNotificationService;

    @Mock
    private ConfirmableNotificationRepository confirmableNotificationRepository;

    @InjectMocks
    private RecruitmentPenaltyAppliedNotificationListener listener;

    private final Instant expiresAt = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void sendsUrgentConfirmationToPenaltyOwnerFromSystemUser() {
        listener.onPenaltyApplied(new RecruitmentPenaltyAppliedNotificationEvent(
                11L, 22L, RecruitmentScopeType.ORGANIZATION, 33L, expiresAt));

        // ALIC-1: actionUrl は受信箱から開ける導線（募集一覧フィード）を指す。
        // 専用の「自分のペナルティ」表示画面は未実装（設計書 §12 スコープ外）で、
        // 本イベントは listingId を持たないため募集詳細へは飛ばせない。
        verify(confirmableNotificationService).sendFromSource(
                eq("RECRUITMENT_PENALTY"), eq(11L), eq(ScopeType.ORGANIZATION), eq(33L),
                any(), any(), eq(ConfirmableNotificationPriority.URGENT),
                eq(LocalDateTime.ofInstant(expiresAt, UserZoneLocalDateTimeParser.SERVER_ZONE)),
                eq("/recruitment-listings"), eq(SystemUsers.SYSTEM_USER_ID), eq(List.of(22L)));
    }

    @Test
    void neverResendsEvenAfterRecipientHasConfirmed() {
        when(confirmableNotificationRepository.existsBySourceTypeAndSourceId("RECRUITMENT_PENALTY", 11L))
                .thenReturn(true);

        listener.onPenaltyApplied(new RecruitmentPenaltyAppliedNotificationEvent(
                11L, 22L, RecruitmentScopeType.TEAM, 33L, expiresAt));

        verifyNoInteractions(confirmableNotificationService);
    }

    @Test
    void deliveryFailureDoesNotChangeCommittedPenalty() {
        when(confirmableNotificationService.sendFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("credit unavailable"));

        assertThatCode(() -> listener.onPenaltyApplied(new RecruitmentPenaltyAppliedNotificationEvent(
                11L, 22L, RecruitmentScopeType.ORGANIZATION, 33L, expiresAt)))
                .doesNotThrowAnyException();
    }
}
