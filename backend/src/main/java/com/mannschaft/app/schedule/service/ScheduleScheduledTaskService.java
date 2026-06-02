package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import com.mannschaft.app.schedule.dto.ScheduledAttendanceRequest;
import com.mannschaft.app.schedule.dto.ScheduledSurveyRequest;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
                String payload = serialize(survey.getSurvey());
                ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                        .scheduleId(scheduleId)
                        .organizationId(organizationId)
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .taskType(ScheduledTaskType.SURVEY)
                        .scheduledAt(survey.getScheduledAt())
                        .status(ScheduledTaskStatus.PENDING)
                        .payloadJson(payload)
                        .createdBy(createdBy)
                        .build();
                scheduledTaskRepository.save(task);
            }
        }

        if (attendance != null) {
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
                    .scheduledAt(attendance.getScheduledAt())
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
     * 予約出欠募集のペイロード（materialize 時に survey ではなく出欠設定として復元する）。
     *
     * <p>JSON 直列化のため getter/no-arg constructor を Jackson が利用できるよう record で表現する。</p>
     *
     * @param attendanceDeadline 出欠回答期限（任意）
     * @param commentOption      コメント要否（任意）
     * @param minResponseRole    最低応答ロール（任意）
     */
    public record AttendancePayload(
            java.time.LocalDateTime attendanceDeadline,
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
