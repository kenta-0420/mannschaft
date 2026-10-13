package com.mannschaft.app.repairplan.batch;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamMemberTermReminderBatch} の単体テスト（Issue #2997 G8）。
 *
 * <p>業務の書き込みを持たない通知バッチなので、検証するのは「1 人分の通知失敗が他の理事への通知と
 * 監査記録を巻き込まない」ことと、通知件数の集計が実態（成功件数）と一致することである。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TeamMemberTermReminderBatch 単体テスト")
class TeamMemberTermReminderBatchTest {

    @Mock private TeamMemberTermRepository termRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;
    @Mock private MessageSource messageSource;
    @Mock private UserLocaleCache userLocaleCache;

    @InjectMocks private TeamMemberTermReminderBatch batch;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @BeforeEach
    void stubMessageSource() {
        given(messageSource.getMessage(anyString(), any(), anyString(), any()))
                .willAnswer(inv -> inv.getArgument(2));
    }

    private TeamMemberTerm term(Long userId, Long scopeId) {
        return TeamMemberTerm.builder()
                .organizationId(1L).scopeType("TEAM").scopeId(scopeId).userId(userId)
                .roleLabel("理事")
                .termStart(TODAY.minusYears(1)).termEnd(TODAY.plusDays(10))
                .isActive(true).build();
    }

    @Test
    @DisplayName("Issue #2997 G8 AC-B2: 1 人の createNotification が例外でも、他の理事へ通知され、監査記録も残る")
    void oneRecipientFailure_doesNotAffectOthersNorAudit() {
        given(termRepository.findByIsActiveTrueAndTermEndBetween(any(), any()))
                .willReturn(List.of(term(1L, 10L), term(2L, 20L)));
        given(notificationService.createNotification(
                eq(1L), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new RuntimeException("通知の永続化失敗（模擬）"));

        batch.executeAt(TODAY);

        verify(notificationService, times(1)).createNotification(
                eq(2L), eq("TERM_ENDING_REMINDER"), any(), any(), any(), eq("REPAIR_PLAN"),
                eq(20L), any(), eq(20L), eq("/teams/20/repair-plan/handover-packs"), eq(null));
        verify(auditLogService, times(1)).record(
                eq("TEAM_MEMBER_TERM_REMINDER_BATCH"), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null),
                eq("{\"targets\":2,\"notified\":1,\"today\":\"2026-10-01\"}"));
    }
}
