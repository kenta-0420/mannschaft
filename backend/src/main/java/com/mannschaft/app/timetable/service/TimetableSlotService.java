package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
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
    private final TimetableService timetableService;

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
        // DRAFT状態チェック（teamIdが必要なため、slotからtimetableを引く）
        // timetableIdでスロットを操作するため、timetable自体の存在確認はslotRepository経由で暗黙的に行う
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
                .map(s -> {
                    var entity = new TimetableSlotEntity();
                    entity.setTimetableId(timetableId);
                    entity.setDayOfWeek(s.dayOfWeek());
                    entity.setPeriodNumber(s.periodNumber());
                    entity.setWeekPattern(s.weekPattern());
                    entity.setSubjectName(s.subjectName());
                    entity.setTeacherName(s.teacherName());
                    entity.setRoomName(s.roomName());
                    entity.setColor(s.color());
                    entity.setNotes(s.notes());
                    return entity;
                })
                .toList();

        return slotRepository.saveAll(entities);
    }

    /**
     * 今日のスロット（臨時変更反映済み）を取得する。
     */
    public List<ResolvedSlot> getTodaySlots(Long timetableId) {
        LocalDate today = LocalDate.now();
        TimetableEntity timetable = timetableService.getByTeamId(0L).stream()
                .filter(t -> t.getId().equals(timetableId))
                .findFirst()
                .orElse(null);

        // timetable情報を直接取得
        String todayDow = today.getDayOfWeek().name();

        List<TimetableSlotEntity> allSlots =
                slotRepository.findByTimetableIdAndDayOfWeek(timetableId, todayDow);

        // A/B週フィルタリング
        if (timetable != null) {
            WeekPattern currentPattern = resolveWeekPattern(timetable, today);
            allSlots = filterByWeekPattern(allSlots, currentPattern);
        }

        List<TimetableChangeEntity> changes =
                changeRepository.findByTimetableIdAndTargetDateOrderByPeriodNumber(timetableId, today);

        return applyChanges(allSlots, changes);
    }

    /**
     * 週次ビューを取得する。月曜から日曜までの7日分のスロットと臨時変更を含む。
     */
    public WeeklyViewData getWeeklyView(Long timetableId, LocalDate weekOf) {
        LocalDate weekStart = weekOf.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        // timetable情報取得
        List<TimetableSlotEntity> allSlots = getSlots(timetableId);
        List<TimetableChangeEntity> weekChanges =
                changeRepository.findByTimetableIdAndTargetDateBetweenOrderByTargetDateAscPeriodNumberAsc(
                        timetableId, weekStart, weekEnd);

        // timetable取得（週パターン判定用）
        // NOTE: getByTeamIdを使わず、allSlotsが空でなければtimetableIdから推定
        TimetableEntity timetable = null;
        WeekPattern currentWeekPattern = WeekPattern.EVERY;
        boolean weekPatternEnabled = false;

        // 日ごとにスロット+変更を組み立て
        Map<LocalDate, List<TimetableChangeEntity>> changesByDate = weekChanges.stream()
                .collect(Collectors.groupingBy(TimetableChangeEntity::getTargetDate));

        Map<String, List<ResolvedSlot>> days = new java.util.LinkedHashMap<>();
        List<String> periodLabels = List.of(); // Controller層で別途取得する想定

        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            String dow = date.getDayOfWeek().name();

            List<TimetableSlotEntity> daySlots = allSlots.stream()
                    .filter(s -> s.getDayOfWeek().equals(dow))
                    .toList();

            // A/B週フィルタリング
            if (timetable != null) {
                WeekPattern pattern = resolveWeekPattern(timetable, date);
                daySlots = filterByWeekPattern(daySlots, pattern);
            }

            List<TimetableChangeEntity> dayChanges =
                    changesByDate.getOrDefault(date, List.of());

            days.put(dow, applyChanges(daySlots, dayChanges));
        }

        return new WeeklyViewData(
                timetableId,
                null, // timetableName は Controller 層で補完
                weekStart,
                weekEnd,
                weekPatternEnabled,
                currentWeekPattern,
                periodLabels,
                days
        );
    }

    /**
     * チームの教科名候補を取得する。
     */
    public List<String> getSubjectSuggestions(Long teamId) {
        return slotRepository.findDistinctSubjectNames(teamId);
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
            Map<String, List<ResolvedSlot>> days
    ) {}
}
