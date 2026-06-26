package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.AttendanceGenerationStatus;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ReminderKind;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.BatchDeleteResponse;
import com.mannschaft.app.schedule.dto.CreatePersonalScheduleRequest;
import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.dto.RecurrenceRuleDto;
import com.mannschaft.app.schedule.dto.ReminderResponse;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    /** 個人スケジュールの相対・絶対を合算したリマインダー上限件数（機能55 第二陣で 3→5 拡張）。 */
    private static final int MAX_TOTAL_PERSONAL_REMINDERS = CreatePersonalScheduleRequest.MAX_TOTAL_REMINDERS;
    private static final String SCOPE_TYPE_PERSONAL = "PERSONAL";
    /** リマインダー保存時に OffsetDateTime を変換する先のタイムゾーン（JVM TZ と一致）。 */
    private static final ZoneId STORAGE_ZONE = ZoneId.of("Asia/Tokyo");
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
        // OffsetDateTime → JST LocalDateTime に変換
        LocalDateTime startAtJst = toJst(req.getStartAt());
        LocalDateTime endAtJst = toJst(req.getEndAt());
        validateDateRange(startAtJst, endAtJst);

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
                .startAt(startAtJst)
                .endAt(endAtJst)
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

        List<Integer> savedReminders = saveReminders(
                schedule.getId(), req.getReminders(), req.getAbsoluteReminders());

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
        // 詳細 GET では相対・絶対 両方のリマインダーを露出する（足軽3 時点で詳細にリマインダーが
        // 一切載っていなかった不足の根治）。relative 分（分数）は後方互換の reminders にも反映する。
        return toPersonalScheduleResponse(schedule, loadReminders(scheduleId), loadDetailedReminders(scheduleId));
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
            LocalDateTime startAt = req.getStartAt() != null ? toJst(req.getStartAt()) : schedule.getStartAt();
            LocalDateTime endAt = req.getEndAt() != null ? toJst(req.getEndAt()) : schedule.getEndAt();
            validateDateRange(startAt, endAt);
        }

        String updateScope = req.getUpdateScopeOrDefault();

        if (schedule.isRecurring() || schedule.getParentScheduleId() != null) {
            updateRecurringSchedule(schedule, req, updateScope);
        } else {
            applyUpdateToSchedule(schedule, req);
        }

        schedule = scheduleRepository.save(schedule);

        // 機能55 BE対応: 相対リマインダー（reminders）と絶対リマインダー（absoluteReminders）の更新
        // どちらかが非nullなら saveReminders で差し替え。両方 null なら既存を保持。
        List<Integer> updatedReminders;
        if (req.getReminders() != null || req.getAbsoluteReminders() != null) {
            // saveReminders は「既存削除→再登録」の差し替えセマンティクス。
            // null を渡すと「変更なし（空扱い）」となるが、本メソッドは非nullが確定しているため
            // どちらか一方が null の場合は空リストとして扱う（既存の値を維持したい場合は null を指定）。
            List<Integer> relativeReminders = req.getReminders();
            List<OffsetDateTime> absoluteReminders = req.getAbsoluteReminders();
            updatedReminders = saveReminders(schedule.getId(), relativeReminders, absoluteReminders);
        } else {
            updatedReminders = loadReminders(schedule.getId());
        }

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

    /**
     * 個人スケジュールのリマインダーを保存する（相対・絶対両対応）。
     *
     * <p>相対指定（開始N分前）は {@link ReminderKind#RELATIVE}、絶対指定（固定日時）は
     * {@link ReminderKind#ABSOLUTE} として保存する。相対・絶対の合算件数は最大
     * {@link #MAX_TOTAL_PERSONAL_REMINDERS} 件。返り値は後方互換のため相対分（分）のみを返す
     * （絶対分のレスポンス露出は FE 拡張に委ねる）。</p>
     *
     * <p>absoluteReminders は OffsetDateTime で受け取り、JVM TZ（Asia/Tokyo）へ変換して
     * LocalDateTime として保存する。バッチ側は {@code LocalDateTime.now()}（JVM=JST）と比較するため
     * 保存側も JST に統一する（タイムゾーン不一致による通知漏れ・二重通知を防止）。</p>
     *
     * @param scheduleId        スケジュールID
     * @param reminders         相対指定リマインダー（開始N分前）
     * @param absoluteReminders 絶対指定リマインダー（OffsetDateTime: クライアントTZ付き）
     * @return 保存した相対指定リマインダー（分）の一覧
     */
    private List<Integer> saveReminders(Long scheduleId, List<Integer> reminders,
                                        List<OffsetDateTime> absoluteReminders) {
        reminderRepository.deleteByScheduleId(scheduleId);

        List<Integer> relative = reminders != null ? reminders : Collections.emptyList();
        List<OffsetDateTime> absolute = absoluteReminders != null ? absoluteReminders : Collections.emptyList();

        if (relative.isEmpty() && absolute.isEmpty()) {
            return Collections.emptyList();
        }
        if (relative.size() + absolute.size() > MAX_TOTAL_PERSONAL_REMINDERS) {
            throw new BusinessException(ScheduleErrorCode.PERSONAL_REMINDER_LIMIT_EXCEEDED);
        }

        List<PersonalScheduleReminderEntity> entities = new ArrayList<>();
        relative.forEach(minutes -> entities.add(PersonalScheduleReminderEntity.builder()
                .scheduleId(scheduleId)
                .remindBeforeMinutes(minutes)
                .reminderKind(ReminderKind.RELATIVE)
                .build()));
        // OffsetDateTime → JSTのLocalDateTimeに変換して保存
        absolute.forEach(remindAt -> entities.add(PersonalScheduleReminderEntity.builder()
                .scheduleId(scheduleId)
                .remindAt(remindAt.atZoneSameInstant(STORAGE_ZONE).toLocalDateTime())
                .reminderKind(ReminderKind.ABSOLUTE)
                .build()));

        reminderRepository.saveAll(entities);
        return new ArrayList<>(relative);
    }

    private List<Integer> loadReminders(Long scheduleId) {
        return reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(scheduleId)
                .stream()
                .filter(r -> r.getReminderKind() == ReminderKind.RELATIVE)
                .map(PersonalScheduleReminderEntity::getRemindBeforeMinutes)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 個人スケジュールのリマインダー詳細（相対・絶対 両方）を {@link ReminderResponse} 一覧で取得する。
     *
     * <p>機能55 第三陣: 詳細 GET で相対（remindBeforeMinutes）と絶対（remindAt）の両方を露出する。
     * 個人リマインダーは送信フラグとして {@code notified} を持つ（{@code isSent}/{@code sentAt} は持たない）。</p>
     */
    private List<ReminderResponse> loadDetailedReminders(Long scheduleId) {
        return reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(scheduleId)
                .stream()
                .map(r -> ReminderResponse.builder()
                        .id(r.getId())
                        .reminderKind(r.getReminderKind() != null ? r.getReminderKind().name() : null)
                        .remindAt(r.getRemindAt())
                        .remindBeforeMinutes(r.getRemindBeforeMinutes())
                        .notified(r.getNotified())
                        .build())
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
                    // toBuilder().isException(true).build() は BaseEntity の id を引き継がないため、
                    // 直接フィールド変更方式で例外フラグを立てる
                    schedule.markAsException();
                }
                // save は呼び出し元（updatePersonalSchedule）に委ねる
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
     * toBuilder().build() は BaseEntity の id を引き継がないため、
     * 直接フィールド変更方式（applyPersonalScheduleUpdate）を使用する。
     * save() は呼び出し元に委ねる。
     *
     * <p>startAt / endAt は OffsetDateTime → JST LocalDateTime に変換してから適用する。</p>
     */
    private void applyUpdateToSchedule(ScheduleEntity schedule, UpdatePersonalScheduleRequest req) {
        EventType eventType = req.getEventType() != null ? EventType.valueOf(req.getEventType()) : null;
        schedule.applyPersonalScheduleUpdate(
                req.getTitle(),
                req.getDescription(),
                req.getLocation(),
                toJst(req.getStartAt()),
                toJst(req.getEndAt()),
                req.getAllDay(),
                eventType,
                req.getColor()
        );
        // 個人予定の繰り返しルール更新（非 null のときのみ上書き）
        // FE は recurrence=true のとき非 null オブジェクトを送信し、それ以外は省略する
        if (req.getRecurrenceRule() != null) {
            schedule.setRecurrenceRule(serializeRecurrenceRule(req.getRecurrenceRule()));
        }
        // 個人スケジュール固定値は変更不可（無視）
        // save() は呼び出し元（updatePersonalSchedule / updateFollowingSchedules / updateAllChildSchedules）で実行する
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
                .forEach(child -> {
                    applyUpdateToSchedule(child, req);
                    scheduleRepository.save(child);
                });
    }

    /**
     * 親スケジュールの全子を更新する（例外は除く）。
     */
    private void updateAllChildSchedules(Long parentId, UpdatePersonalScheduleRequest req) {
        List<ScheduleEntity> children = scheduleRepository
                .findByParentScheduleIdOrderByStartAtAsc(parentId);

        children.stream()
                .filter(child -> !child.getIsException())
                .forEach(child -> {
                    applyUpdateToSchedule(child, req);
                    scheduleRepository.save(child);
                });
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
     * OffsetDateTime を JST の LocalDateTime に変換する。
     *
     * <p>クライアントから受け取った TZ 付き日時を {@link #STORAGE_ZONE}（Asia/Tokyo）に変換する。
     * null の場合は null を返す（部分更新セマンティクスを壊さないため）。</p>
     *
     * @param odt クライアント TZ 付き日時
     * @return JST LocalDateTime、または null
     */
    private static LocalDateTime toJst(OffsetDateTime odt) {
        if (odt == null) return null;
        return odt.atZoneSameInstant(STORAGE_ZONE).toLocalDateTime();
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
        return toPersonalScheduleResponse(entity, reminders, null);
    }

    /**
     * エンティティを個人スケジュールレスポンスDTOに変換する（リマインダー詳細つき・機能55 第三陣）。
     *
     * @param entity            スケジュールエンティティ
     * @param reminders         相対指定リマインダー（分）— 後方互換フィールド
     * @param detailedReminders リマインダー詳細（相対・絶対 両方）。一覧では null
     */
    private PersonalScheduleResponse toPersonalScheduleResponse(ScheduleEntity entity,
                                                                 List<Integer> reminders,
                                                                 List<ReminderResponse> detailedReminders) {
        String createdByDisplayName = nameResolverService.resolveUserDisplayName(entity.getCreatedBy());
        return PersonalScheduleResponse.builder()
                .id(entity.getId())
                .content(new PersonalScheduleResponse.PersonalContentDto(
                        entity.getTitle(), entity.getDescription(), entity.getEventType().name(),
                        entity.getColor(), entity.getLocation()))
                .time(new PersonalScheduleResponse.PersonalTimeDto(
                        entity.getStartAt(), entity.getEndAt(), entity.getAllDay()))
                .status(new PersonalScheduleResponse.PersonalStatusDto(
                        entity.getStatus().name(), entity.getIsException(), entity.getParentScheduleId(),
                        deserializeRecurrenceRule(entity.getRecurrenceRule()),
                        entity.getGoogleCalendarEventId() != null))
                .reminders(reminders)
                .detailedReminders(detailedReminders)
                .audit(new PersonalScheduleResponse.PersonalAuditDto(
                        entity.getCreatedAt(), entity.getUpdatedAt(), createdByDisplayName))
                .build();
    }
}
