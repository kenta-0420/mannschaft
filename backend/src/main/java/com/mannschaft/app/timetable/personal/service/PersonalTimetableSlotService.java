package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.dto.PersonalWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F03.15 Phase 2 個人時間割のコマサービス。
 *
 * <p>Phase 2 ではリンク機能（linked_team_id 等）は受け取らず 400 で拒否する（Phase 4 で対応）。
 * 編集は親 {@code personal_timetables.status = DRAFT} 時のみ許可する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetableSlotService {

    /** 1個人時間割あたりのコマ上限（設計書 §3）。 */
    public static final int MAX_SLOTS_PER_TIMETABLE = 100;

    private static final List<String> WEEK_DOWS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final PersonalTimetableRepository timetableRepository;
    private final PersonalTimetableSlotRepository slotRepository;
    private final PersonalTimetablePeriodRepository periodRepository;

    /**
     * 自分の個人時間割のコマを取得する。所有者検証込み（404 統一）。
     *
     * @param dayOfWeek 指定時はその曜日のみ、null/空時は全曜日
     */
    public List<PersonalTimetableSlotEntity> list(
            Long personalTimetableId, Long userId, String dayOfWeek) {
        ensureOwned(personalTimetableId, userId);
        if (dayOfWeek != null && !dayOfWeek.isBlank()) {
            return slotRepository.findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                    personalTimetableId, dayOfWeek);
        }
        return slotRepository
                .findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(personalTimetableId);
    }

    /**
     * 今日のコマを取得する（A/B 週パターン適用済み）。Phase 2 では臨時変更反映は未実装。
     */
    public List<PersonalTimetableSlotEntity> listToday(Long personalTimetableId, Long userId) {
        PersonalTimetableEntity timetable = ensureOwned(personalTimetableId, userId);
        LocalDate today = LocalDate.now();
        String todayDow = today.getDayOfWeek().name().substring(0, 3);

        List<PersonalTimetableSlotEntity> slots =
                slotRepository.findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                        personalTimetableId, todayDow);

        WeekPattern current = resolveWeekPattern(timetable, today);
        return filterByWeekPattern(slots, current);
    }

    /**
     * コマを一括更新（全置換）する。{@code dayOfWeek} 指定時はその曜日のみ。
     *
     * <p>Phase 2 ではリンク列が入った要素は 400 拒否する（Phase 4 で本実装）。</p>
     */
    @Transactional
    public List<PersonalTimetableSlotEntity> replaceAll(
            Long personalTimetableId, Long userId, String dayOfWeek, List<SlotData> data) {
        PersonalTimetableEntity timetable = ensureOwned(personalTimetableId, userId);
        if (!timetable.isDraft()) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_EDITABLE);
        }

        // リンク機能は Phase 4 で実装。指定された場合 400 拒否。
        for (SlotData d : data) {
            if (d.linkedTeamId() != null
                    || d.linkedTimetableId() != null
                    || d.linkedSlotId() != null) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_NOT_SUPPORTED_YET);
            }
        }

        // 上限チェックは「置換後の総コマ数」で行う
        long futureTotal;
        if (dayOfWeek != null && !dayOfWeek.isBlank()) {
            // 全件 - 当該曜日 + 新規
            long currentTotal = slotRepository.countByPersonalTimetableId(personalTimetableId);
            long currentDay = slotRepository
                    .findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                            personalTimetableId, dayOfWeek).size();
            futureTotal = currentTotal - currentDay + data.size();
        } else {
            futureTotal = data.size();
        }
        if (futureTotal > MAX_SLOTS_PER_TIMETABLE) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_LIMIT_EXCEEDED);
        }

        // 時限の存在＆is_break チェック
        Map<Integer, PersonalTimetablePeriodEntity> periodByNumber = periodRepository
                .findByPersonalTimetableIdOrderByPeriodNumberAsc(personalTimetableId)
                .stream()
                .collect(Collectors.toMap(
                        PersonalTimetablePeriodEntity::getPeriodNumber, p -> p, (a, b) -> a));

        validateSlots(data, dayOfWeek, timetable, periodByNumber);

        if (dayOfWeek != null && !dayOfWeek.isBlank()) {
            slotRepository.deleteByPersonalTimetableIdAndDayOfWeek(personalTimetableId, dayOfWeek);
        } else {
            slotRepository.deleteByPersonalTimetableId(personalTimetableId);
        }
        slotRepository.flush();

        if (data.isEmpty()) {
            log.info("個人時間割のコマを全削除しました: pid={}, day={}, userId={}",
                    personalTimetableId, dayOfWeek, userId);
            return List.of();
        }

        List<PersonalTimetableSlotEntity> entities = data.stream()
                .map(d -> PersonalTimetableSlotEntity.builder()
                        .personalTimetableId(personalTimetableId)
                        .dayOfWeek(d.dayOfWeek())
                        .periodNumber(d.periodNumber())
                        .weekPattern(d.weekPattern() != null ? d.weekPattern() : WeekPattern.EVERY)
                        .subjectName(d.subjectName())
                        .courseCode(d.courseCode())
                        .teacherName(d.teacherName())
                        .roomName(d.roomName())
                        .credits(d.credits())
                        .color(d.color())
                        .autoSyncChanges(
                                d.autoSyncChanges() != null ? d.autoSyncChanges() : Boolean.TRUE)
                        .notes(d.notes())
                        .build())
                .toList();

        List<PersonalTimetableSlotEntity> saved = slotRepository.saveAll(entities);
        log.info("個人時間割のコマを {} 件で置換しました: pid={}, day={}, userId={}",
                saved.size(), personalTimetableId, dayOfWeek, userId);
        return saved;
    }

    /**
     * 週間ビューを取得する。{@code weekOf} の週（月〜日）について、各日付のコマを返す。
     */
    public PersonalWeeklyViewResponse getWeeklyView(
            Long personalTimetableId, Long userId, LocalDate weekOf) {
        PersonalTimetableEntity timetable = ensureOwned(personalTimetableId, userId);
        LocalDate weekStart = weekOf.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        List<PersonalTimetableSlotEntity> allSlots = slotRepository
                .findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(personalTimetableId);
        List<PersonalTimetablePeriodEntity> periods = periodRepository
                .findByPersonalTimetableIdOrderByPeriodNumberAsc(personalTimetableId);

        WeekPattern current = resolveWeekPattern(timetable, weekStart);

        Map<String, PersonalWeeklyViewResponse.WeeklyDayInfo> days = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            String dow = WEEK_DOWS.get(i);
            WeekPattern dayPattern = resolveWeekPattern(timetable, date);

            List<PersonalWeeklyViewResponse.WeeklySlotInfo> daySlots = allSlots.stream()
                    .filter(s -> dow.equals(s.getDayOfWeek()))
                    .filter(s -> s.getWeekPattern() == WeekPattern.EVERY
                            || s.getWeekPattern() == dayPattern)
                    .map(s -> new PersonalWeeklyViewResponse.WeeklySlotInfo(
                            s.getId(),
                            s.getPeriodNumber(),
                            s.getWeekPattern() != null ? s.getWeekPattern().name() : null,
                            s.getSubjectName(),
                            s.getCourseCode(),
                            s.getTeacherName(),
                            s.getRoomName(),
                            s.getColor(),
                            s.getNotes()))
                    .toList();

            days.put(dow, new PersonalWeeklyViewResponse.WeeklyDayInfo(date, daySlots));
        }

        return new PersonalWeeklyViewResponse(
                personalTimetableId,
                timetable.getName(),
                weekStart,
                weekEnd,
                Boolean.TRUE.equals(timetable.getWeekPatternEnabled()),
                current.name(),
                periods.stream()
                        .map(p -> new com.mannschaft.app.timetable.personal.dto
                                .PersonalTimetablePeriodResponse(
                                p.getId(),
                                p.getPeriodNumber(),
                                p.getLabel(),
                                p.getStartTime(),
                                p.getEndTime(),
                                p.getIsBreak()))
                        .toList(),
                days);
    }

    // ---- Helpers ----

    /**
     * A/B 週パターン判定。base_date と date の月曜基準の経過週数で判定する。
     * 偶数週 = A、奇数週 = B。{@code week_pattern_enabled = false} なら EVERY。
     */
    public WeekPattern resolveWeekPattern(PersonalTimetableEntity timetable, LocalDate date) {
        if (!Boolean.TRUE.equals(timetable.getWeekPatternEnabled())
                || timetable.getWeekPatternBaseDate() == null) {
            return WeekPattern.EVERY;
        }
        long weeksBetween = ChronoUnit.WEEKS.between(
                timetable.getWeekPatternBaseDate().with(DayOfWeek.MONDAY),
                date.with(DayOfWeek.MONDAY));
        return (Math.floorMod(weeksBetween, 2) == 0) ? WeekPattern.A : WeekPattern.B;
    }

    private List<PersonalTimetableSlotEntity> filterByWeekPattern(
            List<PersonalTimetableSlotEntity> slots, WeekPattern current) {
        return slots.stream()
                .filter(s -> s.getWeekPattern() == WeekPattern.EVERY
                        || s.getWeekPattern() == current)
                .toList();
    }

    private void validateSlots(
            List<SlotData> data,
            String dayOfWeek,
            PersonalTimetableEntity timetable,
            Map<Integer, PersonalTimetablePeriodEntity> periodByNumber) {

        boolean wpEnabled = Boolean.TRUE.equals(timetable.getWeekPatternEnabled());

        // 重複検出 + EVERY と A/B の混在禁止
        Map<String, List<SlotData>> grouped = new HashMap<>();
        for (SlotData d : data) {
            // 曜日フィルタが指定されているなら、リクエストもその曜日に揃っていることを要請
            if (dayOfWeek != null && !dayOfWeek.isBlank()
                    && !dayOfWeek.equals(d.dayOfWeek())) {
                throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_DUPLICATED);
            }

            WeekPattern wp = d.weekPattern() != null ? d.weekPattern() : WeekPattern.EVERY;
            if (!wpEnabled && wp != WeekPattern.EVERY) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_SLOT_WEEK_PATTERN_NOT_ENABLED);
            }

            // 時限存在＆休憩枠検証
            if (!periodByNumber.isEmpty()) {
                PersonalTimetablePeriodEntity p = periodByNumber.get(d.periodNumber());
                if (p == null) {
                    throw new BusinessException(
                            PersonalTimetableErrorCode.PERSONAL_SLOT_PERIOD_NOT_FOUND);
                }
                if (Boolean.TRUE.equals(p.getIsBreak())) {
                    throw new BusinessException(
                            PersonalTimetableErrorCode.PERSONAL_SLOT_BREAK_PERIOD_ASSIGNED);
                }
            }

            String key = d.dayOfWeek() + ":" + d.periodNumber();
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(d);
        }

        for (Map.Entry<String, List<SlotData>> e : grouped.entrySet()) {
            List<SlotData> group = e.getValue();
            boolean hasEvery = group.stream()
                    .anyMatch(g -> (g.weekPattern() == null || g.weekPattern() == WeekPattern.EVERY));
            boolean hasAB = group.stream()
                    .anyMatch(g -> g.weekPattern() == WeekPattern.A || g.weekPattern() == WeekPattern.B);
            if (hasEvery && hasAB) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_SLOT_WEEK_PATTERN_CONFLICT);
            }
            if (hasEvery && group.size() > 1) {
                throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_DUPLICATED);
            }
            // A/B のみの場合、A 同士・B 同士の重複は不可
            Set<WeekPattern> abSeen = new HashSet<>();
            for (SlotData d : group) {
                WeekPattern wp = d.weekPattern();
                if (wp == WeekPattern.A || wp == WeekPattern.B) {
                    if (!abSeen.add(wp)) {
                        throw new BusinessException(
                                PersonalTimetableErrorCode.PERSONAL_SLOT_DUPLICATED);
                    }
                }
            }
        }
    }

    private PersonalTimetableEntity ensureOwned(Long personalTimetableId, Long userId) {
        return timetableRepository
                .findByIdAndUserIdAndDeletedAtIsNull(personalTimetableId, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND));
    }

    /**
     * コマ入力データ。
     */
    public record SlotData(
            String dayOfWeek,
            Integer periodNumber,
            WeekPattern weekPattern,
            String subjectName,
            String courseCode,
            String teacherName,
            String roomName,
            BigDecimal credits,
            String color,
            Long linkedTeamId,
            Long linkedTimetableId,
            Long linkedSlotId,
            Boolean autoSyncChanges,
            String notes) {
    }
}
