package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.RecurrenceRuleDto;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * スケジュールの繰り返し展開・例外処理・繰り返しスコープに応じた更新／削除のディスパッチを担当するサービス。
 *
 * <p>ScheduleService から分割。リファクタリング第6弾（2026-05-17）で
 * 繰り返し関連のロジックを切り出して責務を分離した。</p>
 *
 * <p>単一スケジュールへの更新適用 ({@code applyUpdateToSchedule}) はファサード側の責務として
 * {@link BiConsumer} で受け取り、本サービスは「対象スケジュール群を選び出してそれぞれに適用する」役割に集中する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleRecurrenceService {

    private static final int MAX_RECURRENCE_OCCURRENCES = 365;
    private static final String UPDATE_SCOPE_THIS_ONLY = "THIS_ONLY";
    private static final String UPDATE_SCOPE_THIS_AND_FOLLOWING = "THIS_AND_FOLLOWING";
    private static final String UPDATE_SCOPE_ALL = "ALL";

    private final ScheduleRepository scheduleRepository;
    private final ObjectMapper objectMapper;

    /**
     * 繰り返しスケジュールを展開して子スケジュールを生成する。
     * DAILY: interval日ごと、WEEKLY: daysOfWeek に従って展開、
     * MONTHLY: 同日（存在しなければ末日）、YEARLY: 同月同日。
     * end_type=DATE: endDateまで / COUNT: count回 / NEVER: 1年先まで（上限365件）
     */
    public void expandRecurrenceSchedules(ScheduleEntity parent) {
        RecurrenceRuleDto rule = deserializeRecurrenceRule(parent.getRecurrenceRule());
        if (rule == null) {
            throw new BusinessException(ScheduleErrorCode.INVALID_RECURRENCE_RULE);
        }

        LocalDateTime baseStart = parent.getStartAt();
        long durationMinutes = parent.getEndAt() != null
                ? java.time.Duration.between(parent.getStartAt(), parent.getEndAt()).toMinutes()
                : 0;

        List<LocalDateTime> occurrences = calculateOccurrences(rule, baseStart);

        for (LocalDateTime startAt : occurrences) {
            LocalDateTime endAt = durationMinutes > 0 ? startAt.plusMinutes(durationMinutes) : null;

            ScheduleEntity child = parent.toBuilder()
                    .id(null)  // 新規 INSERT にするため id をリセット（toBuilder() は BaseEntity の id をコピーするため）
                    .parentScheduleId(parent.getId())
                    .startAt(startAt)
                    .endAt(endAt)
                    .recurrenceRule(null)
                    .isException(false)
                    .googleCalendarEventId(null)
                    .build();

            scheduleRepository.save(child);
        }

        log.info("繰り返し展開: parentId={}, 生成数={}", parent.getId(), occurrences.size());
    }

    /**
     * 繰り返しスケジュールの更新処理を行う。
     *
     * @param schedule     対象スケジュール
     * @param req          更新リクエスト
     * @param updateScope  更新スコープ
     * @param applyUpdate  単一スケジュールへの更新適用ロジック（ファサード側で実装）
     */
    public void updateRecurringSchedule(ScheduleEntity schedule, UpdateScheduleRequest req,
                                        String updateScope,
                                        BiConsumer<ScheduleEntity, UpdateScheduleRequest> applyUpdate) {
        switch (updateScope) {
            case UPDATE_SCOPE_THIS_ONLY -> {
                applyUpdate.accept(schedule, req);
                // 繰り返しの例外としてマーク
                if (schedule.getParentScheduleId() != null) {
                    schedule = schedule.toBuilder().isException(true).build();
                    scheduleRepository.save(schedule);
                }
            }
            case UPDATE_SCOPE_THIS_AND_FOLLOWING -> {
                applyUpdate.accept(schedule, req);
                // この日以降の子スケジュールも更新（例外を除く）
                updateFollowingSchedules(schedule, req, applyUpdate);
            }
            case UPDATE_SCOPE_ALL -> {
                // 親を更新、全子を更新（例外を除く）
                Long parentId = schedule.getParentScheduleId() != null
                        ? schedule.getParentScheduleId() : schedule.getId();
                ScheduleEntity parent = scheduleRepository.findById(parentId)
                        .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
                applyUpdate.accept(parent, req);
                scheduleRepository.save(parent);
                updateAllChildSchedules(parentId, req, applyUpdate);
            }
            default -> applyUpdate.accept(schedule, req);
        }
    }

    /**
     * 親スケジュールの全子を論理削除する。
     */
    public void deleteChildSchedules(Long parentId) {
        List<ScheduleEntity> children = scheduleRepository
                .findByParentScheduleIdOrderByStartAtAsc(parentId);
        children.forEach(child -> {
            child.softDelete();
            scheduleRepository.save(child);
        });
    }

    /**
     * 指定スケジュール以降の子スケジュールを論理削除する。
     */
    public void deleteFollowingSchedules(ScheduleEntity schedule) {
        Long parentId = schedule.getParentScheduleId() != null
                ? schedule.getParentScheduleId() : schedule.getId();
        List<ScheduleEntity> children = scheduleRepository
                .findByParentScheduleIdOrderByStartAtAsc(parentId);

        children.stream()
                .filter(child -> !child.getStartAt().isBefore(schedule.getStartAt()))
                .forEach(child -> {
                    child.softDelete();
                    scheduleRepository.save(child);
                });
    }

    /**
     * 繰り返しルールをJSON文字列にシリアライズする。
     */
    public String serializeRecurrenceRule(RecurrenceRuleDto rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ScheduleErrorCode.INVALID_RECURRENCE_RULE);
        }
    }

    // --- プライベートメソッド ---

    /**
     * 繰り返しルールに基づいて日時の一覧を計算する。
     */
    private List<LocalDateTime> calculateOccurrences(RecurrenceRuleDto rule, LocalDateTime baseStart) {
        List<LocalDateTime> occurrences = new ArrayList<>();

        int maxCount = resolveMaxCount(rule);
        LocalDate endDate = resolveEndDate(rule, baseStart.toLocalDate());
        int interval = rule.interval();

        LocalDate current = baseStart.toLocalDate();
        int count = 0;

        while (count < maxCount) {
            current = advanceDate(current, rule.type(), interval, rule.daysOfWeek());
            if (current == null || current.isAfter(endDate)) {
                break;
            }

            occurrences.add(current.atTime(baseStart.toLocalTime()));
            count++;
        }

        return occurrences;
    }

    /**
     * 繰り返しルールの終了条件から最大生成数を決定する。
     */
    private int resolveMaxCount(RecurrenceRuleDto rule) {
        if ("COUNT".equals(rule.endType()) && rule.count() != null) {
            return Math.min(rule.count(), MAX_RECURRENCE_OCCURRENCES);
        }
        return MAX_RECURRENCE_OCCURRENCES;
    }

    /**
     * 繰り返しルールの終了条件から終了日を決定する。
     */
    private LocalDate resolveEndDate(RecurrenceRuleDto rule, LocalDate baseDate) {
        if ("DATE".equals(rule.endType()) && rule.endDate() != null) {
            return rule.endDate();
        }
        // NEVER または COUNT の場合は1年先を上限とする
        return baseDate.plusYears(1);
    }

    /**
     * 繰り返し種別に応じて次の日付を算出する。
     */
    private LocalDate advanceDate(LocalDate current, String type, int interval, List<String> daysOfWeek) {
        return switch (type) {
            case "DAILY" -> current.plusDays(interval);
            case "WEEKLY" -> advanceWeekly(current, interval, daysOfWeek);
            case "MONTHLY" -> advanceMonthly(current, interval);
            case "YEARLY" -> current.plusYears(interval);
            default -> null;
        };
    }

    /**
     * 週単位の繰り返し: daysOfWeek に従って次の日付を算出する。
     */
    private LocalDate advanceWeekly(LocalDate current, int interval, List<String> daysOfWeek) {
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            return current.plusWeeks(interval);
        }
        // 次の該当曜日を探す
        LocalDate next = current.plusDays(1);
        LocalDate limit = current.plusWeeks(interval + 1);
        while (!next.isAfter(limit)) {
            String dayName = next.getDayOfWeek().name();
            if (daysOfWeek.contains(dayName)) {
                return next;
            }
            next = next.plusDays(1);
        }
        return current.plusWeeks(interval);
    }

    /**
     * 月単位の繰り返し: 同日（存在しなければ末日）を算出する。
     */
    private LocalDate advanceMonthly(LocalDate current, int interval) {
        LocalDate nextMonth = current.plusMonths(interval);
        int targetDay = current.getDayOfMonth();
        int lastDay = nextMonth.with(TemporalAdjusters.lastDayOfMonth()).getDayOfMonth();
        return nextMonth.withDayOfMonth(Math.min(targetDay, lastDay));
    }

    /**
     * 指定スケジュール以降の子スケジュールを更新する（例外は除く）。
     */
    private void updateFollowingSchedules(ScheduleEntity schedule, UpdateScheduleRequest req,
                                          BiConsumer<ScheduleEntity, UpdateScheduleRequest> applyUpdate) {
        Long parentId = schedule.getParentScheduleId() != null
                ? schedule.getParentScheduleId() : schedule.getId();
        List<ScheduleEntity> children = scheduleRepository
                .findByParentScheduleIdOrderByStartAtAsc(parentId);

        children.stream()
                .filter(child -> !child.getIsException())
                .filter(child -> !child.getStartAt().isBefore(schedule.getStartAt()))
                .forEach(child -> applyUpdate.accept(child, req));
    }

    /**
     * 親スケジュールの全子を更新する（例外は除く）。
     */
    private void updateAllChildSchedules(Long parentId, UpdateScheduleRequest req,
                                         BiConsumer<ScheduleEntity, UpdateScheduleRequest> applyUpdate) {
        List<ScheduleEntity> children = scheduleRepository
                .findByParentScheduleIdOrderByStartAtAsc(parentId);

        children.stream()
                .filter(child -> !child.getIsException())
                .forEach(child -> applyUpdate.accept(child, req));
    }

    /**
     * JSON文字列から繰り返しルールをデシリアライズする。
     */
    private RecurrenceRuleDto deserializeRecurrenceRule(String json) {
        try {
            return objectMapper.readValue(json, RecurrenceRuleDto.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ScheduleErrorCode.INVALID_RECURRENCE_RULE);
        }
    }
}
