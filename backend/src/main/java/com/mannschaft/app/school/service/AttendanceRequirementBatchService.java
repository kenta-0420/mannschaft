package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import com.mannschaft.app.school.repository.StudentAttendanceSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
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
    private final ClassHomeroomRepository homeroomRepository;
    private final SchoolAttendanceNotificationService notificationService;

    /**
     * 日次評価バッチ（毎朝6時実行）。
     *
     * <p>全ACTIVE規程の対象生徒を一括評価し、ステータス変化があれば教員に通知する。
     * 1件の評価が失敗しても例外をキャッチして次の生徒に進む設計とする。</p>
     */
    @Scheduled(cron = "0 0 6 * * *")
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

                    // 評価実行
                    EvaluationResponse result = evaluationService.evaluate(studentId, rule.getId());
                    evaluated++;

                    // ステータス変化があれば通知
                    if (prevStatus != result.status()) {
                        notified += notifyStatusChange(studentId, rule, result.status());
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
     */
    @Scheduled(cron = "0 0 7 * * MON")
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

            // 担任を取得して通知
            homeroomRepository.findByTeamIdAndAcademicYearAndEffectiveUntilIsNull(
                    rule.getTeamId(), (int) year)
                .ifPresent(homeroom -> notificationService.sendWeeklyRiskDigest(
                    rule.getTeamId(), atRiskEvals.size(), homeroom.getHomeroomTeacherUserId()));
        }

        log.info("週次ダイジェストバッチ完了");
    }

    /**
     * ステータス変化に応じた教員通知を送信する。
     *
     * @param studentId 対象生徒のユーザーID
     * @param rule      適用規程
     * @param newStatus 新しい評価ステータス
     * @return 通知送信件数（0 または 1）
     */
    private int notifyStatusChange(Long studentId, AttendanceRequirementRuleEntity rule, EvaluationStatus newStatus) {
        List<Long> teacherIds = getTeacherIds(rule);
        if (teacherIds.isEmpty()) return 0;

        switch (newStatus) {
            case WARNING   -> notificationService.notifyRequirementWarning(studentId, rule.getName(), teacherIds);
            case RISK      -> notificationService.notifyRequirementRisk(studentId, rule.getName(), teacherIds);
            case VIOLATION -> notificationService.notifyRequirementViolation(studentId, rule.getName(), teacherIds);
            default        -> { return 0; }
        }
        return 1;
    }

    /**
     * 規程から担任のユーザーIDリストを取得する。
     *
     * @param rule 対象規程
     * @return 担任のユーザーIDリスト（取得できない場合は空リスト）
     */
    private List<Long> getTeacherIds(AttendanceRequirementRuleEntity rule) {
        if (rule.getTeamId() == null) return List.of();
        short year = (short) LocalDate.now().getYear();
        return homeroomRepository.findByTeamIdAndAcademicYearAndEffectiveUntilIsNull(
                rule.getTeamId(), (int) year)
            .map(h -> {
                List<Long> ids = new ArrayList<>();
                ids.add(h.getHomeroomTeacherUserId());
                return ids;
            })
            .orElse(List.of());
    }
}
