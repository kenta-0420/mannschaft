package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReflectionCalendarEnricher implements CalendarEnricher {

    private static final String EVENT_TYPE_ENTRY = "REFLECTION_ENTRY";
    private static final String SCOPE_TYPE_PERSONAL = "PERSONAL";

    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;

    @Override
    public List<CalendarEntryResponse> enrich(Long userId, LocalDateTime from, LocalDateTime to) {
        if (userId == null || from == null || to == null) {
            return List.of();
        }
        LocalDate fromDate = from.toLocalDate();
        LocalDate toDate = to.toLocalDate();

        List<ReflectionEntryEntity> entries = reflectionEntryRepository
                .findByUserIdAndTargetDateBetween(userId, fromDate, toDate);
        if (entries.isEmpty()) {
            return List.of();
        }

        // F00 UUID 経路フィルタ（REFLECTION_ENTRY）で可視なエントリのみ残す（漏洩防止・§6.2）。
        List<UUID> entryIds = entries.stream().map(ReflectionEntryEntity::getId).toList();
        Set<UUID> accessible = contentVisibilityChecker
                .filterAccessibleUuid(ReferenceType.REFLECTION_ENTRY, entryIds, userId);

        // theme タイトル解決（マスク中でもタイトルは出してよい・本文は載せない）。
        Map<UUID, String> themeTitleById = new HashMap<>();
        for (ReflectionThemeEntity t : reflectionThemeRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            themeTitleById.put(t.getId(), t.getTitle());
        }

        List<CalendarEntryResponse> marks = new ArrayList<>();
        for (ReflectionEntryEntity e : entries) {
            if (!accessible.contains(e.getId())) {
                continue;
            }
            String title = themeTitleById.get(e.getThemeId());
            marks.add(toCalendarMark(e, title));
        }
        return marks;
    }

    /**
     * reflection エントリをカレンダー印に変換する（id=null・referenceUuid 識別・allDay・PERSONAL）。
     */
    private CalendarEntryResponse toCalendarMark(ReflectionEntryEntity entry, String themeTitle) {
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
}
