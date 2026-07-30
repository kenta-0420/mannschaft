package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.config.jackson.LenientOffsetDateTimeDeserializer;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import com.mannschaft.app.schedule.dto.ScheduledAttendanceRequest;
import com.mannschaft.app.schedule.dto.ScheduledSurveyRequest;
import com.mannschaft.app.schedule.dto.ScheduledTaskResponse;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 予約タスク（機能55 第二陣）登録・取消・取得サービス。
 *
 * <p>予定作成時に「予約アンケート」「予約出欠募集」を {@link ScheduleScheduledTaskEntity}
 * （status=PENDING）として保存する。payload は {@link ObjectMapper} で JSON 直列化して
 * {@code payloadJson} に格納し、materialize 時に後続バッチ
 * （{@code ScheduleScheduledTaskBatchService}）がデシリアライズして実体を生成する。</p>
 *
 * <p>本サービスは schedule ドメイン内に閉じる。survey ドメインへの越境（materialize）は
 * バッチ側に分離してある（CLAUDE.md 原則5）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleScheduledTaskService {

    /** ScheduledAt 保存時に OffsetDateTime を変換する先のタイムゾーン（バッチ側は LocalDateTime.now()=JST と比較）。 */
    private static final ZoneId STORAGE_ZONE = ZoneId.of("Asia/Tokyo");

    private final ScheduleScheduledTaskRepository scheduledTaskRepository;
    private final ObjectMapper objectMapper;

    /**
     * 予定に紐づく予約タスク（予約アンケート / 予約出欠募集）を登録する。
     *
     * <p>各要素を PENDING 状態の {@link ScheduleScheduledTaskEntity} として保存する。
     * survey のスナップショットは {@code payloadJson} に JSON で格納する。</p>
     *
     * @param scheduleId     親予定 schedules.id
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ実体 ID（team_id または organization_id）
     * @param organizationId テナントキー（team 予定なら所属組織 id）
     * @param createdBy      作成者 users.id（任意）
     * @param surveys        予約アンケート定義（null/空可）
     * @param attendance     予約出欠募集設定（null 可）
     */
    @Transactional
    public void registerTasks(Long scheduleId, CalendarSyncScopeType scopeType, Long scopeId,
                              Long organizationId, Long createdBy,
                              List<ScheduledSurveyRequest> surveys,
                              ScheduledAttendanceRequest attendance) {
        if (surveys != null) {
            for (ScheduledSurveyRequest survey : surveys) {
                // OffsetDateTime → Asia/Tokyo の LocalDateTime に変換（バッチはJSTの LocalDateTime.now() と比較）
                LocalDateTime scheduledAtJst = survey.getScheduledAt()
                        .atZoneSameInstant(STORAGE_ZONE).toLocalDateTime();
                String payload = serialize(survey.getSurvey());
                ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                        .scheduleId(scheduleId)
                        .organizationId(organizationId)
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .taskType(ScheduledTaskType.SURVEY)
                        .scheduledAt(scheduledAtJst)
                        .status(ScheduledTaskStatus.PENDING)
                        .payloadJson(payload)
                        .createdBy(createdBy)
                        .build();
                scheduledTaskRepository.save(task);
            }
        }

        if (attendance != null) {
            // OffsetDateTime → Asia/Tokyo の LocalDateTime に変換
            LocalDateTime scheduledAtJst = attendance.getScheduledAt()
                    .atZoneSameInstant(STORAGE_ZONE).toLocalDateTime();
            String payload = serialize(new AttendancePayload(
                    attendance.getAttendanceDeadline(),
                    attendance.getCommentOption(),
                    attendance.getMinResponseRole()));
            ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(scheduleId)
                    .organizationId(organizationId)
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .taskType(ScheduledTaskType.ATTENDANCE)
                    .scheduledAt(scheduledAtJst)
                    .status(ScheduledTaskStatus.PENDING)
                    .payloadJson(payload)
                    .createdBy(createdBy)
                    .build();
            scheduledTaskRepository.save(task);
        }

        log.info("予約タスク登録: scheduleId={}, surveys={}, attendance={}",
                scheduleId, surveys != null ? surveys.size() : 0, attendance != null);
    }

    /**
     * 予定編集時に予約タスクを差分更新する（機能55 BE対応）。
     *
     * <p>surveys または attendance が非 null の場合、対応する PENDING タスクをすべて CANCELLED にしてから
     * 新規タスクを登録する（差し替え）。null は「変更なし」として処理しない。
     * 空リストは「全削除」として CANCEL のみ行い新規登録をしない。</p>
     *
     * @param scheduleId     親予定 schedules.id
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ実体 ID
     * @param organizationId テナントキー
     * @param updatedBy      更新者 users.id
     * @param surveys        予約アンケート（null = 変更なし、空リスト = PENDING 全 CANCEL）
     * @param attendance     予約出欠募集（null = 変更なし）
     */
    @Transactional
    public void updateTasksForSchedule(Long scheduleId, CalendarSyncScopeType scopeType, Long scopeId,
                                       Long organizationId, Long updatedBy,
                                       List<ScheduledSurveyRequest> surveys,
                                       ScheduledAttendanceRequest attendance) {
        // どちらも null なら何もしない（部分更新セマンティクス）
        if (surveys == null && attendance == null) {
            return;
        }

        List<ScheduleScheduledTaskEntity> existingTasks =
                scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(scheduleId);

        // surveys が非null → PENDING の SURVEY タスクを全 CANCEL
        if (surveys != null) {
            existingTasks.stream()
                    .filter(t -> t.getTaskType() == ScheduledTaskType.SURVEY
                            && t.getStatus() == ScheduledTaskStatus.PENDING)
                    .forEach(t -> {
                        t.cancel();
                        scheduledTaskRepository.save(t);
                    });
            // 非空リストのみ再登録
            if (!surveys.isEmpty()) {
                registerTasks(scheduleId, scopeType, scopeId, organizationId, updatedBy, surveys, null);
            }
        }

        // attendance が非null → PENDING の ATTENDANCE タスクを全 CANCEL
        if (attendance != null) {
            existingTasks.stream()
                    .filter(t -> t.getTaskType() == ScheduledTaskType.ATTENDANCE
                            && t.getStatus() == ScheduledTaskStatus.PENDING)
                    .forEach(t -> {
                        t.cancel();
                        scheduledTaskRepository.save(t);
                    });
            // 新規登録
            registerTasks(scheduleId, scopeType, scopeId, organizationId, updatedBy, null, attendance);
        }

        log.info("予約タスク差分更新: scheduleId={}, surveys={}, attendance={}",
                scheduleId, surveys != null ? surveys.size() : "null", attendance != null);
    }

    /**
     * 予定キャンセル時に、当該予定の PENDING 予約タスクをすべて取り消す（→ CANCELLED）。
     *
     * <p>既に materialize 済み（CREATED）・失敗（FAILED）・取消済み（CANCELLED）のタスクは対象外。</p>
     *
     * @param scheduleId 親予定 schedules.id
     */
    @Transactional
    public void cancelTasksForSchedule(Long scheduleId) {
        List<ScheduleScheduledTaskEntity> tasks =
                scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(scheduleId);
        int cancelled = 0;
        for (ScheduleScheduledTaskEntity task : tasks) {
            if (task.getStatus() == ScheduledTaskStatus.PENDING) {
                task.cancel();
                scheduledTaskRepository.save(task);
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("予約タスク取消: scheduleId={}, 件数={}", scheduleId, cancelled);
        }
    }

    /**
     * 予定に紐づく予約タスク一覧を取得する（予定詳細表示用）。
     *
     * @param scheduleId 親予定 schedules.id
     * @return 予約タスク一覧（論理削除を除く）
     */
    public List<ScheduleScheduledTaskEntity> findTasksForSchedule(Long scheduleId) {
        return List.copyOf(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(scheduleId));
    }

    /**
     * 予定に紐づく予約タスクをレスポンス DTO 一覧に変換して取得する（予定詳細表示用）。
     *
     * <p>PENDING を含む全状態を返す。FE で「予約アンケート ○月○日 作成予定」「作成済み」等の
     * 表示を出し分けるために使用する。</p>
     *
     * @param scheduleId 親予定 schedules.id
     * @return 予約タスクのレスポンス DTO 一覧（論理削除を除く）
     */
    public List<ScheduledTaskResponse> findTaskResponsesForSchedule(Long scheduleId) {
        return scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(scheduleId).stream()
                .map(ScheduleScheduledTaskService::toResponse)
                .toList();
    }

    /**
     * 予約タスクを単体で取り消す（機能55 第三陣）。
     *
     * <p>PENDING のタスクのみ取消可能（→ CANCELLED）。スコープ（{@code scopeType}/{@code scopeId}）が
     * パスのスコープと一致しないタスクは「見つからない」（404）として扱い、IDOR を防止する。
     * 既に CREATED/CANCELLED/FAILED のタスクは取消不能（409）。</p>
     *
     * @param taskId    予約タスク id（UUIDv7）
     * @param scopeType パスのスコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   パスのスコープ実体 ID（team_id または organization_id）
     */
    @Transactional
    public void cancelTask(UUID taskId, CalendarSyncScopeType scopeType, Long scopeId) {
        ScheduleScheduledTaskEntity task = scheduledTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULED_TASK_NOT_FOUND));

        // スコープ不一致は IDOR 対策で 404（存在を隠蔽）
        if (task.getScopeType() != scopeType || !scopeId.equals(task.getScopeId())) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }

        // PENDING 以外は取り消せない（既に materialize 済み等）→ 409
        if (task.getStatus() != ScheduledTaskStatus.PENDING) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULED_TASK_NOT_CANCELLABLE);
        }

        task.cancel();
        scheduledTaskRepository.save(task);
        log.info("予約タスク単体取消: taskId={}, scopeType={}, scopeId={}", taskId, scopeType, scopeId);
    }

    /**
     * 予約タスクエンティティをレスポンス DTO に変換する。
     */
    private static ScheduledTaskResponse toResponse(ScheduleScheduledTaskEntity entity) {
        return ScheduledTaskResponse.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .taskType(entity.getTaskType() != null ? entity.getTaskType().name() : null)
                .scheduledAt(entity.getScheduledAt())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .materializedEntityId(entity.getMaterializedEntityId())
                .build();
    }

    /**
     * 予約出欠募集のペイロード（materialize 時に survey ではなく出欠設定として復元する）。
     *
     * <p>JSON 直列化のため getter/no-arg constructor を Jackson が利用できるよう record で表現する。</p>
     *
     * <p><b>Issue #2508 早馬（後方互換）</b>: {@code attendanceDeadline} は元々
     * {@code LocalDateTime} 宣言だったが、直列化に使う {@link ObjectMapper} には
     * {@code LocalDateTimeTimezoneSerializer}（ユーザー TZ でオフセット付き文字列を書き出す）が
     * 登録されているため、<b>書き込みはオフセット付き・読み出しはオフセット無ししか受け付けない</b>
     * という非対称になっていた。既存行はオフセット付き（{@code +09:00} / {@code -04:00} など TZ 混在）で
     * 溜まっているため、{@link OffsetDateTime} で受けたうえで
     * {@link com.mannschaft.app.config.jackson.LenientOffsetDateTimeDeserializer} により
     * オフセット無しの行も JST として読めるようにしている。</p>
     *
     * @param attendanceDeadline 出欠回答期限（任意・TZ 付き）
     * @param commentOption      コメント要否（任意）
     * @param minResponseRole    最低応答ロール（任意）
     */
    public record AttendancePayload(
            @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class)
            OffsetDateTime attendanceDeadline,
            String commentOption,
            String minResponseRole) {
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 対処療法禁止: 直列化に失敗したら予約自体を成立させない（壊れた予約を保存しない）
            log.error("予約タスクの payload 直列化に失敗: {}", e.getMessage());
            throw new BusinessException(ScheduleErrorCode.SCHEDULED_TASK_PAYLOAD_SERIALIZATION_FAILED);
        }
    }
}
