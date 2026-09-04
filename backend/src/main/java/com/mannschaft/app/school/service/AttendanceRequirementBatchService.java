package com.mannschaft.app.school.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.event.AttendanceRequirementStatusChangedEvent;
import com.mannschaft.app.school.event.AttendanceWeeklyRiskDigestReadyEvent;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import com.mannschaft.app.school.repository.StudentAttendanceSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus.RISK;
import static com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus.VIOLATION;

/**
 * 出席要件評価バッチサービス（F03.13 Phase 14）。
 *
 * <p>日次バッチ（毎朝6時）と週次ダイジェスト通知（毎週月曜7時）を提供する。
 * 分散排他制御は {@code ShedLockConfig} の {@code @EnableSchedulerLock} に委譲する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceRequirementBatchService {

    private final AttendanceRequirementRuleRepository ruleRepository;
    private final StudentAttendanceSummaryRepository summaryRepository;
    private final AttendanceRequirementEvaluationService evaluationService;
    private final AttendanceRequirementEvaluationRepository evaluationRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 日次評価バッチ（毎朝6時実行）。
     *
     * <p>全ACTIVE規程の対象生徒を一括評価し、ステータス変化があれば教員に通知する。
     * 1件の評価が失敗しても例外をキャッチして次の生徒に進む設計とする。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_FAMILY_CARE_ENABLED",
            reason = "出席要件の評価は出席記録から何度でも再計算できる派生判定であり、止めても元の出席記録は壊れない")
    @BatchEndpoint(name = "attendance-daily-evaluation", description = "出席要件規程の日次評価を毎日 06:00 に実行する")
    @Scheduled(cron = "0 0 6 * * *")
    // 起動間隔は日次 06:00。全規程 × 全対象生徒の出席要件評価であり生徒数に比例して伸びる。余裕を取り 2 時間を上限とする。
    @SchedulerLock(name = "attendanceDailyEvaluation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    @Transactional
    public void runDailyEvaluation() {
        LocalDate today = LocalDate.now();
        short year = (short) today.getYear();
        log.info("日次評価バッチ開始: date={}", today);
        int evaluated = 0;
        int notified = 0;

        List<AttendanceRequirementRuleEntity> activeRules = ruleRepository.findAllActive(today, year);

        for (AttendanceRequirementRuleEntity rule : activeRules) {
            // チームスコープ規程のみ対象（組織スコープは将来拡張）
            if (rule.getTeamId() == null) {
                log.debug("組織スコープ規程はスキップ: ruleId={}", rule.getId());
                continue;
            }

            List<StudentAttendanceSummaryEntity> summaries =
                summaryRepository.findClassSummaries(rule.getTeamId(), year);

            for (StudentAttendanceSummaryEntity summary : summaries) {
                Long studentId = summary.getStudentUserId();
                try {
                    // 評価前のステータスを記録
                    EvaluationStatus prevStatus = evaluationRepository
                        .findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(studentId, rule.getId())
                        .map(AttendanceRequirementEvaluationEntity::getStatus)
                        .orElse(null);

                    // 評価実行（バッチはユーザー主体を持たず、対象スコープは activeRules で確定済みのため
                    // 認可ガードを通さない内部経路を使う）
                    EvaluationResponse result = evaluationService.evaluateInternal(studentId, rule.getId());
                    evaluated++;

                    // ステータス変化があれば通知（Issue #2990 L6）。
                    // 業務TX（＝バッチ全体を覆う単一 @Transactional）内では publish だけに留める。
                    // 実配送を TX 内で行うと、通知側の実DBエラーが TX を rollback-only にして
                    // commit 時にその日の評価結果が全生徒・全規程ぶん巻き戻る。
                    // 通知対象のステータス（WARNING / RISK / VIOLATION）だけを publish する。
                    // 是正前の notifyStatusChange の switch と同じ条件であり、OK 等への変化は
                    // 従来どおり通知しない。通知先教員が解決できない場合の扱いだけがリスナー側へ移った
                    // （notified は「配送要求を出した件数」であり、是正前の「通知を呼んだ件数」と
                    //  担任未設定のケースだけ数え方が異なる）。
                    if (prevStatus != result.status() && isNotifiableStatus(result.status())) {
                        eventPublisher.publishEvent(new AttendanceRequirementStatusChangedEvent(
                                studentId, rule.getId(), result.status()));
                        notified++;
                    }
                } catch (Exception e) {
                    log.error("評価失敗: studentId={}, ruleId={}", studentId, rule.getId(), e);
                }
            }
        }

        log.info("日次評価バッチ完了: evaluated={}, notified={}", evaluated, notified);
    }

    /**
     * 週次ダイジェスト通知（毎週月曜7時実行）。
     *
     * <p>チームごとに担任へリスク生徒一覧をダイジェスト通知する。
     * 同一チームが複数規程を持つ場合は重複送信しない。</p>
     *
     * <p>通知はバッチTX内では行わず、{@link AttendanceWeeklyRiskDigestReadyEvent} を publish するに留める。
     * 実配送は {@code SchoolAttendanceNotificationListener} が {@code AFTER_COMMIT} +
     * {@code @Async("event-pool")} で行い、1 チームの送信失敗が以降のチームを巻き添えにしない。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_FAMILY_CARE_ENABLED",
            reason = "止まるのは教員向けダイジェスト通知のみで DB は書き換わらず、学校機能を閉じている間は受け取る教員の画面も閉じている")
    @BatchEndpoint(name = "attendance-weekly-digest", description = "出席要件のリスク生徒週次ダイジェストを毎週月曜 07:00 に教員へ送信する")
    @Scheduled(cron = "0 0 7 * * MON")
    // 起動間隔は週次（月曜 07:00）。リスク生徒の抽出と教員への通知送出のみ。週次で次回まで 7 日あるため余裕を取り 1 時間を上限とする。
    @SchedulerLock(name = "attendanceWeeklyDigest", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    @Transactional(readOnly = true)
    public void sendWeeklyDigest() {
        LocalDate today = LocalDate.now();
        short year = (short) today.getYear();
        log.info("週次ダイジェストバッチ開始: date={}", today);

        List<AttendanceRequirementRuleEntity> activeRules = ruleRepository.findAllActive(today, year);
        Set<Long> processedTeams = new HashSet<>();

        for (AttendanceRequirementRuleEntity rule : activeRules) {
            if (rule.getTeamId() == null) continue;
            if (processedTeams.contains(rule.getTeamId())) continue;
            processedTeams.add(rule.getTeamId());

            List<EvaluationStatus> riskStatuses = List.of(RISK, VIOLATION);

            List<AttendanceRequirementEvaluationEntity> atRiskEvals =
                evaluationRepository.findAtRiskByTeamId(rule.getTeamId(), riskStatuses);

            if (atRiskEvals.isEmpty()) continue;

            // 通知はコミット後にリスナーへ委ねる（担任の解決・件数の再取得もリスナー側で行う）。
            eventPublisher.publishEvent(
                new AttendanceWeeklyRiskDigestReadyEvent(rule.getTeamId(), (int) year));
        }

        log.info("週次ダイジェストバッチ完了");
    }

    /**
     * 教員への通知対象となる評価ステータスかどうかを返す。
     *
     * <p>是正前の {@code notifyStatusChange} の switch（WARNING / RISK / VIOLATION のみ通知し
     * それ以外は 0 を返す）と同一の条件である。</p>
     *
     * @param status 評価ステータス
     * @return 通知対象なら true
     */
    private boolean isNotifiableStatus(EvaluationStatus status) {
        return status == EvaluationStatus.WARNING
                || status == EvaluationStatus.RISK
                || status == EvaluationStatus.VIOLATION;
    }
}
