package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reflection.ReflectionReminderKind;
import com.mannschaft.app.reflection.ReflectionReminderStatus;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionSpacedReminderEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReflectionSpacedReminderService} 単体テスト（F06.5・§5）。
 *
 * <p>カバー AC: AC-9（保存で SPACED 1/3/7/14 生成）/ AC-11（ユーザー TZ で remind_at 生成）/
 * AC-12（PRE_EXAM 14/7/3/1＋過去日スキップ）/ AC-22（FORGOT 翌日 SPACED）/ AC-10（due は SENT 遷移・孤児 CANCEL）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionSpacedReminderService 単体テスト（間隔反復 §5）")
class ReflectionSpacedReminderServiceTest {

    @Mock private ReflectionSpacedReminderRepository reminderRepository;
    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private ReflectionSettingsService settingsService;
    @Mock private UserTimezoneCache userTimezoneCache;
    @Mock private NotificationHelper notificationHelper;

    @InjectMocks private ReflectionSpacedReminderService service;

    private static final Long USER_ID = 100L;

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionEntryEntity entry(LocalDate targetDate) {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(USER_ID).targetDate(targetDate)
                .structuredContent("{}").build();
        setId(e, UUID.randomUUID());
        return e;
    }

    private ReflectionThemeEntity theme(String intervals, LocalDate examDate) {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title("数学").recallIntervalDays(intervals).examDate(examDate).build();
        setId(t, UUID.randomUUID());
        return t;
    }

