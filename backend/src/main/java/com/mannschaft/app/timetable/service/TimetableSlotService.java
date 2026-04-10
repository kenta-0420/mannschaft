package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 時間割スロットサービス。スロットのCRUD・週次ビュー・臨時変更反映を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableSlotService {

    private final TimetableSlotRepository slotRepository;
    private final TimetableChangeRepository changeRepository;
    private final TimetableRepository timetableRepository;

    /**
     * 時間割の全スロットを取得する。
     */
    public List<TimetableSlotEntity> getSlots(Long timetableId) {
        return slotRepository.findByTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(timetableId);
    }

    /**
     * 時間割の指定曜日のスロットを取得する。
     */
    public List<TimetableSlotEntity> getSlotsByDay(Long timetableId, String dayOfWeek) {
        return slotRepository.findByTimetableIdAndDayOfWeek(timetableId, dayOfWeek);
    }

    /**
     * 時間割スロットを置換する。
     * dayOfWeek指定時はその曜日のみ、null時は全曜日を対象にDELETE→INSERTする。
     */
    @Transactional
    public List<TimetableSlotEntity> replaceSlots(Long timetableId, List<SlotData> slots,
                                                   String dayOfWeek) {
        // DRAFT状態チェック（設計書要件: スロット編集はDRAFT状態の時間割のみ可能）
        TimetableEntity timetable = timetableRepository.findById(timetableId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TIMETABLE_NOT_FOUND));
        if (!timetable.isDraft()) {
            throw new BusinessException(TimetableErrorCode.TIMETABLE_NOT_DRAFT);
        }
        validateSlotWeekPatterns(slots);

        if (dayOfWeek != null) {
            // 特定曜日のスロットのみ削除
            List<TimetableSlotEntity> existing =
                    slotRepository.findByTimetableIdAndDayOfWeek(timetableId, dayOfWeek);
            slotRepository.deleteAll(existing);
        } else {
            slotRepository.deleteByTimetableId(timetableId);
        }

        List<TimetableSlotEntity> entities = slots.stream()
                .map(s -> TimetableSlotEntity.builder()
                        .timetableId(timetableId)
                        .dayOfWeek(s.dayOfWeek())
                        .periodNumber(s.periodNumber())
                        .weekPattern(s.weekPattern())
                        .subjectName(s.subjectName())
                        .teacherName(s.teacherName())
                        .roomName(s.roomName())
                        .color(s.color())
                        .notes(s.notes())
                        .build())
                .toList();

        return slotRepository.saveAll(entities);
    }

    /**
     * 今日のスロット（臨時変更反映済み）を取得する。
     */
    public List<ResolvedSlot> getTodaySlots(Long timetableId, TimetableEntity timetable) {
        LocalDate today = LocalDate.now();
        String todayDow = today.getDayOfWeek().name().substring(0, 3); // MONDAY → MON

        List<TimetableSlotEntity> allSlots =
                slotRepository.findByTimetableIdAndDayOfWeek(timetableId, todayDow);

        // A/B週フィルタリング
        WeekPattern currentPattern = resolveWeekPattern(timetable, today);
        allSlots = filterByWeekPattern(allSlots, currentPattern);

        List<TimetableChangeEntity> changes =
                changeRepository.findByTimetableIdAndTargetDateOrderByPeriodNumber(timetableId, today);

        return applyChanges(allSlots, changes);
    }

    /**
     * 週次ビューを取得する。月曜から日曜までの7日分のスロットと臨時変更を含む。
     */
    public WeeklyViewData getWeeklyView(Long timetableId, TimetableEntity timetable, LocalDate weekOf) {
        LocalDate weekStart = weekOf.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        List<TimetableSlotEntity> allSlots = getSlots(timetableId);
        List<TimetableChangeEntity> weekChanges =
                changeRepository.findByTimetableIdAndTargetDateBetweenOrderByTargetDateAscPeriodNumberAsc(
                        timetableId, weekStart, weekEnd);

        WeekPattern currentWeekPattern = resolveWeekPattern(timetable, weekStart);

        Map<LocalDate, List<TimetableChangeEntity>> changesByDate = weekChanges.stream()
                .collect(Collectors.groupingBy(TimetableChangeEntity::getTargetDate));

        Map<String, DayViewData> days = new java.util.LinkedHashMap<>();

        String[] dowNames = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            String dow = dowNames[i];

            List<TimetableSlotEntity> daySlots = allSlots.stream()
                    .filter(s -> s.getDayOfWeek().equals(dow))
                    .toList();

            WeekPattern pattern = resolveWeekPattern(timetable, date);
            daySlots = filterByWeekPattern(daySlots, pattern);

            List<TimetableChangeEntity> dayChanges =
                    changesByDate.getOrDefault(date, List.of());

            // DAY_OFF チェック
            TimetableChangeEntity dayOff = dayChanges.stream()
                    .filter(TimetableChangeEntity::isDayOff)
                    .findFirst()
                    .orElse(null);
            boolean isDayOff = dayOff != null;
            String dayOffReason = isDayOff ? dayOff.getReason() : null;

            List<ResolvedSlot> slots = isDayOff ? List.of() : applyChanges(daySlots, dayChanges);
            days.put(dow, new DayViewData(date, isDayOff, dayOffReason, slots));
        }

        return new WeeklyViewData(
                timetableId,
                timetable.getName(),
                weekStart,
                weekEnd,
                timetable.getWeekPatternEnabled(),
                currentWeekPattern,
                List.of(), // periods は Controller 層で補完
                days
        );
    }

    /**
     * チームの教科名候補を取得する。
     */
    public List<String> getSubjectSuggestions(Long teamId) {
        return slotRepository.findDistinctSubjectNamesByTeamId(teamId);
    }

    /**
     * A/B週パターンを判定する。
     * weekPatternBaseDateからの経過週数で偶数週=A、奇数週=Bとする。
     */
    public WeekPattern resolveWeekPattern(TimetableEntity timetable, LocalDate date) {
        if (!timetable.getWeekPatternEnabled()) {
            return WeekPattern.EVERY;
        }
        long weeksBetween = ChronoUnit.WEEKS.between(
                timetable.getWeekPatternBaseDate().with(DayOfWeek.MONDAY),
                date.with(DayOfWeek.MONDAY)
        );
        return (weeksBetween % 2 == 0) ? WeekPattern.A : WeekPattern.B;
    }

    /**
     * スロットに臨時変更を反映する。
     */
    public List<ResolvedSlot> applyChanges(List<TimetableSlotEntity> slots,
                                            List<TimetableChangeEntity> changes) {
        // DAY_OFF チェック — 全日休みならスロットをクリア
        boolean hasDayOff = changes.stream().anyMatch(TimetableChangeEntity::isDayOff);
        if (hasDayOff) {
            return List.of();
        }

        // period_number でインデックス化
        Map<Integer, TimetableSlotEntity> slotMap = slots.stream()
                .collect(Collectors.toMap(
                        TimetableSlotEntity::getPeriodNumber,
                        s -> s,
                        (a, b) -> a // 重複時は先勝ち
                ));

        // 変更を適用
        List<ResolvedSlot> result = new ArrayList<>();
        Map<Integer, TimetableChangeEntity> changeMap = changes.stream()
                .filter(c -> c.getPeriodNumber() != null)
                .collect(Collectors.toMap(
                        TimetableChangeEntity::getPeriodNumber,
                        c -> c,
                        (a, b) -> b // 後勝ち
                ));

        // 既存スロットに変更を反映
        for (TimetableSlotEntity slot : slots) {
            TimetableChangeEntity change = changeMap.remove(slot.getPeriodNumber());
            if (change == null) {
                // 変更なし — そのまま出力
                result.add(toResolvedSlot(slot, false, null, null, null));
            } else if (change.getChangeType() == TimetableChangeType.REPLACE) {
                // 差し替え
                result.add(new ResolvedSlot(
                        slot.getPeriodNumber(),
                        change.getSubjectName(),
                        change.getTeacherName(),
                        change.getRoomName(),
                        slot.getColor(),
                        slot.getNotes(),
                        true,
                        slot.getSubjectName(),
                        TimetableChangeType.REPLACE,
                        change.getReason()
                ));
            } else if (change.getChangeType() == TimetableChangeType.CANCEL) {
                // 取消 — スロットを除去（結果に追加しない）
                continue;
            }
        }

        // ADDタイプの変更を追加
        for (TimetableChangeEntity change : changeMap.values()) {
            if (change.getChangeType() == TimetableChangeType.ADD) {
                result.add(new ResolvedSlot(
                        change.getPeriodNumber(),
                        change.getSubjectName(),
                        change.getTeacherName(),
                        change.getRoomName(),
                        null,
                        null,
                        true,
                        null,
                        TimetableChangeType.ADD,
                        change.getReason()
                ));
            }
        }

        // ADD分もchangeMapに残っている可能性を処理
        for (TimetableChangeEntity change : changes) {
            if (change.getChangeType() == TimetableChangeType.ADD
                    && change.getPeriodNumber() != null
                    && !slotMap.containsKey(change.getPeriodNumber())) {
                // changeMapで既に処理済みか確認
                boolean alreadyAdded = result.stream()
                        .anyMatch(r -> r.periodNumber().equals(change.getPeriodNumber())
                                && r.changeType() == TimetableChangeType.ADD);
                if (!alreadyAdded) {
                    result.add(new ResolvedSlot(
                            change.getPeriodNumber(),
                            change.getSubjectName(),
                            change.getTeacherName(),
                            change.getRoomName(),
                            null,
                            null,
                            true,
                            null,
                            TimetableChangeType.ADD,
                            change.getReason()
                    ));
                }
            }
        }

        return result;
    }

    // ---- Private Helpers ----

    private List<TimetableSlotEntity> filterByWeekPattern(List<TimetableSlotEntity> slots,
                                                           WeekPattern currentPattern) {
        return slots.stream()
                .filter(s -> s.getWeekPattern() == WeekPattern.EVERY
                        || s.getWeekPattern() == currentPattern)
                .toList();
    }

    private void validateSlotWeekPatterns(List<SlotData> slots) {
        // EVERY と同一 day+period の A/B が共存しないことを検証
        Map<String, List<SlotData>> grouped = slots.stream()
                .collect(Collectors.groupingBy(s -> s.dayOfWeek() + ":" + s.periodNumber()));

        for (Map.Entry<String, List<SlotData>> entry : grouped.entrySet()) {
            List<SlotData> group = entry.getValue();
            boolean hasEvery = group.stream().anyMatch(s -> s.weekPattern() == WeekPattern.EVERY);
            boolean hasAB = group.stream().anyMatch(s -> s.weekPattern() == WeekPattern.A
                    || s.weekPattern() == WeekPattern.B);
            if (hasEvery && hasAB) {
                throw new BusinessException(TimetableErrorCode.SLOT_WEEK_PATTERN_CONFLICT);
            }
            if (hasEvery && group.size() > 1) {
                throw new BusinessException(TimetableErrorCode.SLOT_WEEK_PATTERN_CONFLICT);
            }
        }
    }

    private ResolvedSlot toResolvedSlot(TimetableSlotEntity slot, boolean isChanged,
                                         String originalSubject,
                                         TimetableChangeType changeType,
                                         String changeReason) {
        return new ResolvedSlot(
                slot.getPeriodNumber(),
                slot.getSubjectName(),
                slot.getTeacherName(),
                slot.getRoomName(),
                slot.getColor(),
                slot.getNotes(),
                isChanged,
                originalSubject,
                changeType,
                changeReason
        );
    }

    // ---- Inner Records ----

    /**
     * スロットデータ。
     */
    public record SlotData(
            String dayOfWeek,
            Integer periodNumber,
            WeekPattern weekPattern,
            String subjectName,
            String teacherName,
            String roomName,
            String color,
            String notes
    ) {}

    /**
     * 臨時変更反映済みスロット。
     */
    public record ResolvedSlot(
            Integer periodNumber,
            String subjectName,
            String teacherName,
            String roomName,
            String color,
            String notes,
            boolean isChanged,
            String originalSubject,
            TimetableChangeType changeType,
            String changeReason
    ) {}

    /**
     * 曜日ごとの日次ビューデータ（日付・DAY_OFF情報・スロット一覧）。
     */
    public record DayViewData(
            LocalDate date,
            boolean isDayOff,
            String dayOffReason,
            List<ResolvedSlot> slots
    ) {}

    /**
     * 週次ビューデータ。
     */
    public record WeeklyViewData(
            Long timetableId,
            String timetableName,
            LocalDate weekStart,
            LocalDate weekEnd,
            boolean weekPatternEnabled,
            WeekPattern currentWeekPattern,
            List<String> periods,
            Map<String, DayViewData> days
    ) {}
}
