package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.RecurrenceRuleDto;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ScheduleCancelledEvent;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.event.ScheduleUpdatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * スケジュールサービス。スケジュールのCRUD・繰り返し展開・カレンダー集約を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleService {

    private static final int MAX_RECURRENCE_OCCURRENCES = 365;
    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";
    private static final String SCOPE_TYPE_PERSONAL = "PERSONAL";
    private static final String UPDATE_SCOPE_THIS_ONLY = "THIS_ONLY";
    private static final String UPDATE_SCOPE_THIS_AND_FOLLOWING = "THIS_AND_FOLLOWING";
    private static final String UPDATE_SCOPE_ALL = "ALL";

    private final ScheduleRepository scheduleRepository;
    private final EventSurveyService eventSurveyService;
    private final ScheduleReminderService reminderService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final NameResolverService nameResolverService;
    private final AccessControlService accessControlService;
    private final UserRoleRepository userRoleRepository;

    /**
     * スケジュールを単体取得する。存在しない場合は例外をスローする。
     *
     * @param id スケジュールID
     * @return スケジュールエンティティ
     */
    public ScheduleEntity getSchedule(Long id) {
        return findScheduleOrThrow(id);
    }

    /**
     * 閲覧権限チェック付きでスケジュールを取得する。
     *
     * @param id     スケジュールID
     * @param userId ユーザーID
     * @return スケジュールエンティティ
     */
    public ScheduleEntity getScheduleWithAccessCheck(Long id, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        // スコープメンバーシップ検証
        if (schedule.getTeamId() != null) {
            accessControlService.checkMembership(userId, schedule.getTeamId(), "TEAM");
        } else if (schedule.getOrganizationId() != null) {
            accessControlService.checkMembership(userId, schedule.getOrganizationId(), "ORGANIZATION");
        }
        return schedule;
    }

    /**
     * チームスコープのスケジュール一覧を取得する。
     *
     * @param teamId チームID
     * @param from   期間開始
     * @param to     期間終了
     * @return スケジュール一覧
     */
    public List<ScheduleResponse> listTeamSchedules(Long teamId, LocalDateTime from, LocalDateTime to) {
        List<ScheduleEntity> schedules = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, from, to);
        return schedules.stream().map(this::toScheduleResponse).toList();
    }

    /**
     * 組織スコープのスケジュール一覧を取得する。
     *
     * @param orgId 組織ID
     * @param from  期間開始
     * @param to    期間終了
     * @return スケジュール一覧
     */
    public List<ScheduleResponse> listOrgSchedules(Long orgId, LocalDateTime from, LocalDateTime to) {
        List<ScheduleEntity> schedules = scheduleRepository
                .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, from, to);
        return schedules.stream().map(this::toScheduleResponse).toList();
    }

    /**
     * スケジュールを作成する。繰り返しルールがある場合は子スケジュールを展開する。
     * attendanceRequired が true の場合は出欠レコード生成をイベント経由で発行する。
     *
     * @param req       作成リクエスト
     * @param scopeId   スコープID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION / PERSONAL）
     * @param userId    作成者ID
     * @return 作成されたスケジュール
     */
    @Transactional
    public ScheduleResponse createSchedule(CreateScheduleRequest req, Long scopeId,
                                           String scopeType, Long userId) {
        validateDateRange(req.getStartAt(), req.getEndAt());

        ScheduleEntity schedule = buildScheduleEntity(req, scopeId, scopeType, userId);
        schedule = scheduleRepository.save(schedule);

        // 繰り返しルールがある場合は子スケジュールを展開
        if (req.getRecurrenceRule() != null) {
            expandRecurrenceSchedules(schedule);
        }

        // アンケート設問の保存
        if (req.getSurveys() != null && !req.getSurveys().isEmpty()) {
            eventSurveyService.createSurveys(schedule.getId(), req.getSurveys());
        }

        // リマインダーの保存
        if (req.getReminders() != null && !req.getReminders().isEmpty()) {
            reminderService.createReminders(schedule.getId(), req.getReminders());
        }

        // イベント発行（トランザクションコミット後に発行）
        String resolvedScopeType = resolveScopeType(schedule);
        eventPublisher.publishEvent(new ScheduleCreatedEvent(
                schedule.getId(), resolvedScopeType, scopeId, userId,
                Boolean.TRUE.equals(req.getAttendanceRequired())));

        log.info("スケジュール作成: id={}, title={}, scope={}:{}", schedule.getId(), schedule.getTitle(), scopeType, scopeId);
        return toScheduleResponse(schedule);
    }

    /**
     * スケジュールを更新する。繰り返しスケジュールの場合は updateScope に応じて更新範囲を制御する。
     *
     * @param id          スケジュールID
     * @param req         更新リクエスト
     * @param updateScope 更新スコープ（THIS_ONLY / THIS_AND_FOLLOWING / ALL）
     * @param userId      操作ユーザーID
     * @return 更新されたスケジュール
     */
    @Transactional
    public ScheduleResponse updateSchedule(Long id, UpdateScheduleRequest req,
                                           String updateScope, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        validateScheduleNotCancelled(schedule);

        if (req.getStartAt() != null || req.getEndAt() != null) {
            LocalDateTime startAt = req.getStartAt() != null ? req.getStartAt() : schedule.getStartAt();
            LocalDateTime endAt = req.getEndAt() != null ? req.getEndAt() : schedule.getEndAt();
            validateDateRange(startAt, endAt);
        }

        if (schedule.isRecurring() || schedule.getParentScheduleId() != null) {
            updateRecurringSchedule(schedule, req, updateScope);
        } else {
            applyUpdateToSchedule(schedule, req);
        }

        schedule = scheduleRepository.save(schedule);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new ScheduleUpdatedEvent(schedule.getId(), userId));

        log.info("スケジュール更新: id={}, updateScope={}", id, updateScope);
        return toScheduleResponse(schedule);
    }

    /**
     * スケジュールを論理削除する。繰り返しスケジュールの場合は updateScope に応じて削除範囲を制御する。
     *
     * @param id          スケジュールID
     * @param updateScope 更新スコープ（THIS_ONLY / THIS_AND_FOLLOWING / ALL）
     */
    @Transactional
    public void deleteSchedule(Long id, String updateScope) {
        ScheduleEntity schedule = findScheduleOrThrow(id);

        if (UPDATE_SCOPE_ALL.equals(updateScope) && schedule.getParentScheduleId() != null) {
            // 親と全子を削除
            Long parentId = schedule.getParentScheduleId();
            ScheduleEntity parent = findScheduleOrThrow(parentId);
            parent.softDelete();
            scheduleRepository.save(parent);
            deleteChildSchedules(parentId);
        } else if (UPDATE_SCOPE_THIS_AND_FOLLOWING.equals(updateScope) && schedule.getParentScheduleId() != null) {
            // この日以降の子を削除
            deleteFollowingSchedules(schedule);
        } else {
            // 単体削除
            schedule.softDelete();
            scheduleRepository.save(schedule);
        }

        log.info("スケジュール削除: id={}, updateScope={}", id, updateScope);
    }

    /**
     * スケジュールをキャンセルする。
     *
     * @param id     スケジュールID
     * @param userId 操作ユーザーID
     */
    @Transactional
    public void cancelSchedule(Long id, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        validateScheduleNotCancelled(schedule);

        schedule.cancel();
        scheduleRepository.save(schedule);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new ScheduleCancelledEvent(schedule.getId(), userId));

        log.info("スケジュールキャンセル: id={}", id);
    }

    /**
     * スケジュールを複製する。
     *
     * @param id     複製元スケジュールID
     * @param userId 作成者ID
     * @return 複製されたスケジュール
     */
    @Transactional
    public ScheduleResponse duplicateSchedule(Long id, Long userId) {
        ScheduleEntity source = findScheduleOrThrow(id);

        ScheduleEntity duplicate = source.toBuilder()
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(userId)
                .googleCalendarEventId(null)
                .build();

        // BaseEntity の id, createdAt, updatedAt は @PrePersist で再設定される
        duplicate = scheduleRepository.save(duplicate);

        log.info("スケジュール複製: sourceId={}, newId={}", id, duplicate.getId());
        return toScheduleResponse(duplicate);
    }

    /**
     * ユーザーの横断カレンダーを取得する。個人・チーム・組織スコープのスケジュールを統合して返す。
     *
     * @param userId ユーザーID
     * @param from   期間開始
     * @param to     期間終了
     * @return カレンダーエントリー一覧
     */
    public List<CalendarEntryResponse> getMyCalendar(Long userId, LocalDateTime from, LocalDateTime to) {
        List<CalendarEntryResponse> entries = new ArrayList<>();

        // 個人スケジュール
        List<ScheduleEntity> personalSchedules = scheduleRepository
                .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, from, to);
        personalSchedules.forEach(s -> entries.add(toCalendarEntry(s, SCOPE_TYPE_PERSONAL, userId)));

        // 所属チームのスケジュールを取得
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        for (UserRoleEntity role : teamRoles) {
            List<ScheduleEntity> teamSchedules = scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), from, to);
            teamSchedules.forEach(s -> entries.add(toCalendarEntry(s, "TEAM", role.getTeamId())));
        }

        // 所属組織のスケジュールを取得
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);
        for (UserRoleEntity role : orgRoles) {
            List<ScheduleEntity> orgSchedules = scheduleRepository
                    .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(role.getOrganizationId(), from, to);
            orgSchedules.forEach(s -> entries.add(toCalendarEntry(s, "ORGANIZATION", role.getOrganizationId())));
        }

        return entries;
    }

    // --- プライベートメソッド ---

    /**
     * スケジュールを取得する。存在しない場合は例外をスローする。
     */
    ScheduleEntity findScheduleOrThrow(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
    }

    /**
     * 開始日時と終了日時の整合性を検証する。
     */
    private void validateDateRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt != null && endAt != null && !startAt.isBefore(endAt)) {
            throw new BusinessException(ScheduleErrorCode.INVALID_DATE_RANGE);
        }
    }

    /**
     * キャンセル済みスケジュールの操作を防止する。
     */
    private void validateScheduleNotCancelled(ScheduleEntity schedule) {
        if (schedule.getStatus() == ScheduleStatus.CANCELLED) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_ALREADY_CANCELLED);
        }
    }

    /**
     * 作成リクエストからスケジュールエンティティを構築する。
     */
    private ScheduleEntity buildScheduleEntity(CreateScheduleRequest req, Long scopeId,
                                               String scopeType, Long userId) {
        String recurrenceRuleJson = null;
        if (req.getRecurrenceRule() != null) {
            recurrenceRuleJson = serializeRecurrenceRule(req.getRecurrenceRule());
        }

        ScheduleEntity.ScheduleEntityBuilder builder = ScheduleEntity.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .location(req.getLocation())
                .startAt(req.getStartAt())
                .endAt(req.getEndAt())
                .allDay(req.getAllDay())
                .eventType(EventType.valueOf(req.getEventType()))
                .visibility(req.getVisibility() != null
                        ? ScheduleVisibility.valueOf(req.getVisibility()) : ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(req.getMinViewRole() != null
                        ? MinViewRole.valueOf(req.getMinViewRole()) : MinViewRole.MEMBER_PLUS)
                .minResponseRole(req.getMinResponseRole() != null
                        ? MinResponseRole.valueOf(req.getMinResponseRole()) : null)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(req.getAttendanceRequired())
                .attendanceDeadline(req.getAttendanceDeadline())
                .commentOption(req.getCommentOption() != null
                        ? CommentOption.valueOf(req.getCommentOption()) : CommentOption.OPTIONAL)
                .recurrenceRule(recurrenceRuleJson)
                .createdBy(userId);

        // スコープ設定（XOR制約: teamId, organizationId, userId のいずれか1つのみ設定）
        switch (scopeType) {
            case SCOPE_TYPE_TEAM -> builder.teamId(scopeId);
            case SCOPE_TYPE_ORGANIZATION -> builder.organizationId(scopeId);
            case SCOPE_TYPE_PERSONAL -> builder.userId(scopeId);
            default -> throw new BusinessException(ScheduleErrorCode.INVALID_SCOPE);
        }

        return builder.build();
    }

    /**
     * 繰り返しスケジュールを展開して子スケジュールを生成する。
     * DAILY: interval日ごと、WEEKLY: daysOfWeek に従って展開、
     * MONTHLY: 同日（存在しなければ末日）、YEARLY: 同月同日。
     * end_type=DATE: endDateまで / COUNT: count回 / NEVER: 1年先まで（上限365件）
     */
    private void expandRecurrenceSchedules(ScheduleEntity parent) {
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
     * 繰り返しスケジュールの更新処理を行う。
     */
    private void updateRecurringSchedule(ScheduleEntity schedule, UpdateScheduleRequest req,
                                         String updateScope) {
        switch (updateScope) {
            case UPDATE_SCOPE_THIS_ONLY -> {
                applyUpdateToSchedule(schedule, req);
                // 繰り返しの例外としてマーク
                if (schedule.getParentScheduleId() != null) {
                    schedule = schedule.toBuilder().isException(true).build();
                    scheduleRepository.save(schedule);
                }
            }
            case UPDATE_SCOPE_THIS_AND_FOLLOWING -> {
                applyUpdateToSchedule(schedule, req);
                // この日以降の子スケジュールも更新（例外を除く）
                updateFollowingSchedules(schedule, req);
            }
            case UPDATE_SCOPE_ALL -> {
                // 親を更新、全子を更新（例外を除く）
                Long parentId = schedule.getParentScheduleId() != null
                        ? schedule.getParentScheduleId() : schedule.getId();
                ScheduleEntity parent = findScheduleOrThrow(parentId);
                applyUpdateToSchedule(parent, req);
                scheduleRepository.save(parent);
                updateAllChildSchedules(parentId, req);
            }
            default -> applyUpdateToSchedule(schedule, req);
        }
    }

    /**
     * スケジュールに更新リクエストの内容を適用する。
     */
    private void applyUpdateToSchedule(ScheduleEntity schedule, UpdateScheduleRequest req) {
        ScheduleEntity.ScheduleEntityBuilder builder = schedule.toBuilder();

        if (req.getTitle() != null) builder.title(req.getTitle());
        if (req.getDescription() != null) builder.description(req.getDescription());
        if (req.getLocation() != null) builder.location(req.getLocation());
        if (req.getStartAt() != null) builder.startAt(req.getStartAt());
        if (req.getEndAt() != null) builder.endAt(req.getEndAt());
        if (req.getAllDay() != null) builder.allDay(req.getAllDay());
        if (req.getEventType() != null) builder.eventType(EventType.valueOf(req.getEventType()));
        if (req.getVisibility() != null) builder.visibility(ScheduleVisibility.valueOf(req.getVisibility()));
        if (req.getMinViewRole() != null) builder.minViewRole(MinViewRole.valueOf(req.getMinViewRole()));
        if (req.getMinResponseRole() != null) builder.minResponseRole(MinResponseRole.valueOf(req.getMinResponseRole()));
        if (req.getAttendanceRequired() != null) builder.attendanceRequired(req.getAttendanceRequired());
        if (req.getAttendanceDeadline() != null) builder.attendanceDeadline(req.getAttendanceDeadline());
        if (req.getCommentOption() != null) builder.commentOption(CommentOption.valueOf(req.getCommentOption()));

        ScheduleEntity updated = builder.build();
        scheduleRepository.save(updated);
    }

    /**
     * 指定スケジュール以降の子スケジュールを更新する（例外は除く）。
     */
    private void updateFollowingSchedules(ScheduleEntity schedule, UpdateScheduleRequest req) {
        Long parentId = schedule.getParentScheduleId() != null
                ? schedule.getParentScheduleId() : schedule.getId();
        List<ScheduleEntity> children = scheduleRepository
                .findByParentScheduleIdOrderByStartAtAsc(parentId);

        children.stream()
                .filter(child -> !child.getIsException())
                .filter(child -> !child.getStartAt().isBefore(schedule.getStartAt()))
                .forEach(child -> applyUpdateToSchedule(child, req));
    }

    /**
     * 親スケジュールの全子を更新する（例外は除く）。
     */
    private void updateAllChildSchedules(Long parentId, UpdateScheduleRequest req) {
        List<ScheduleEntity> children = scheduleRepository
                .findByParentScheduleIdOrderByStartAtAsc(parentId);

        children.stream()
                .filter(child -> !child.getIsException())
                .forEach(child -> applyUpdateToSchedule(child, req));
    }

    /**
     * 親スケジュールの全子を論理削除する。
     */
    private void deleteChildSchedules(Long parentId) {
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
    private void deleteFollowingSchedules(ScheduleEntity schedule) {
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
     * スケジュールのスコープ種別を解決する。
     */
    private String resolveScopeType(ScheduleEntity schedule) {
        if (schedule.isTeamScope()) return SCOPE_TYPE_TEAM;
        if (schedule.isOrganizationScope()) return SCOPE_TYPE_ORGANIZATION;
        return SCOPE_TYPE_PERSONAL;
    }

    /**
     * 繰り返しルールをJSON文字列にシリアライズする。
     */
    private String serializeRecurrenceRule(RecurrenceRuleDto rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ScheduleErrorCode.INVALID_RECURRENCE_RULE);
        }
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

    /**
     * エンティティをスケジュール一覧用レスポンスDTOに変換する。
     */
    private ScheduleResponse toScheduleResponse(ScheduleEntity entity) {
        return new ScheduleResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStartAt(),
                entity.getEndAt(),
                entity.getAllDay(),
                entity.getEventType().name(),
                entity.getStatus().name(),
                entity.getAttendanceRequired(),
                entity.getLocation(),
                entity.getCreatedAt());
    }

    /**
     * エンティティをカレンダーエントリーレスポンスDTOに変換する。
     */
    private CalendarEntryResponse toCalendarEntry(ScheduleEntity entity, String scopeType, Long scopeId) {
        return new CalendarEntryResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStartAt(),
                entity.getEndAt(),
                entity.getAllDay(),
                entity.getEventType().name(),
                entity.getStatus().name(),
                scopeType,
                scopeId,
                nameResolverService.resolveScopeName(scopeType, scopeId),
                null);
    }
}
