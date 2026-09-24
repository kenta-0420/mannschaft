package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import com.mannschaft.app.schedule.dto.AttendanceSolicitationSettings;
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
import java.time.ZoneId;
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

    /**
     * 予定本体（{@code schedules}）が日時を保持するタイムゾーン。
     * payload の TZ 付き締切をここへ変換して適用する（{@code ScheduleScheduledTaskService} と対）。
     * サーバー保持形式の正準定義は {@link UserZoneLocalDateTimeParser#SERVER_ZONE} を参照。
     */
    private static final ZoneId STORAGE_ZONE = UserZoneLocalDateTimeParser.SERVER_ZONE;

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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予定に紐づく予約タスクの実行。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
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
     * ATTENDANCE: payload を {@link ScheduleScheduledTaskService.AttendancePayload} にデシリアライズし、
     * 予約時に指定された出欠設定（締切・コメント要否・最低応答ロール）を添えて
     * {@code ScheduleAttendanceService.openAttendanceSolicitation} を呼ぶ。</p>
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
            Long surveyId = detail.getId();
            surveyService.publishSurvey(scopeType, current.getScopeId(), surveyId);
            current.markCreated(surveyId);
            scheduledTaskRepository.save(current);
            log.info("予約アンケートmaterialize: taskId={}, surveyId={}", current.getId(), surveyId);
        } else if (current.getTaskType() == ScheduledTaskType.ATTENDANCE) {
            AttendanceSolicitationSettings settings = toAttendanceSettings(current.getPayloadJson());
            scheduleAttendanceService.openAttendanceSolicitation(current.getScheduleId(), settings);
            current.markCreated(current.getScheduleId());
            scheduledTaskRepository.save(current);
            log.info("予約出欠募集materialize: taskId={}, scheduleId={}, deadline={}, commentOption={}, minResponseRole={}",
                    current.getId(), current.getScheduleId(), settings.attendanceDeadline(),
                    settings.commentOption(), settings.minResponseRole());
        }
    }

    /**
     * 予約出欠募集の {@code payload_json} を、予定へ適用する出欠設定に復元する
     * （機能55 / Issue #2508 欠陥B）。
     *
     * <p><b>回帰防止</b>: 以前はこの復元処理そのものが無く、ユーザーが指定した回答締切・
     * コメント要否・最低応答ロールは payload に書かれるだけで一切適用されなかった。</p>
     *
     * <p>payload が空（旧データや設定なしの予約）の場合は {@link AttendanceSolicitationSettings#NONE}
     * を返し、予定の既存設定をそのまま使う。JSON が壊れている・enum 名が不正といった
     * 「本当に異常な」ケースは例外を伝播させ、{@code attempt_count} / {@code last_error} に
     * 記録されるようにする（対処療法で握り潰さない）。</p>
     *
     * @param payloadJson 予約タスクの payload（null / 空可）
     * @return 適用する出欠設定（null は返さない）
     * @throws JsonProcessingException payload が JSON として壊れている場合
     */
    private AttendanceSolicitationSettings toAttendanceSettings(String payloadJson)
            throws JsonProcessingException {
        if (payloadJson == null || payloadJson.isBlank()) {
            return AttendanceSolicitationSettings.NONE;
        }

        ScheduleScheduledTaskService.AttendancePayload payload =
                objectMapper.readValue(payloadJson, ScheduleScheduledTaskService.AttendancePayload.class);

        // 締切は TZ 付きで保持されている。予定本体は JST の LocalDateTime で持つため変換する。
        LocalDateTime deadlineJst = payload.attendanceDeadline() == null ? null
                : payload.attendanceDeadline().atZoneSameInstant(STORAGE_ZONE).toLocalDateTime();

        return new AttendanceSolicitationSettings(
                deadlineJst,
                payload.commentOption() == null ? null : CommentOption.valueOf(payload.commentOption()),
                payload.minResponseRole() == null ? null
                        : MinResponseRole.valueOf(payload.minResponseRole()));
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
