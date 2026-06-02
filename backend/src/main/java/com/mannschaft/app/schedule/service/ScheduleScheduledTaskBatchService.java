package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.service.SurveyService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 予約タスク materialize バッチ（機能55 第二陣）。
 *
 * <p>1 分間隔で PENDING かつ {@code scheduledAt} 到来済みの予約タスクを取得し、
 * SURVEY なら集計可能な survey を生成・公開、ATTENDANCE なら出欠募集を開始する。
 * 手本: {@code ConfirmableNotificationReminderBatchService}。</p>
 *
 * <p><b>per-item トランザクション分離</b>: 各タスクの materialize は
 * {@link #materializeOne(ScheduleScheduledTaskEntity)} で独立したトランザクション
 * （{@link Propagation#REQUIRES_NEW}）として実行する。1 件の失敗が他の materialize を
 * ロールバックしない。失敗は {@link ScheduleScheduledTaskEntity#markFailed(String)} で
 * 記録し（対処療法でエラーを握り潰さない）、{@code attempt_count} 上限を超えたら FAILED 確定。</p>
 *
 * <p>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 */
@Slf4j
@Service
public class ScheduleScheduledTaskBatchService {

    /** materialize の最大試行回数。これを超えたら FAILED 確定（無限リトライ防止）。 */
    public static final int MAX_ATTEMPTS = 5;

    private final ScheduleScheduledTaskRepository scheduledTaskRepository;
    private final ObjectMapper objectMapper;
    private final SurveyService surveyService;
    private final ScheduleAttendanceService scheduleAttendanceService;

    /**
     * 自己参照プロキシ。{@code @Transactional(REQUIRES_NEW)} を効かせるため、self-invocation を避けて
     * プロキシ経由で {@link #materializeOne}/{@link #recordFailure} を呼ぶ。{@link Lazy} で循環参照を回避。
     */
    private final ScheduleScheduledTaskBatchService self;

    public ScheduleScheduledTaskBatchService(
            ScheduleScheduledTaskRepository scheduledTaskRepository,
            ObjectMapper objectMapper,
            SurveyService surveyService,
            ScheduleAttendanceService scheduleAttendanceService,
            @Lazy ScheduleScheduledTaskBatchService self) {
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.objectMapper = objectMapper;
        this.surveyService = surveyService;
        this.scheduleAttendanceService = scheduleAttendanceService;
        this.self = self;
    }

    /**
     * 予約タスク materialize バッチを実行する。
     */
    @BatchEndpoint(
            name = "schedule-scheduled-task",
            description = "予約アンケート・予約出欠募集を scheduledAt 到来時に materialize する（1分毎）")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(
            name = "scheduleScheduledTaskBatch",
            lockAtLeastFor = "PT50S",
            lockAtMostFor = "PT2M")
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleScheduledTaskEntity> due = scheduledTaskRepository
                .findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(ScheduledTaskStatus.PENDING, now);

        if (due.isEmpty()) {
            return;
        }

        int created = 0;
        int failed = 0;
        for (ScheduleScheduledTaskEntity task : due) {
            try {
                // プロキシ経由で呼び REQUIRES_NEW を効かせる（1件のロールバックを他に波及させない）
                self.materializeOne(task);
                created++;
            } catch (Exception e) {
                // per-item トランザクションは materializeOne 側でロールバックされる。
                // ここでは失敗をタスクに記録するための別トランザクションを開く。
                failed++;
                self.recordFailure(task.getId(), e.getMessage());
            }
        }

        log.info("予約タスクmaterializeバッチ完了: due={}, created={}, failed={}",
                due.size(), created, failed);
    }

    /**
     * 単一の予約タスクを materialize する（独立トランザクション）。
     *
     * <p>SURVEY: payload を {@link CreateSurveyRequest} にデシリアライズし、
     * {@code SurveyService.createSurvey/publishSurvey} で集計可能な survey を生成・公開する。<br>
     * ATTENDANCE: {@code ScheduleAttendanceService.openAttendanceSolicitation} を呼ぶ。</p>
     *
     * @param task 対象タスク（PENDING）
     * @throws Exception materialize に失敗した場合（呼び出し元で markFailed する）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void materializeOne(ScheduleScheduledTaskEntity task) throws Exception {
        // 取り直して最新状態を確認（並行バッチ・取消との競合に備える）
        ScheduleScheduledTaskEntity current = scheduledTaskRepository.findById(task.getId()).orElse(null);
        if (current == null || current.getStatus() != ScheduledTaskStatus.PENDING) {
            return;
        }

        if (current.getTaskType() == ScheduledTaskType.SURVEY) {
            String scopeType = toScopeTypeString(current);
            CreateSurveyRequest request = objectMapper.readValue(
                    current.getPayloadJson(), CreateSurveyRequest.class);
            SurveyDetailResponse detail = surveyService.createSurvey(
                    scopeType, current.getScopeId(), current.getCreatedBy(), request);
            Long surveyId = detail.getSurvey().getId();
            surveyService.publishSurvey(scopeType, current.getScopeId(), surveyId);
            current.markCreated(surveyId);
            scheduledTaskRepository.save(current);
            log.info("予約アンケートmaterialize: taskId={}, surveyId={}", current.getId(), surveyId);
        } else if (current.getTaskType() == ScheduledTaskType.ATTENDANCE) {
            scheduleAttendanceService.openAttendanceSolicitation(current.getScheduleId());
            current.markCreated(current.getScheduleId());
            scheduledTaskRepository.save(current);
            log.info("予約出欠募集materialize: taskId={}, scheduleId={}",
                    current.getId(), current.getScheduleId());
        }
    }

    /**
     * materialize 失敗をタスクに記録する（独立トランザクション）。
     *
     * <p>{@code attempt_count} を加算し、上限到達なら FAILED のまま PENDING から外す。
     * 上限未満なら次回バッチで再試行できるよう PENDING に戻す。</p>
     *
     * @param taskId 対象タスク ID
     * @param error  失敗理由
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(java.util.UUID taskId, String error) {
        ScheduleScheduledTaskEntity task = scheduledTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        task.recordFailedAttempt(error, MAX_ATTEMPTS);
        scheduledTaskRepository.save(task);
        if (task.getStatus() == ScheduledTaskStatus.FAILED) {
            log.warn("予約タスクmaterialize失敗（試行打ち止め・FAILED確定 {}/{}）: taskId={}, error={}",
                    task.getAttemptCount(), MAX_ATTEMPTS, taskId, error);
        } else {
            log.warn("予約タスクmaterialize失敗（再試行予定 {}/{}）: taskId={}, error={}",
                    task.getAttemptCount(), MAX_ATTEMPTS, taskId, error);
        }
    }

    private String toScopeTypeString(ScheduleScheduledTaskEntity task) {
        return switch (task.getScopeType()) {
            case TEAM -> "TEAM";
            case ORGANIZATION -> "ORGANIZATION";
        };
    }
}
