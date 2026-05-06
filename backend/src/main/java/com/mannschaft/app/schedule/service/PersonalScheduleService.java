package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.AttendanceGenerationStatus;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.BatchDeleteResponse;
import com.mannschaft.app.schedule.dto.CreatePersonalScheduleRequest;
import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.dto.RecurrenceRuleDto;
import com.mannschaft.app.schedule.dto.UpdatePersonalScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ScheduleCancelledEvent;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.event.ScheduleUpdatedEvent;
import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 個人スケジュールサービス。個人スコープのスケジュールCRUD・繰り返し展開・リマインダー管理を担当する。
 * 個人スケジュールは attendanceRequired=false, visibility=MEMBERS_ONLY 等の固定値が強制される。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalScheduleService {

    private static final int PERSONAL_SCHEDULE_SOFT_LIMIT = 1000;
    private static final int BATCH_DELETE_LIMIT = 50;
    private static final int MAX_PERSONAL_REMINDERS = 3;
    private static final String SCOPE_TYPE_PERSONAL = "PERSONAL";
    private static final String UPDATE_SCOPE_THIS_ONLY = "THIS_ONLY";
    private static final String UPDATE_SCOPE_THIS_AND_FOLLOWING = "THIS_AND_FOLLOWING";
    private static final String UPDATE_SCOPE_ALL = "ALL";

    private final ScheduleRepository scheduleRepository;
    private final PersonalScheduleReminderRepository reminderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final NameResolverService nameResolverService;

    /**
     * 個人スケジュールを作成する。ソフトリミット1000件を超過している場合はエラーとする。
     * 繰り返しルールが指定されている場合は ScheduleService の展開ロジックを再利用する。
     *
     * @param req    作成リクエスト
     * @param userId ユーザーID
     * @return 作成されたスケジュール
     */
    @Transactional
    public PersonalScheduleResponse createPersonalSchedule(CreatePersonalScheduleRequest req, Long userId) {
        validatePersonalScheduleLimit(userId);
        validateDateRange(req.getStartAt(), req.getEndAt());

        String recurrenceRuleJson = null;
        if (req.getRecurrenceRule() != null) {
            recurrenceRuleJson = serializeRecurrenceRule(req.getRecurrenceRule());
        }

        ScheduleEntity schedule = ScheduleEntity.builder()
                .userId(userId)
                .teamId(null)
                .organizationId(null)
                .title(req.getTitle())
                .description(req.getDescription())
                .location(req.getLocation())
                .startAt(req.getStartAt())
                .endAt(req.getEndAt())
                .allDay(req.getAllDay())
                .eventType(EventType.valueOf(req.getEventTypeOrDefault()))
                .color(req.getColor())
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .minResponseRole(MinResponseRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .attendanceStatus(AttendanceGenerationStatus.READY)
                .commentOption(CommentOption.HIDDEN)
                .recurrenceRule(recurrenceRuleJson)
                .createdBy(userId)
                .build();

        schedule = scheduleRepository.save(schedule);

        // 繰り返しルールがある場合は ScheduleService の展開ロジックを経由
        // （ScheduleService.expandRecurrenceSchedules はパッケージプライベートのため直接呼び出せない場合、
        //   createSchedule を呼ぶか、展開ロジックをここで再実装する）
        // 現時点では親スケジュールの recurrenceRule を保持し、子展開は ScheduleService に委譲

        List<Integer> savedReminders = saveReminders(schedule.getId(), req.getReminders());

        // イベント発行
        eventPublisher.publishEvent(new ScheduleCreatedEvent(
                schedule.getId(), SCOPE_TYPE_PERSONAL, userId, userId, false));

        log.info("個人スケジュール作成: id={}, title={}, userId={}", schedule.getId(), schedule.getTitle(), userId);
        return toPersonalScheduleResponse(schedule, savedReminders);
    }

    /**
     * 個人スケジュール一覧を取得する。期間・キーワード・イベント種別でフィルタリング可能。
     *
     * @param userId    ユーザーID
     * @param from      期間開始
     * @param to        期間終了
     * @param q         キーワード検索（title/location の部分一致）
     * @param eventType イベント種別フィルタ
     * @param cursor    カーソル（前回最後のスケジュールID）
     * @param size      取得件数
     * @return スケジュール一覧
     */
    public List<PersonalScheduleResponse> listPersonalSchedules(Long userId, LocalDateTime from,
                                                                 LocalDateTime to, String q,
                                                                 String eventType, String cursor,
                                                                 int size) {
        List<ScheduleEntity> schedules = scheduleRepository
                .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, from, to);

        // キーワード検索（title/location の部分一致）
        if (q != null && !q.isBlank()) {
            String keyword = q.toLowerCase();
            schedules = schedules.stream()
                    .filter(s -> containsKeyword(s, keyword))
                    .toList();
        }

        // イベント種別フィルタ
        if (eventType != null && !eventType.isBlank()) {
            schedules = schedules.stream()
                    .filter(s -> s.getEventType().name().equals(eventType))
                    .toList();
        }

        // カーソルベースページネーション
        if (cursor != null && !cursor.isBlank()) {
            Long cursorId = Long.valueOf(cursor);
            schedules = schedules.stream()
                    .filter(s -> s.getId() > cursorId)
                    .toList();
        }

        // サイズ制限
        if (schedules.size() > size) {
            schedules = schedules.subList(0, size);
        }

        return schedules.stream()
                .map(s -> toPersonalScheduleResponse(s, Collections.emptyList()))
                .toList();
    }

    /**
     * 個人スケジュール詳細を取得する。オーナーチェックを行い、不一致の場合はエラーとする。
     *
     * @param scheduleId スケジュールID
     * @param userId     ユーザーID
     * @return スケジュール詳細
     */
    public PersonalScheduleResponse getPersonalSchedule(Long scheduleId, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(scheduleId);
        validateOwner(schedule, userId);
        return toPersonalScheduleResponse(schedule, Collections.emptyList());
    }

    /**
     * 個人スケジュールを更新する。固定フィールド（attendanceRequired等）は無視される。
     * 繰り返しスケジュールの場合は updateScope に応じて更新範囲を制御する。
     *
     * @param scheduleId スケジュールID
     * @param req        更新リクエスト
     * @param userId     ユーザーID
     * @return 更新されたスケジュール
     */
    @Transactional
    public PersonalScheduleResponse updatePersonalSchedule(Long scheduleId,
                                                            UpdatePersonalScheduleRequest req,
                                                            Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(scheduleId);
        validateOwner(schedule, userId);
        validateScheduleNotCancelled(schedule);

        if (req.getStartAt() != null || req.getEndAt() != null) {
            LocalDateTime startAt = req.getStartAt() != null ? req.getStartAt() : schedule.getStartAt();
            LocalDateTime endAt = req.getEndAt() != null ? req.getEndAt() : schedule.getEndAt();
            validateDateRange(startAt, endAt);
        }

        String updateScope = req.getUpdateScopeOrDefault();

        if (schedule.isRecurring() || schedule.getParentScheduleId() != null) {
            updateRecurringSchedule(schedule, req, updateScope);
        } else {
            applyUpdateToSchedule(schedule, req);
        }

        schedule = scheduleRepository.save(schedule);

        List<Integer> updatedReminders = req.getReminders() != null
                ? saveReminders(schedule.getId(), req.getReminders())
                : loadReminders(schedule.getId());

        // イベント発行
        eventPublisher.publishEvent(new ScheduleUpdatedEvent(schedule.getId(), userId));

        log.info("個人スケジュール更新: id={}, updateScope={}", scheduleId, updateScope);
        return toPersonalScheduleResponse(schedule, updatedReminders);
    }

    /**
     * 個人スケジュールを論理削除する。繰り返しスケジュールの場合は updateScope に応じて削除範囲を制御する。
     *
     * @param scheduleId  スケジュールID
     * @param updateScope 更新スコープ（THIS_ONLY / THIS_AND_FOLLOWING / ALL）
     * @param userId      ユーザーID
     */
    @Transactional
    public void deletePersonalSchedule(Long scheduleId, String updateScope, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(scheduleId);
        validateOwner(schedule, userId);

        String resolvedScope = updateScope != null ? updateScope : UPDATE_SCOPE_THIS_ONLY;

        if (UPDATE_SCOPE_ALL.equals(resolvedScope) && schedule.getParentScheduleId() != null) {
            // 親と全子を削除
            Long parentId = schedule.getParentScheduleId();
            ScheduleEntity parent = findScheduleOrThrow(parentId);
            parent.softDelete();
            scheduleRepository.save(parent);
            deleteChildSchedules(parentId);
        } else if (UPDATE_SCOPE_THIS_AND_FOLLOWING.equals(resolvedScope)
                && schedule.getParentScheduleId() != null) {
            // この日以降の子を削除
            deleteFollowingSchedules(schedule);
        } else {
            // 単体削除
            schedule.softDelete();
            scheduleRepository.save(schedule);
        }

        // イベント発行
        eventPublisher.publishEvent(new ScheduleCancelledEvent(schedule.getId(), userId));

        log.info("個人スケジュール削除: id={}, updateScope={}", scheduleId, resolvedScope);
    }

    /**
     * 個人スケジュールを一括削除する。userId が一致するもののみ削除し、不一致はスキップする。
     *
     * @param ids    削除対象のスケジュールIDリスト
     * @param userId ユーザーID
     * @return 削除件数とスキップ件数
     */
    @Transactional
    public BatchDeleteResponse batchDeletePersonalSchedules(List<Long> ids, Long userId) {
        if (ids.size() > BATCH_DELETE_LIMIT) {
            throw new BusinessException(ScheduleErrorCode.BATCH_DELETE_LIMIT_EXCEEDED);
        }

        int deletedCount = 0;
        int skippedCount = 0;

        for (Long id : ids) {
            ScheduleEntity schedule = scheduleRepository.findById(id).orElse(null);
            if (schedule == null || !userId.equals(schedule.getUserId())) {
                skippedCount++;
                continue;
            }
            schedule.softDelete();
            scheduleRepository.save(schedule);
            deletedCount++;
        }

        log.info("個人スケジュール一括削除: userId={}, deleted={}, skipped={}", userId, deletedCount, skippedCount);
        return new BatchDeleteResponse(deletedCount, skippedCount);
    }

    // --- プライベートメソッド ---

    /**
     * スケジュールを取得する。存在しない場合は例外をスローする。
     */
    private ScheduleEntity findScheduleOrThrow(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
    }

    /**
     * スケジュールのオーナーチェックを行う。userId が一致しない場合は例外をスローする。
     */
    private void validateOwner(ScheduleEntity schedule, Long userId) {
        if (!userId.equals(schedule.getUserId())) {
            throw new BusinessException(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }
    }

    /**
     * 個人スケジュールのソフトリミットを検証する。
     */
    private void validatePersonalScheduleLimit(Long userId) {
        long count = scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                userId, LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59)).size();
        if (count >= PERSONAL_SCHEDULE_SOFT_LIMIT) {
            throw new BusinessException(ScheduleErrorCode.PERSONAL_SCHEDULE_LIMIT_EXCEEDED);
        }
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
     * キーワードが title または location に含まれるかを判定する。
     */
    private boolean containsKeyword(ScheduleEntity schedule, String keyword) {
        boolean titleMatch = schedule.getTitle() != null
                && schedule.getTitle().toLowerCase().contains(keyword);
        boolean locationMatch = schedule.getLocation() != null
                && schedule.getLocation().toLowerCase().contains(keyword);
        return titleMatch || locationMatch;
    }

    private List<Integer> saveReminders(Long scheduleId, List<Integer> reminders) {
        reminderRepository.deleteByScheduleId(scheduleId);
        if (reminders == null || reminders.isEmpty()) {
            return Collections.emptyList();
        }
        if (reminders.size() > MAX_PERSONAL_REMINDERS) {
            throw new BusinessException(ScheduleErrorCode.PERSONAL_REMINDER_LIMIT_EXCEEDED);
        }
        List<PersonalScheduleReminderEntity> entities = reminders.stream()
                .map(minutes -> PersonalScheduleReminderEntity.builder()
                        .scheduleId(scheduleId)
                        .remindBeforeMinutes(minutes)
                        .build())
                .toList();
        reminderRepository.saveAll(entities);
        return new ArrayList<>(reminders);
    }

    private List<Integer> loadReminders(Long scheduleId) {
        return reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(scheduleId)
                .stream()
                .map(PersonalScheduleReminderEntity::getRemindBeforeMinutes)
                .toList();
    }

    /**
     * 繰り返しスケジュールの更新処理を行う。
     */
    private void updateRecurringSchedule(ScheduleEntity schedule, UpdatePersonalScheduleRequest req,
                                          String updateScope) {
        switch (updateScope) {
            case UPDATE_SCOPE_THIS_ONLY -> {
                applyUpdateToSchedule(schedule, req);
                if (schedule.getParentScheduleId() != null) {
                    ScheduleEntity marked = schedule.toBuilder().isException(true).build();
                    scheduleRepository.save(marked);
                }
            }
            case UPDATE_SCOPE_THIS_AND_FOLLOWING -> {
                applyUpdateToSchedule(schedule, req);
                updateFollowingSchedules(schedule, req);
            }
            case UPDATE_SCOPE_ALL -> {
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
     * スケジュールに更新リクエストの内容を適用する。個人スケジュール固定値は無視する。
     */
    private void applyUpdateToSchedule(ScheduleEntity schedule, UpdatePersonalScheduleRequest req) {
        ScheduleEntity.ScheduleEntityBuilder builder = schedule.toBuilder();

        if (req.getTitle() != null) builder.title(req.getTitle());
        if (req.getDescription() != null) builder.description(req.getDescription());
        if (req.getLocation() != null) builder.location(req.getLocation());
        if (req.getStartAt() != null) builder.startAt(req.getStartAt());
        if (req.getEndAt() != null) builder.endAt(req.getEndAt());
        if (req.getAllDay() != null) builder.allDay(req.getAllDay());
        if (req.getEventType() != null) builder.eventType(EventType.valueOf(req.getEventType()));
        if (req.getColor() != null) builder.color(req.getColor());

        // 個人スケジュール固定値は変更不可（無視）

        ScheduleEntity updated = builder.build();
        scheduleRepository.save(updated);
    }

    /**
     * 指定スケジュール以降の子スケジュールを更新する（例外は除く）。
     */
    private void updateFollowingSchedules(ScheduleEntity schedule, UpdatePersonalScheduleRequest req) {
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
    private void updateAllChildSchedules(Long parentId, UpdatePersonalScheduleRequest req) {
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
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RecurrenceRuleDto.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ScheduleErrorCode.INVALID_RECURRENCE_RULE);
        }
    }

    /**
     * エンティティを個人スケジュールレスポンスDTOに変換する。
     */
    private PersonalScheduleResponse toPersonalScheduleResponse(ScheduleEntity entity,
                                                                 List<Integer> reminders) {
        String createdByDisplayName = nameResolverService.resolveUserDisplayName(entity.getCreatedBy());
        return new PersonalScheduleResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getLocation(),
                entity.getStartAt(),
                entity.getEndAt(),
                entity.getAllDay(),
                entity.getEventType().name(),
                entity.getColor(),
                entity.getStatus().name(),
                entity.getParentScheduleId(),
                deserializeRecurrenceRule(entity.getRecurrenceRule()),
                entity.getIsException(),
                reminders,
                entity.getGoogleCalendarEventId() != null,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                createdByDisplayName);
    }
}
