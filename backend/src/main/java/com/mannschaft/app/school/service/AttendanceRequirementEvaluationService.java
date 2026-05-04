package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.AtRiskStudentResponse;
import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.dto.ResolveEvaluationRequest;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import com.mannschaft.app.school.repository.StudentAttendanceSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 出席要件評価サービス（F03.13 Phase 12）。
 *
 * <p>生徒の出席集計に対して要件規程を適用し、評価ステータス（OK/WARNING/RISK/VIOLATION）を算出する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceRequirementEvaluationService {

    private final AttendanceRequirementEvaluationRepository evaluationRepository;
    private final AttendanceRequirementRuleRepository ruleRepository;
    private final StudentAttendanceSummaryRepository summaryRepository;

    // ========================================
    // 一覧取得
    // ========================================

    /**
     * 生徒の評価一覧を評価日降順で取得する。
     *
     * @param studentUserId 生徒ユーザーID
     * @return 評価レスポンスのリスト
     */
    public List<EvaluationResponse> getStudentEvaluations(Long studentUserId) {
        return evaluationRepository.findByStudentUserIdOrderByEvaluatedAtDesc(studentUserId)
                .stream()
                .map(EvaluationResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * チームのリスクあり生徒一覧を取得する。
     *
     * @param teamId        チームID
     * @param statusFilters ステータスフィルター（空の場合は RISK, VIOLATION を対象とする）
     * @return リスクあり生徒レスポンスのリスト
     */
    public List<AtRiskStudentResponse> getAtRiskStudents(Long teamId, List<String> statusFilters) {
        // フィルターが空の場合はデフォルトで RISK と VIOLATION を対象とする
        List<EvaluationStatus> statuses;
        if (statusFilters == null || statusFilters.isEmpty()) {
            statuses = List.of(EvaluationStatus.RISK, EvaluationStatus.VIOLATION);
        } else {
            statuses = statusFilters.stream()
                    .map(EvaluationStatus::valueOf)
                    .collect(Collectors.toList());
        }

        return evaluationRepository.findAtRiskByTeamId(teamId, statuses)
                .stream()
                .map(AtRiskStudentResponse::from)
                .collect(Collectors.toList());
    }

    // ========================================
    // 評価実行
    // ========================================

    /**
     * 生徒の出席要件評価を実行し、結果を保存（upsert）して返す。
     *
     * <p>規程の閾値に基づき、出席率・欠席日数から評価ステータスを算出する。
     * 既存評価がある場合は更新、ない場合は新規作成する。</p>
     *
     * @param studentUserId     評価対象の生徒ユーザーID
     * @param requirementRuleId 適用する要件規程ID
     * @return 評価結果レスポンス
     */
    @Transactional
    public EvaluationResponse evaluate(Long studentUserId, Long requirementRuleId) {
        // 1. 規程取得
        AttendanceRequirementRuleEntity rule = ruleRepository.findById(requirementRuleId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND));

        // 2. 集計取得（teamId は rule から、academicYear/termId も rule から取得）
        Long teamId = rule.getTeamId();
        short academicYear = rule.getAcademicYear();
        Long termId = rule.getTermId();

        StudentAttendanceSummaryEntity summary = summaryRepository
                .findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                        studentUserId, teamId, academicYear, termId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.SUMMARY_NOT_FOUND));

        // 3. 有効欠席日数の計算
        int effectiveAbsenceDays = calculateEffectiveAbsences(rule, summary);

        // 4. 出席率の計算
        BigDecimal attendanceRate = calculateAttendanceRate(summary, effectiveAbsenceDays);

        // 5. 残余許容欠席日数の計算
        int remainingAllowedAbsences = calculateRemainingAllowedAbsences(rule, summary, effectiveAbsenceDays);

        // 6. ステータス判定
        EvaluationStatus newStatus = determineStatus(rule, attendanceRate, effectiveAbsenceDays, remainingAllowedAbsences);

        // 7. upsert（既存評価があれば更新、なければ新規作成）
        AttendanceRequirementEvaluationEntity entity =
                evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                        studentUserId, requirementRuleId)
                .map(existing -> existing.toBuilder()
                        .status(newStatus)
                        .currentAttendanceRate(attendanceRate)
                        .remainingAllowedAbsences(remainingAllowedAbsences)
                        .summaryId(summary.getId())
                        .evaluatedAt(LocalDateTime.now())
                        .build())
                .orElseGet(() -> AttendanceRequirementEvaluationEntity.builder()
                        .requirementRuleId(requirementRuleId)
                        .studentUserId(studentUserId)
                        .summaryId(summary.getId())
                        .status(newStatus)
                        .currentAttendanceRate(attendanceRate)
                        .remainingAllowedAbsences(remainingAllowedAbsences)
                        .evaluatedAt(LocalDateTime.now())
                        .build());

        AttendanceRequirementEvaluationEntity saved = evaluationRepository.save(entity);
        return EvaluationResponse.from(saved);
    }

    // ========================================
    // 違反解消
    // ========================================

    /**
     * 評価違反を解消済みとして記録する。
     *
     * @param evaluationId   対象の評価ID
     * @param resolverUserId 解消を記録した教員のユーザーID
     * @param request        解消リクエスト（解消理由を含む）
     * @return 更新後の評価レスポンス
     */
    @Transactional
    public EvaluationResponse resolveViolation(
            Long evaluationId, Long resolverUserId, ResolveEvaluationRequest request) {
        // 1. 評価取得
        AttendanceRequirementEvaluationEntity entity = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));

        // 2. 既に解消済みかチェック
        if (entity.isResolved()) {
            throw new BusinessException(SchoolErrorCode.EVALUATION_ALREADY_RESOLVED);
        }

        // 3. 解消処理
        entity.resolve(resolverUserId, request.resolutionNote());

        // 4. 保存して返す
        AttendanceRequirementEvaluationEntity saved = evaluationRepository.save(entity);
        return EvaluationResponse.from(saved);
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /**
     * 規程の換算フラグを適用し、有効欠席日数を計算する。
     *
     * <p>保健室・別室・オンライン・家庭学習が「出席扱い」の場合はその日数を欠席から除外する。
     * 遅刻換算が設定されている場合はその換算分を加算する。</p>
     *
     * @param rule    適用する要件規程
     * @param summary 出席集計
     * @return 有効欠席日数（0以上）
     */
    private int calculateEffectiveAbsences(
            AttendanceRequirementRuleEntity rule,
            StudentAttendanceSummaryEntity summary) {

        int effectiveAbsenceDays = (int) summary.getAbsentDays();

        // 保健室登校を出席扱いにする場合は欠席から除外
        if (Boolean.TRUE.equals(rule.getCountSickBayAsPresent())) {
            effectiveAbsenceDays -= (int) summary.getSickBayDays();
        }
        // 別室登校を出席扱いにする場合は欠席から除外
        if (Boolean.TRUE.equals(rule.getCountSeparateRoomAsPresent())) {
            effectiveAbsenceDays -= (int) summary.getSeparateRoomDays();
        }
        // オンライン登校を出席扱いにする場合は欠席から除外
        if (Boolean.TRUE.equals(rule.getCountOnlineAsPresent())) {
            effectiveAbsenceDays -= (int) summary.getOnlineDays();
        }
        // 家庭学習を公欠扱いにする場合は欠席から除外
        if (Boolean.TRUE.equals(rule.getCountHomeLearningAsOfficialAbsence())) {
            effectiveAbsenceDays -= (int) summary.getHomeLearningDays();
        }

        // 遅刻換算（N回で欠席1日換算）
        byte threshold = rule.getCountLateAsAbsenceThreshold();
        if (threshold > 0) {
            effectiveAbsenceDays += (int) summary.getLateCount() / (int) threshold;
        }

        // 負にならないよう補正
        return Math.max(0, effectiveAbsenceDays);
    }

    /**
     * 有効欠席日数をもとに出席率（%）を計算する。
     *
     * @param summary              出席集計
     * @param effectiveAbsenceDays 有効欠席日数
     * @return 出席率（%）、授業日数が0の場合は0.00
     */
    private BigDecimal calculateAttendanceRate(
            StudentAttendanceSummaryEntity summary,
            int effectiveAbsenceDays) {

        int totalSchoolDays = (int) summary.getTotalSchoolDays();
        if (totalSchoolDays == 0) {
            return BigDecimal.ZERO;
        }

        int effectivePresentDays = totalSchoolDays - effectiveAbsenceDays;
        return BigDecimal.valueOf(effectivePresentDays)
                .divide(BigDecimal.valueOf(totalSchoolDays), 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 残余許容欠席日数を計算する。
     *
     * <p>maxAbsenceDays が設定されている場合はそこから逆算する。
     * minAttendanceRate のみの場合は出席率から逆算する。
     * どちらも設定されていない場合は Integer.MAX_VALUE（制限なし）を返す。</p>
     *
     * @param rule                 適用する要件規程
     * @param summary              出席集計
     * @param effectiveAbsenceDays 有効欠席日数
     * @return 残余許容欠席日数（0以上）
     */
    private int calculateRemainingAllowedAbsences(
            AttendanceRequirementRuleEntity rule,
            StudentAttendanceSummaryEntity summary,
            int effectiveAbsenceDays) {

        int remaining;

        if (rule.getMaxAbsenceDays() != null) {
            // 最大欠席日数から残余を計算
            remaining = (int) rule.getMaxAbsenceDays() - effectiveAbsenceDays;
        } else if (rule.getMinAttendanceRate() != null) {
            // 最低出席率から最大許容欠席日数を逆算
            int totalSchoolDays = (int) summary.getTotalSchoolDays();
            // 最低限必要な出席日数（切り上げ）
            BigDecimal requiredPresentBd = BigDecimal.valueOf(totalSchoolDays)
                    .multiply(rule.getMinAttendanceRate())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING);
            int requiredPresent = requiredPresentBd.intValue();
            int maxAllowedAbsence = totalSchoolDays - requiredPresent;
            remaining = maxAllowedAbsence - effectiveAbsenceDays;
        } else {
            // 制限なし
            remaining = Integer.MAX_VALUE;
        }

        // 負にならないよう補正
        return Math.max(0, remaining);
    }

    /**
     * 評価ステータスを判定する。
     *
     * <p>判定優先順位:
     * <ol>
     *   <li>minAttendanceRate 未満 → remaining=0 なら VIOLATION、それ以外は RISK</li>
     *   <li>maxAbsenceDays 超過 → VIOLATION</li>
     *   <li>warningThresholdRate 未満 → WARNING</li>
     *   <li>それ以外 → OK</li>
     * </ol>
     * </p>
     *
     * @param rule                 適用する要件規程
     * @param attendanceRate       算出した出席率
     * @param effectiveAbsenceDays 有効欠席日数
     * @param remaining            残余許容欠席日数
     * @return 評価ステータス
     */
    private EvaluationStatus determineStatus(
            AttendanceRequirementRuleEntity rule,
            BigDecimal attendanceRate,
            int effectiveAbsenceDays,
            int remaining) {

        // 最低出席率チェック
        if (rule.getMinAttendanceRate() != null
                && attendanceRate.compareTo(rule.getMinAttendanceRate()) < 0) {
            return remaining <= 0 ? EvaluationStatus.VIOLATION : EvaluationStatus.RISK;
        }

        // 最大欠席日数チェック
        if (rule.getMaxAbsenceDays() != null
                && effectiveAbsenceDays > (int) rule.getMaxAbsenceDays()) {
            return EvaluationStatus.VIOLATION;
        }

        // 警告しきい値チェック
        if (rule.getWarningThresholdRate() != null
                && attendanceRate.compareTo(rule.getWarningThresholdRate()) < 0) {
            return EvaluationStatus.WARNING;
        }

        return EvaluationStatus.OK;
    }
}