    @Test
    @DisplayName("AC-9/AC-11: SPACED は interval 分（1/3/7/14）生成され、remind_at はユーザー TZ ×時刻で算出")
    void generateSpacedReminders_createsRowsWithUserTz() {
        given(userTimezoneCache.getTimezone(USER_ID)).willReturn("Asia/Tokyo");
        given(settingsService.remindHour(USER_ID)).willReturn(8);
        ReflectionEntryEntity e = entry(LocalDate.of(2026, 6, 1));

        service.generateSpacedReminders(e, theme("1,3,7,14", null));

        ArgumentCaptor<ReflectionSpacedReminderEntity> captor =
                ArgumentCaptor.forClass(ReflectionSpacedReminderEntity.class);
        verify(reminderRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        List<ReflectionSpacedReminderEntity> saved = captor.getAllValues();
        assertThat(saved).allMatch(r -> r.getKind() == ReflectionReminderKind.SPACED);
        assertThat(saved).allMatch(r -> r.getStatus() == ReflectionReminderStatus.PENDING);
        assertThat(saved).extracting(ReflectionSpacedReminderEntity::getIntervalDays)
                .containsExactlyInAnyOrder(1, 3, 7, 14);
        // 1日後（6/2 08:00 JST）の remind_at を確認。
        assertThat(saved).anyMatch(r -> r.getRemindAt().equals(LocalDateTime.of(2026, 6, 2, 8, 0)));
    }

    @Test
    @DisplayName("AC-11: 非 JST ユーザーの remind_at は JST 正規化される（New York 8時 → JST 21時想定）")
    void generateSpacedReminders_nonJstNormalizedToJst() {
        given(userTimezoneCache.getTimezone(USER_ID)).willReturn("America/New_York");
        given(settingsService.remindHour(USER_ID)).willReturn(8);
        ReflectionEntryEntity e = entry(LocalDate.of(2026, 6, 1));

        service.generateSpacedReminders(e, theme("1", null));

        ArgumentCaptor<ReflectionSpacedReminderEntity> captor =
                ArgumentCaptor.forClass(ReflectionSpacedReminderEntity.class);
        verify(reminderRepository).save(captor.capture());
        // 6/2 08:00 America/New_York (EDT -4) = 6/2 12:00 UTC = 6/2 21:00 JST
        assertThat(captor.getValue().getRemindAt()).isEqualTo(LocalDateTime.of(2026, 6, 2, 21, 0));
    }

    @Test
    @DisplayName("AC-22: FORGOT で翌日（recall_date+1）の SPACED 行を 1 件追加生成")
    void scheduleNextDaySpacedReminder_createsNextDayRow() {
        given(userTimezoneCache.getTimezone(USER_ID)).willReturn("Asia/Tokyo");
        given(settingsService.remindHour(USER_ID)).willReturn(8);
        ReflectionEntryEntity e = entry(LocalDate.of(2026, 6, 1));

        service.scheduleNextDaySpacedReminder(e, LocalDate.of(2026, 6, 10));

        ArgumentCaptor<ReflectionSpacedReminderEntity> captor =
                ArgumentCaptor.forClass(ReflectionSpacedReminderEntity.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(ReflectionReminderKind.SPACED);
        assertThat(captor.getValue().getRemindAt()).isEqualTo(LocalDateTime.of(2026, 6, 11, 8, 0));
    }

    @Test
    @DisplayName("AC-12: exam_date 設定で PRE_EXAM が 14/7/3/1 日前に 4 件生成（全て未来）")
    void generatePreExamReminders_fourRowsWhenFuture() {
        given(userTimezoneCache.getTimezone(USER_ID)).willReturn("Asia/Tokyo");
        given(settingsService.remindHour(USER_ID)).willReturn(8);
        // exam_date を十分未来にして全 4 件が未来になるようにする。
        LocalDate examDate = LocalDate.now().plusDays(60);

        service.generatePreExamReminders(theme("1,3,7,14", examDate));

        ArgumentCaptor<ReflectionSpacedReminderEntity> captor =
                ArgumentCaptor.forClass(ReflectionSpacedReminderEntity.class);
        verify(reminderRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(r -> r.getKind() == ReflectionReminderKind.PRE_EXAM)
                .extracting(ReflectionSpacedReminderEntity::getIntervalDays)
                .containsExactlyInAnyOrder(14, 7, 3, 1);
    }

    @Test
    @DisplayName("AC-12: exam_date が過去日なら PRE_EXAM は 0 件（過去日ガード・§5.5）")
    void generatePreExamReminders_pastExamDate_noRows() {
        given(userTimezoneCache.getTimezone(USER_ID)).willReturn("Asia/Tokyo");
        given(settingsService.remindHour(USER_ID)).willReturn(8);
        LocalDate pastExam = LocalDate.now().minusDays(10);

        service.generatePreExamReminders(theme("1,3,7,14", pastExam));

        verify(reminderRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-10: due（PENDING）は通知送信後 SENT 遷移（二重送信防止）")
    void processDueReminders_sendsAndMarksSent() {
        ReflectionEntryEntity e = entry(LocalDate.of(2026, 6, 1));
        ReflectionThemeEntity t = theme("1,3,7,14", null);
        ReflectionSpacedReminderEntity r = ReflectionSpacedReminderEntity.builder()
                .entryId(e.getId()).userId(USER_ID)
                .remindAt(LocalDateTime.now().minusMinutes(1))
                .intervalDays(1).kind(ReflectionReminderKind.SPACED)
                .status(ReflectionReminderStatus.PENDING).build();
        setId(r, UUID.randomUUID());

        given(reminderRepository.findByStatusAndRemindAtLessThanEqual(eq(ReflectionReminderStatus.PENDING), any()))
                .willReturn(List.of(r));
        given(entryRepository.findById(e.getId())).willReturn(Optional.of(e));
        given(themeRepository.findById(e.getThemeId())).willReturn(Optional.of(t));

        service.processDueReminders();

        verify(notificationHelper).notify(eq(USER_ID), eq("REFLECTION_RECALL_REMINDER"),
                any(), any(), eq("REFLECTION"), eq(null),
                eq(NotificationScopeType.PERSONAL), eq(USER_ID), any(), eq(null));
        assertThat(r.getStatus()).isEqualTo(ReflectionReminderStatus.SENT);
        assertThat(r.getSentAt()).isNotNull();
        verify(reminderRepository).save(r);
    }

    @Test
    @DisplayName("AC-10: 親エントリ不在の due は孤児 fail-safe で CANCELLED（通知しない）")
    void processDueReminders_orphan_cancelled() {
        UUID missingEntryId = UUID.randomUUID();
        ReflectionSpacedReminderEntity r = ReflectionSpacedReminderEntity.builder()
                .entryId(missingEntryId).userId(USER_ID)
                .remindAt(LocalDateTime.now().minusMinutes(1))
                .intervalDays(1).kind(ReflectionReminderKind.SPACED)
                .status(ReflectionReminderStatus.PENDING).build();
        setId(r, UUID.randomUUID());

        given(reminderRepository.findByStatusAndRemindAtLessThanEqual(eq(ReflectionReminderStatus.PENDING), any()))
                .willReturn(List.of(r));
        given(entryRepository.findById(missingEntryId)).willReturn(Optional.empty());

        service.processDueReminders();

        assertThat(r.getStatus()).isEqualTo(ReflectionReminderStatus.CANCELLED);
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(), any(), any(),
                any(), anyLong(), any(), any());
        verify(reminderRepository).save(r);
    }
}
