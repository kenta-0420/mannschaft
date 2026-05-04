package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteAttachmentRepository;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteRepository;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F03.15 Phase 3 ダッシュボード集約サービス。
 *
 * <p>個人ダッシュボードに表示する「今日の時間割」を、所属チーム時間割と個人時間割を
 * マージして時刻順で返す。Phase 3 ではチームリンク連動による臨時変更反映は未実装のため、
 * {@code is_changed} は常に false / {@code change} は常に null。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetableDashboardService {

    private static final List<String> WEEK_DOWS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final PersonalTimetableRepository personalTimetableRepository;
    private final PersonalTimetableSlotRepository personalSlotRepository;
    private final PersonalTimetablePeriodRepository personalPeriodRepository;
    private final TimetableRepository teamTimetableRepository;
    private final TimetableSlotRepository teamSlotRepository;
    private final TimetableSlotUserNoteRepository userNoteRepository;
    private final TimetableSlotUserNoteAttachmentRepository attachmentRepository;
    private final UserRoleRepository userRoleRepository;
    private final TimetableChangeRepository timetableChangeRepository;

    /**
     * 「今日の時間割」を取得する。
     *
     * @param userId   対象ユーザー
     * @param today    今日の日付（テスト容易性のため引数化）
     */
    public DashboardTimetableTodayResponse getTimetableToday(Long userId, LocalDate today) {
        String todayDow = WEEK_DOWS.get(today.getDayOfWeek().getValue() - 1);

        List<DashboardTimetableTodayResponse.TimetableTodayItem> items = new ArrayList<>();
        WeekPattern firstActivePattern = WeekPattern.EVERY;

        // ---- PERSONAL: 自分のACTIVE個人時間割の今日のコマ ----
        List<PersonalTimetableEntity> activePersonals = personalTimetableRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, PersonalTimetableStatus.ACTIVE);
        for (PersonalTimetableEntity personal : activePersonals) {
            // 適用期間内チェック
            if (personal.getEffectiveFrom() != null && today.isBefore(personal.getEffectiveFrom())) continue;
            if (personal.getEffectiveUntil() != null && today.isAfter(personal.getEffectiveUntil())) continue;

            WeekPattern current = resolveWeekPattern(personal, today);
            if (firstActivePattern == WeekPattern.EVERY && current != WeekPattern.EVERY) {
                firstActivePattern = current;
            }

            List<PersonalTimetableSlotEntity> slots = personalSlotRepository
                    .findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                            personal.getId(), todayDow);
            Map<Integer, PersonalTimetablePeriodEntity> periodMap = personalPeriodRepository
                    .findByPersonalTimetableIdOrderByPeriodNumberAsc(personal.getId())
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            PersonalTimetablePeriodEntity::getPeriodNumber, p -> p, (a, b) -> a));

            for (PersonalTimetableSlotEntity slot : slots) {
                if (slot.getWeekPattern() != WeekPattern.EVERY && slot.getWeekPattern() != current) {
                    continue;
                }
                PersonalTimetablePeriodEntity period = periodMap.get(slot.getPeriodNumber());

                items.add(buildPersonalItem(userId, personal, slot, period, today));
            }
        }

        // ---- TEAM: 所属チームの ACTIVE 時間割の今日のコマ ----
        // ユーザーが所属する team_id 一覧を user_roles から取得
        List<Long> joinedTeamIds = listJoinedTeamIds(userId);
        for (Long teamId : joinedTeamIds) {
            List<TimetableEntity> active = teamTimetableRepository.findEffective(teamId, today);
            for (TimetableEntity timetable : active) {
                List<TimetableSlotEntity> slots = teamSlotRepository
                        .findByTimetableIdAndDayOfWeek(timetable.getId(), todayDow);
                for (TimetableSlotEntity slot : slots) {
                    items.add(buildTeamItem(userId, teamId, timetable, slot, today));
                }
            }
        }

        // 開始時刻ソート（null は末尾）
        items.sort(Comparator.comparing(
                (DashboardTimetableTodayResponse.TimetableTodayItem i) -> i.startTime(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new DashboardTimetableTodayResponse(today, firstActivePattern.name(), items);
    }

    /**
     * 所属チーム ID 一覧を取得する（teamId が NULL でないもの）。
     */
    private List<Long> listJoinedTeamIds(Long userId) {
        return userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId).stream()
                .map(ur -> ur.getTeamId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private DashboardTimetableTodayResponse.TimetableTodayItem buildPersonalItem(
            Long userId,
            PersonalTimetableEntity personal,
            PersonalTimetableSlotEntity slot,
            PersonalTimetablePeriodEntity period,
            LocalDate today) {
        Long noteId = findNoteId(userId, TimetableSlotKind.PERSONAL, slot.getId(), today);
        boolean hasAttachments = noteId != null
                && attachmentRepository.countByNoteIdAndDeletedAtIsNull(noteId) > 0;
        LocalTime startTime = period != null ? period.getStartTime() : null;
        LocalTime endTime = period != null ? period.getEndTime() : null;
        String periodLabel = period != null ? period.getLabel() : null;

        // F03.15 Phase 4: linked_timetable_id 経由で当日の臨時変更を引く
        Boolean isChanged = Boolean.FALSE;
        Object change = null;
        if (slot.getLinkedTimetableId() != null) {
            List<TimetableChangeEntity> changes = timetableChangeRepository
                    .findByTimetableIdAndTargetDateOrderByPeriodNumber(slot.getLinkedTimetableId(), today);
            // DAY_OFF 優先
            TimetableChangeEntity dayOff = changes.stream()
                    .filter(c -> c.getChangeType() == TimetableChangeType.DAY_OFF)
                    .findFirst()
                    .orElse(null);
            TimetableChangeEntity periodMatch = changes.stream()
                    .filter(c -> c.getPeriodNumber() != null
                            && c.getPeriodNumber().equals(slot.getPeriodNumber()))
                    .findFirst()
                    .orElse(null);
            TimetableChangeEntity effective = dayOff != null ? dayOff : periodMatch;
            if (effective != null) {
                isChanged = Boolean.TRUE;
                change = buildChangeMap(effective);
            }
        }

        // F03.15 Phase 4: linked_team_id がセットされているのに自分が現在 MEMBER でない場合は link_revoked
        Boolean linkRevoked = Boolean.FALSE;
        if (slot.getLinkedTeamId() != null
                && !userRoleRepository.existsByUserIdAndTeamId(userId, slot.getLinkedTeamId())) {
            linkRevoked = Boolean.TRUE;
        }

        return new DashboardTimetableTodayResponse.TimetableTodayItem(
                "PERSONAL",
                null, null,
                personal.getId(),
                null,
                slot.getId(),
                periodLabel,
                slot.getPeriodNumber(),
                startTime,
                endTime,
                slot.getSubjectName(),
                slot.getCourseCode(),
                slot.getTeacherName(),
                slot.getRoomName(),
                slot.getCredits(),
                slot.getColor(),
                slot.getLinkedTeamId(),
                isChanged,
                change,
                linkRevoked,
                noteId,
                hasAttachments
        );
    }

    private Map<String, Object> buildChangeMap(TimetableChangeEntity change) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change_type", change.getChangeType().name());
        m.put("reason", change.getReason());
        m.put("subject_name", change.getSubjectName());
        m.put("teacher_name", change.getTeacherName());
        m.put("room_name", change.getRoomName());
        return m;
    }

    private DashboardTimetableTodayResponse.TimetableTodayItem buildTeamItem(
            Long userId,
            Long teamId,
            TimetableEntity timetable,
            TimetableSlotEntity slot,
            LocalDate today) {
        Long noteId = findNoteId(userId, TimetableSlotKind.TEAM, slot.getId(), today);
        boolean hasAttachments = noteId != null
                && attachmentRepository.countByNoteIdAndDeletedAtIsNull(noteId) > 0;
        return new DashboardTimetableTodayResponse.TimetableTodayItem(
                "TEAM",
                teamId,
                timetable.getName(),
                null,
                timetable.getId(),
                slot.getId(),
                null,
                slot.getPeriodNumber(),
                null,
                null,
                slot.getSubjectName(),
                null,
                slot.getTeacherName(),
                slot.getRoomName(),
                null,
                slot.getColor(),
                null,
                Boolean.FALSE,
                null,
                Boolean.FALSE,
                noteId,
                hasAttachments
        );
    }

    /**
     * 該当スロットのメモ ID を返す（target_date 限定 → 常設 の優先順）。
     */
    private Long findNoteId(Long userId, TimetableSlotKind kind, Long slotId, LocalDate today) {
        return userNoteRepository
                .findByUserIdAndSlotKindAndSlotIdAndTargetDate(userId, kind, slotId, today)
                .map(TimetableSlotUserNoteEntity::getId)
                .or(() -> userNoteRepository
                        .findByUserIdAndSlotKindAndSlotIdAndTargetDateIsNull(userId, kind, slotId)
                        .map(TimetableSlotUserNoteEntity::getId))
                .orElse(null);
    }

    /**
     * A/B 週パターン解決（PersonalTimetableSlotService と同ロジック）。
     */
    private WeekPattern resolveWeekPattern(PersonalTimetableEntity tt, LocalDate date) {
        if (!Boolean.TRUE.equals(tt.getWeekPatternEnabled())
                || tt.getWeekPatternBaseDate() == null) {
            return WeekPattern.EVERY;
        }
        long weeksBetween = ChronoUnit.WEEKS.between(
                tt.getWeekPatternBaseDate().with(DayOfWeek.MONDAY),
                date.with(DayOfWeek.MONDAY));
        return (Math.floorMod(weeksBetween, 2) == 0) ? WeekPattern.A : WeekPattern.B;
    }
}
