package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.reflection.ReflectionReminderKind;
import com.mannschaft.app.reflection.ReflectionReminderStatus;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionSpacedReminderEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReflectionCalendarEnricher} 単体テスト（F06.5・§6.2 / AC-14）。
 *
 * <p>カバー: reflection 印が from..to で合流 / id=null＋referenceUuid/referenceKind 付与 /
 * マスク中でも title（theme タイトル）は出るが本文（structured_content）は載せない /
 * F00 UUID 経路フィルタ（filterAccessibleUuid）で非可視（他者分）が出ない / allDay=true・PERSONAL。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionCalendarEnricher 単体テスト")
class ReflectionCalendarEnricherTest {

    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private ReflectionSpacedReminderRepository reminderRepository;
    @Mock private ContentVisibilityChecker visibilityChecker;

    @InjectMocks private ReflectionCalendarEnricher enricher;

    private static final Long USER_ID = 100L;
    private static final LocalDateTime FROM = LocalDate.now().minusDays(5).atStartOfDay();
    private static final LocalDateTime TO = LocalDate.now().plusDays(5).atTime(23, 59);

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionThemeEntity theme(UUID id, String title) {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title(title).recallIntervalDays("1,3,7,14").build();
        setId(t, id);
        return t;
    }

    private ReflectionEntryEntity entry(UUID id, UUID themeId, LocalDate targetDate) {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(themeId).userId(USER_ID).targetDate(targetDate)
                .structuredContent("{\"main_theme\":\"秘密\"}").build();
        setId(e, id);
        return e;
    }

    /** SPACED（entry_id 基準）の PENDING 想起予定。 */
    private ReflectionSpacedReminderEntity spacedReminder(UUID entryId, UUID themeId, LocalDateTime remindAt) {
        return ReflectionSpacedReminderEntity.builder()
                .entryId(entryId).themeId(themeId).userId(USER_ID).remindAt(remindAt)
                .kind(ReflectionReminderKind.SPACED).status(ReflectionReminderStatus.PENDING).build();
    }

    /** PRE_EXAM（entry_id=null・theme_id 基準）の PENDING 想起予定。 */
    private ReflectionSpacedReminderEntity preExamReminder(UUID themeId, LocalDateTime remindAt) {
        return ReflectionSpacedReminderEntity.builder()
                .entryId(null).themeId(themeId).userId(USER_ID).remindAt(remindAt)
                .kind(ReflectionReminderKind.PRE_EXAM).status(ReflectionReminderStatus.PENDING).build();
    }

