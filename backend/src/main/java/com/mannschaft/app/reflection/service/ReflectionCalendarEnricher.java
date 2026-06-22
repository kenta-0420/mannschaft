package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.reflection.ReflectionReminderStatus;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionSpacedReminderEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.service.CalendarEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * reflection エントリのカレンダー印を横断カレンダーに合流する enricher（F06.5・§6.2 / AC-14）。
 *
 * <p>{@link com.mannschaft.app.schedule.service.ScheduleQueryService#getMyCalendar} の return 直前に
 * <b>独立 enrich パス</b>として呼ばれる（既存 schedule 合流の Long 経路は一切改変しない）。reflection 行は
 * UUID 主キーゆえ {@code CalendarEntryResponse.id=null} とし、識別は {@code content.referenceUuid}
 * （entry UUID 文字列）＋ {@code content.referenceKind="REFLECTION_ENTRY"} で行う（§6.2・AC-21 は FE 第五陣）。</p>
 *
 * <p><b>F00 漏洩防止</b>: 本人カレンダーでも対象 UUID 群を必ず
 * {@link ContentVisibilityChecker#filterAccessibleUuid}（{@link ReferenceType#REFLECTION_ENTRY} の
 * UUID 経路 Resolver 経由）に通してから合流する。Long 経路（SCHEDULE）と UUID 経路を混ぜない。</p>
 *
 * <p><b>マスク × カレンダー</b>: タイトルは theme タイトルを出してよい（§3.2 maskedHint と同基準）。
 * 本文（structured_content）はカレンダーに一切載せない。</p>
 *
 * <p><b>想起予定印（§6.2 / AC-14）</b>: {@code reflection_spaced_reminders} の PENDING 行を
 * remind_at の日付に出す。referenceKind は種別によって異なる:</p>
 * <ul>
 *   <li><b>SPACED（entry_id 基準）</b>: {@code referenceKind="REFLECTION_RECALL"}・
 *       {@code referenceUuid=entry_id}。FE は recall 画面（{@code /reflections/recall?entry=...}）へ遷移。
 *       親エントリを {@link ContentVisibilityChecker#filterAccessibleUuid} に通し、可視なもののみ出す。</li>
 *   <li><b>PRE_EXAM（entry_id=null・theme_id 基準）</b>: {@code referenceKind="REFLECTION_PRE_EXAM"}・
 *       {@code referenceUuid=theme_id}。FE はテーマ詳細画面（{@code /reflections/themes/...}）へ遷移。
 *       テーマ所有者本人のみ出す（themeTitleById に当該 theme_id が存在＝本人所有）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReflectionCalendarEnricher implements CalendarEnricher {

    private static final String EVENT_TYPE_ENTRY = "REFLECTION_ENTRY";
    private static final String EVENT_TYPE_RECALL = "REFLECTION_RECALL";
    private static final String EVENT_TYPE_PRE_EXAM = "REFLECTION_PRE_EXAM";
    private static final String SCOPE_TYPE_PERSONAL = "PERSONAL";

    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionSpacedReminderRepository reflectionSpacedReminderRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;

    @Override
    public List<CalendarEntryResponse> enrich(Long userId, LocalDateTime from, LocalDateTime to) {
        if (userId == null || from == null || to == null) {
            return List.of();
        }

        // theme タイトル解決（マスク中でもタイトルは出してよい・本文は載せない）。
        // このマップは「本人所有テーマ」のみを含むため、PRE_EXAM 想起予定の所有検証も兼ねる（§6.2）。
        Map<UUID, String> themeTitleById = new HashMap<>();
        for (ReflectionThemeEntity t : reflectionThemeRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            themeTitleById.put(t.getId(), t.getTitle());
        }

        List<CalendarEntryResponse> marks = new ArrayList<>();
        // ① エントリ印（target_date・REFLECTION_ENTRY）。
        marks.addAll(buildEntryMarks(userId, from, to, themeTitleById));
        // ② 想起予定印（remind_at の日付・REFLECTION_RECALL）。
        marks.addAll(buildRecallMarks(userId, from, to, themeTitleById));
        return marks;
    }

    /**
     * エントリ印（target_date・REFLECTION_ENTRY）を構築する（§6.2・AC-14）。
     * F00 UUID 経路フィルタ（REFLECTION_ENTRY）で可視なエントリのみ残す（漏洩防止）。
     */
    private List<CalendarEntryResponse> buildEntryMarks(
            Long userId, LocalDateTime from, LocalDateTime to, Map<UUID, String> themeTitleById) {
        LocalDate fromDate = from.toLocalDate();
        LocalDate toDate = to.toLocalDate();

        List<ReflectionEntryEntity> entries = reflectionEntryRepository
                .findByUserIdAndTargetDateBetween(userId, fromDate, toDate);
        if (entries.isEmpty()) {
            return List.of();
        }

        List<UUID> entryIds = entries.stream().map(ReflectionEntryEntity::getId).toList();
        Set<UUID> accessible = contentVisibilityChecker
                .filterAccessibleUuid(ReferenceType.REFLECTION_ENTRY, entryIds, userId);

        List<CalendarEntryResponse> marks = new ArrayList<>();
        for (ReflectionEntryEntity e : entries) {
            if (!accessible.contains(e.getId())) {
                continue;
            }
            String title = themeTitleById.get(e.getThemeId());
            marks.add(toEntryMark(e, title));
        }
        return marks;
    }

    /**
     * 想起予定印（remind_at の日付・REFLECTION_RECALL）を構築する（§6.2・AC-14）。
     *
     * <p>PENDING の想起予定を remind_at の日付に allDay で出す。漏洩防止:</p>
     * <ul>
     *   <li><b>SPACED（entry_id 基準）</b>: 親エントリ UUID を F00
     *       {@link ContentVisibilityChecker#filterAccessibleUuid}（{@link ReferenceType#REFLECTION_ENTRY}）に
     *       通し、可視なもののみ出す。referenceUuid = entry_id。</li>
     *   <li><b>PRE_EXAM（entry_id=null・theme_id 基準）</b>: 親エントリが無いため、テーマ所有者本人
     *       （themeTitleById に当該 theme_id が存在＝本人所有テーマ）のみ出す。referenceUuid = theme_id。</li>
     * </ul>
     */
    private List<CalendarEntryResponse> buildRecallMarks(
            Long userId, LocalDateTime from, LocalDateTime to, Map<UUID, String> themeTitleById) {
        List<ReflectionSpacedReminderEntity> reminders = reflectionSpacedReminderRepository
                .findByUserIdAndStatusAndRemindAtBetween(userId, ReflectionReminderStatus.PENDING, from, to);
        if (reminders.isEmpty()) {
            return List.of();
        }

        // SPACED 行の親エントリ UUID を F00 フィルタ（漏洩防止・§6.2）。
        List<UUID> spacedEntryIds = reminders.stream()
                .map(ReflectionSpacedReminderEntity::getEntryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Set<UUID> accessibleEntries = spacedEntryIds.isEmpty()
                ? Set.of()
                : contentVisibilityChecker
                        .filterAccessibleUuid(ReferenceType.REFLECTION_ENTRY, spacedEntryIds, userId);

        List<CalendarEntryResponse> marks = new ArrayList<>();
        for (ReflectionSpacedReminderEntity r : reminders) {
            UUID entryId = r.getEntryId();
            String themeTitle = themeTitleById.get(r.getThemeId());
            final String referenceUuid;
            final String referenceKind;
            if (entryId != null) {
                // SPACED: 親エントリが F00 可視なもののみ。recall 画面（entry_id 基準）へ遷移。
                if (!accessibleEntries.contains(entryId)) {
                    continue;
                }
                referenceUuid = entryId.toString();
                referenceKind = EVENT_TYPE_RECALL;
            } else {
                // PRE_EXAM: テーマ所有者本人のみ（themeTitleById に存在＝本人所有）。
                // FE はテーマ詳細画面へ遷移するため、referenceKind を REFLECTION_PRE_EXAM に設定。
                if (r.getThemeId() == null || !themeTitleById.containsKey(r.getThemeId())) {
                    continue;
                }
                referenceUuid = r.getThemeId().toString();
                referenceKind = EVENT_TYPE_PRE_EXAM;
            }
            marks.add(toRecallMark(r, themeTitle, referenceUuid, referenceKind));
        }
        return marks;
    }

    /**
     * reflection エントリをカレンダー印に変換する（id=null・referenceUuid 識別・allDay・PERSONAL）。
     */
    private CalendarEntryResponse toEntryMark(ReflectionEntryEntity entry, String themeTitle) {
        LocalDateTime startOfDay = entry.getTargetDate().atStartOfDay();
        return CalendarEntryResponse.builder()
                .id(null) // UUID 主キーゆえ Long id は持たない（§6.2・referenceUuid で識別）
                .content(new CalendarEntryResponse.CalendarContentDto(
                        themeTitle,               // title = theme タイトル（本文は載せない）
                        EVENT_TYPE_ENTRY,         // eventType
                        null,                     // status（reflection 行は無し）
                        entry.getId().toString(), // referenceUuid
                        EVENT_TYPE_ENTRY))        // referenceKind
                .time(new CalendarEntryResponse.CalendarTimeDto(startOfDay, startOfDay, Boolean.TRUE))
                .scope(new CalendarEntryResponse.CalendarScopeDto(SCOPE_TYPE_PERSONAL, null, null, null))
                .myAttendanceStatus(null)
                .build();
    }

    /**
     * 想起予定をカレンダー印に変換する（id=null・referenceUuid 識別・remind_at 日付 allDay・PERSONAL）。
     * 本文は載せない（タイトル＝テーマタイトルのみ）。
     *
     * @param referenceKind SPACED の場合は {@code REFLECTION_RECALL}、
     *                      PRE_EXAM の場合は {@code REFLECTION_PRE_EXAM}。
     *                      FE はこの値でクリック時の遷移先を切り替える。
     */
    private CalendarEntryResponse toRecallMark(
            ReflectionSpacedReminderEntity reminder, String themeTitle, String referenceUuid, String referenceKind) {
        LocalDateTime startOfDay = reminder.getRemindAt().toLocalDate().atStartOfDay();
        return CalendarEntryResponse.builder()
                .id(null) // UUID 主キードメインゆえ Long id は持たない（§6.2・referenceUuid で識別）
                .content(new CalendarEntryResponse.CalendarContentDto(
                        themeTitle,     // title = theme タイトル（本文は載せない）
                        referenceKind,  // eventType（REFLECTION_RECALL / REFLECTION_PRE_EXAM）
                        null,           // status
                        referenceUuid,  // referenceUuid（SPACED=entry_id / PRE_EXAM=theme_id）
                        referenceKind)) // referenceKind（eventType と同値）
                .time(new CalendarEntryResponse.CalendarTimeDto(startOfDay, startOfDay, Boolean.TRUE))
                .scope(new CalendarEntryResponse.CalendarScopeDto(SCOPE_TYPE_PERSONAL, null, null, null))
                .myAttendanceStatus(null)
                .build();
    }
}
