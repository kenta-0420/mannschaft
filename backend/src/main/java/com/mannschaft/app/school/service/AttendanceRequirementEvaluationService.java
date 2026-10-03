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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 規程 entity 由来のスコープ（認可の前段でトランザクションの外の Facade が読む値）。
     * 組織スコープ規程は {@code organizationId} あり・{@code teamId} なし、チームスコープ規程はその逆。
     */
    public record RuleScope(Long organizationId, Long teamId) { }

    // ========================================
    // 一覧取得
    // ========================================

    /**
     * 生徒の評価一覧を評価日降順で取得する。
     *
     * <p>認可（AC-4）は、トランザクションの外の {@code AttendanceRequirementEvaluationFacade} が
     * {@link SchoolAttendanceAccessPolicy#resolveViewableTeamIds} で行い、返してよいクラスの範囲だけを本メソッドへ渡す。</p>
     * <ol>
     *   <li>生徒本人、または対象生徒への ACTIVE な careLink を持つ保護者は、全クラス分を返す（範囲は {@code null}）。</li>
     *   <li>教職員は、生徒が現在所属するクラスのうち自分が閲覧権（V）を持つクラスの規程に属する評価だけを返す
     *       （評価が 0 件でも 200 の空配列）。</li>
     * </ol>
     *
     * @param studentUserId   生徒ユーザーID
     * @param viewableTeamIds 返してよいクラス ID の集合。{@code null} なら全クラス分
     * @return 評価レスポンスのリスト
     */
    public List<EvaluationResponse> getStudentEvaluations(Long studentUserId, Set<Long> viewableTeamIds) {
        List<AttendanceRequirementEvaluationEntity> evaluations =
                evaluationRepository.findByStudentUserIdOrderByEvaluatedAtDesc(studentUserId);
        if (viewableTeamIds != null) {
            Map<Long, AttendanceRequirementRuleEntity> rules = new HashMap<>();
            ruleRepository.findAllById(evaluations.stream()
                            .map(AttendanceRequirementEvaluationEntity::getRequirementRuleId)
                            .distinct().collect(Collectors.toList()))
                    .forEach(r -> rules.put(r.getId(), r));
            evaluations = evaluations.stream()
                    .filter(e -> {
                        AttendanceRequirementRuleEntity rule = rules.get(e.getRequirementRuleId());
                        return rule != null && rule.getOrganizationId() == null
                                && rule.getTeamId() != null && viewableTeamIds.contains(rule.getTeamId());
                    })
                    .collect(Collectors.toList());
        }
        return evaluations.stream()
                .map(EvaluationResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * チームのリスクあり生徒一覧を取得する。
     *
     * <p>認可: クラス全員分を返すため閲覧権（V）が必要。トランザクションの外の
     * {@code AttendanceRequirementEvaluationFacade} が済ませてから呼ばれる。</p>
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
     * 規程のスコープを返す（評価実行の認可の前段。トランザクションの外の Facade から呼ぶ）。
     *
     * <p>URL パスにスコープを持たない ruleId 直指定 EP のため、規程 entity 由来のスコープで認可する。
     * 規程が存在しなければ 404（{@code REQUIREMENT_RULE_NOT_FOUND}）。</p>
     */
    @Transactional(readOnly = true)
    public RuleScope findRuleScope(Long requirementRuleId) {
        AttendanceRequirementRuleEntity rule = ruleRepository.findById(requirementRuleId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND));
        return new RuleScope(rule.getOrganizationId(), rule.getTeamId());
    }

    /**
     * 評価 → 規程と辿ったスコープを返す（違反解消の認可の前段。トランザクションの外の Facade から呼ぶ）。
     *
     * <p>URL パスにスコープを持たない evaluationId 直指定 EP のため、entity 由来のスコープで認可する。
     * 評価または規程が存在しなければ 404（{@code EVALUATION_NOT_FOUND}）。</p>
     */
    @Transactional(readOnly = true)
    public RuleScope findRuleScopeByEvaluation(Long evaluationId) {
        AttendanceRequirementEvaluationEntity entity = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));
        AttendanceRequirementRuleEntity rule = ruleRepository.findById(entity.getRequirementRuleId())
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));
        return new RuleScope(rule.getOrganizationId(), rule.getTeamId());
    }

    /**
     * 生徒の出席要件評価を実行し、結果を保存（upsert）して返す（内部・バッチ用）。
     *
     * <p>規程の閾値に基づき、出席率・欠席日数から評価ステータスを算出する。
     * 既存評価がある場合は更新、ない場合は新規作成する。</p>
     *
     * <p><b>認可は行わない。</b> HTTP 経由の呼び出しは必ず
     * {@code AttendanceRequirementEvaluationFacade#evaluate} を入口とすること（認可と在籍確認をトランザクションの外で済ませる）。
     * 本メソッドは {@code AttendanceRequirementBatchService} の日次バッチのように、スコープを
     * 呼び出し元が確定済みでユーザー主体を持たない経路にも共有される
     * （共有 Service 内部にガードを置くとバッチが巻き添えで 403 になるため入口側で分離した）。</p>
     *
     * @param studentUserId     評価対象の生徒ユーザーID
     * @param requirementRuleId 適用する要件規程ID
     * @return 評価結果レスポンス
     */
    @Transactional
    public EvaluationResponse evaluateInternal(Long studentUserId, Long requirementRuleId) {
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
                .map(existing -> (AttendanceRequirementEvaluationEntity) existing.toBuilder()
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
     * <p>認可（AC-13/AC-22）: 評価 → 規程と辿った entity 由来スコープで、チームスコープ規程は日次登録権（R）、
     * 組織スコープ規程は当該組織の ADMIN/DEPUTY_ADMIN のみ実行可。権限が無い場合は 404
     * （{@code EVALUATION_NOT_FOUND}）に収束させ、評価の存在有無を非権限者に開示しない（存在秘匿）。
     * 認可は、トランザクションの外の {@code AttendanceRequirementEvaluationFacade} が
     * {@link #findRuleScopeByEvaluation} で読んだスコープに対して、本メソッドより前に済ませる。</p>
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
    // プライベートヘルパー（算出）
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