    @Test
    @DisplayName("AC-14: reflection エントリ印が id=null＋referenceUuid/referenceKind=REFLECTION_ENTRY で合流（allDay/PERSONAL）")
    void enrich_addsReflectionEntryMark() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        ReflectionEntryEntity e = entry(entryId, themeId, LocalDate.now());
        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of(e));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "数学II")));
        given(visibilityChecker.filterAccessibleUuid(eq(ReferenceType.REFLECTION_ENTRY), any(), eq(USER_ID)))
                .willReturn(Set.of(entryId));

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).hasSize(1);
        CalendarEntryResponse cal = result.get(0);
        assertThat(cal.getId()).isNull();
        assertThat(cal.getContent().referenceUuid()).isEqualTo(entryId.toString());
        assertThat(cal.getContent().referenceKind()).isEqualTo("REFLECTION_ENTRY");
        assertThat(cal.getContent().eventType()).isEqualTo("REFLECTION_ENTRY");
        // タイトルは theme タイトル（本文は載せない）。
        assertThat(cal.getContent().title()).isEqualTo("数学II");
        assertThat(cal.getTime().allDay()).isTrue();
        assertThat(cal.getScope().scopeType()).isEqualTo("PERSONAL");
    }

    @Test
    @DisplayName("AC-8/AC-14: マスク中でも title は出るが本文 structured_content はカレンダーに載らない")
    void enrich_doesNotLeakBody() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of(entry(entryId, themeId, LocalDate.now())));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "テーマ名のみ")));
        given(visibilityChecker.filterAccessibleUuid(eq(ReferenceType.REFLECTION_ENTRY), any(), eq(USER_ID)))
                .willReturn(Set.of(entryId));

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        CalendarEntryResponse cal = result.get(0);
        assertThat(cal.getContent().title()).isEqualTo("テーマ名のみ");
        // 本文（structured_content の "秘密"）がどのフィールドにも載らない。
        assertThat(cal.getContent().status()).isNull();
        assertThat(cal.getContent().title()).doesNotContain("秘密");
    }

    @Test
    @DisplayName("F00: filterAccessibleUuid で非可視のエントリ（他者分）は合流しない")
    void enrich_filtersInaccessible() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of(entry(entryId, themeId, LocalDate.now())));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "数学")));
        // フィルタが空集合 = 非可視
        given(visibilityChecker.filterAccessibleUuid(eq(ReferenceType.REFLECTION_ENTRY), any(), eq(USER_ID)))
                .willReturn(Set.of());

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("対象期間に reflection 行が無ければ空（既存 schedule 合流を増やさない）")
    void enrich_empty_returnsEmpty() {
        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of());

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AC-14/§6.2: SPACED 想起予定が REFLECTION_RECALL 印（id=null・referenceUuid=entry_id・remind_at 日付 allDay・本文非搭載）で出る")
    void enrich_addsSpacedRecallMark() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime remindAt = LocalDate.now().plusDays(3).atTime(9, 0);

        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "数学II")));
        given(reminderRepository.findByUserIdAndStatusAndRemindAtBetween(
                eq(USER_ID), eq(ReflectionReminderStatus.PENDING), any(), any()))
                .willReturn(List.of(spacedReminder(entryId, themeId, remindAt)));
        given(visibilityChecker.filterAccessibleUuid(eq(ReferenceType.REFLECTION_ENTRY), any(), eq(USER_ID)))
                .willReturn(Set.of(entryId));

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).hasSize(1);
        CalendarEntryResponse cal = result.get(0);
        assertThat(cal.getId()).isNull();
        assertThat(cal.getContent().eventType()).isEqualTo("REFLECTION_RECALL");
        assertThat(cal.getContent().referenceKind()).isEqualTo("REFLECTION_RECALL");
        assertThat(cal.getContent().referenceUuid()).isEqualTo(entryId.toString());
        assertThat(cal.getContent().title()).isEqualTo("数学II");
        // 本文は載せない。
        assertThat(cal.getContent().status()).isNull();
        assertThat(cal.getContent().title()).doesNotContain("秘密");
        // remind_at の日付の atStartOfDay()・allDay。
        assertThat(cal.getTime().startAt()).isEqualTo(remindAt.toLocalDate().atStartOfDay());
        assertThat(cal.getTime().allDay()).isTrue();
        assertThat(cal.getScope().scopeType()).isEqualTo("PERSONAL");
    }

    @Test
    @DisplayName("§6.2: PRE_EXAM 想起予定（entry_id=null）はテーマ所有者本人なら referenceUuid=theme_id で出る")
    void enrich_addsPreExamRecallMark() {
        UUID themeId = UUID.randomUUID();
        LocalDateTime remindAt = LocalDate.now().plusDays(1).atTime(9, 0);

        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "英語表現")));
        given(reminderRepository.findByUserIdAndStatusAndRemindAtBetween(
                eq(USER_ID), eq(ReflectionReminderStatus.PENDING), any(), any()))
                .willReturn(List.of(preExamReminder(themeId, remindAt)));

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).hasSize(1);
        CalendarEntryResponse cal = result.get(0);
        assertThat(cal.getContent().eventType()).isEqualTo("REFLECTION_RECALL");
        assertThat(cal.getContent().referenceUuid()).isEqualTo(themeId.toString());
        assertThat(cal.getContent().title()).isEqualTo("英語表現");
    }

    @Test
    @DisplayName("F00: SPACED 想起予定の親エントリが非可視なら想起予定印も出ない（漏洩防止）")
    void enrich_filtersInaccessibleSpacedRecall() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime remindAt = LocalDate.now().plusDays(2).atTime(9, 0);

        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "数学")));
        given(reminderRepository.findByUserIdAndStatusAndRemindAtBetween(
                eq(USER_ID), eq(ReflectionReminderStatus.PENDING), any(), any()))
                .willReturn(List.of(spacedReminder(entryId, themeId, remindAt)));
        // 親エントリが F00 非可視。
        given(visibilityChecker.filterAccessibleUuid(eq(ReferenceType.REFLECTION_ENTRY), any(), eq(USER_ID)))
                .willReturn(Set.of());

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("§6.2: PRE_EXAM 想起予定でも所有していないテーマ（themeTitleById に無い）は出ない")
    void enrich_filtersUnownedPreExamRecall() {
        UUID ownedThemeId = UUID.randomUUID();
        UUID otherThemeId = UUID.randomUUID();
        LocalDateTime remindAt = LocalDate.now().plusDays(1).atTime(9, 0);

        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        // 本人所有テーマには otherThemeId が含まれない。
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(ownedThemeId, "自分のテーマ")));
        given(reminderRepository.findByUserIdAndStatusAndRemindAtBetween(
                eq(USER_ID), eq(ReflectionReminderStatus.PENDING), any(), any()))
                .willReturn(List.of(preExamReminder(otherThemeId, remindAt)));

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("§6.2: エントリ印と想起予定印の両方が合流する（片方だけにしない）")
    void enrich_returnsBothEntryAndRecallMarks() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID recallEntryId = UUID.randomUUID();
        LocalDateTime remindAt = LocalDate.now().plusDays(3).atTime(9, 0);

        given(entryRepository.findByUserIdAndTargetDateBetween(eq(USER_ID), any(), any()))
                .willReturn(List.of(entry(entryId, themeId, LocalDate.now())));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "数学II")));
        given(reminderRepository.findByUserIdAndStatusAndRemindAtBetween(
                eq(USER_ID), eq(ReflectionReminderStatus.PENDING), any(), any()))
                .willReturn(List.of(spacedReminder(recallEntryId, themeId, remindAt)));
        given(visibilityChecker.filterAccessibleUuid(eq(ReferenceType.REFLECTION_ENTRY), any(), eq(USER_ID)))
                .willReturn(Set.of(entryId, recallEntryId));

        List<CalendarEntryResponse> result = enricher.enrich(USER_ID, FROM, TO);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(c -> c.getContent().eventType())
                .containsExactlyInAnyOrder("REFLECTION_ENTRY", "REFLECTION_RECALL");
    }
}
