package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
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
    private final AccessControlService accessControlService;

    // スコープ種別文字列（AttendanceRequirementService の用法に合わせる）。
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";
    private static final String SCOPE_TEAM = "TEAM";

    // ========================================
    // 一覧取得
    // ========================================

    /**
     * 生徒の評価一覧を評価日降順で取得する。
     *
     * <p>認可（同ドメイン {@code AttendanceLocationService#getTimeline} の二経路方式に本人経路を足した三経路）:</p>
     * <ol>
     *   <li>本人（{@code currentUserId == studentUserId}）なら許可。</li>
     *   <li>評価が属する規程の entity 由来スコープ（組織 or チーム）に閲覧者が所属していれば
     *       教職員として許可（{@link AccessControlService#isMember}）。</li>
     *   <li>いずれでもなければ、対象生徒への ACTIVE な careLink を持つ保護者のみ許可
     *       （{@link AccessControlService#checkCareLink}）。全経路失敗で 403（COMMON_002）。</li>
     * </ol>
     *
     * @param studentUserId 生徒ユーザーID
     * @param currentUserId 閲覧者のユーザーID（認可判定に使用）
     * @return 評価レスポンスのリスト
     */
    public List<EvaluationResponse> getStudentEvaluations(Long studentUserId, Long currentUserId) {
        List<AttendanceRequirementEvaluationEntity> evaluations =
                evaluationRepository.findByStudentUserIdOrderByEvaluatedAtDesc(studentUserId);
        authorizeStudentEvaluationView(studentUserId, evaluations, currentUserId);
        return evaluations.stream()
                .map(EvaluationResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * チームのリスクあり生徒一覧を取得する。
     *
     * <p>認可: クラス全員分を返すため、対象クラスチームのメンバーのみ参照可
     * （{@link AccessControlService#checkMembership}）。非メンバーは 403（COMMON_002）。</p>
     *
     * @param teamId        チームID
     * @param statusFilters ステータスフィルター（空の場合は RISK, VIOLATION を対象とする）
     * @param currentUserId 閲覧者のユーザーID（認可判定に使用）
     * @return リスクあり生徒レスポンスのリスト
     */
    public List<AtRiskStudentResponse> getAtRiskStudents(
            Long teamId, List<String> statusFilters, Long currentUserId) {
        accessControlService.checkMembership(currentUserId, teamId, SCOPE_TEAM);

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
     * 生徒の出席要件評価を実行する（HTTP 公開入口）。
     *
     * <p>認可: 規程 entity 由来スコープ（{@code organizationId} が非 null なら ORGANIZATION、
     * そうでなければ TEAM）のメンバーのみ実行可。URL パスにスコープを持たない ruleId 直指定 EP のため、
     * 権限が無い場合は 403 ではなく 404（{@code REQUIREMENT_RULE_NOT_FOUND}）に収束させ、
     * 規程の存在有無を非権限者に開示しない（存在秘匿）。同ドメイン
     * {@code AttendanceRequirementService#updateRule} の規約を踏襲する。</p>
     *
     * @param studentUserId     評価対象の生徒ユーザーID
     * @param requirementRuleId 適用する要件規程ID
     * @param actorUserId       実行者のユーザーID（認可判定に使用）
     * @return 評価結果レスポンス
     */
    @Transactional
    public EvaluationResponse evaluate(Long studentUserId, Long requirementRuleId, Long actorUserId) {
        AttendanceRequirementRuleEntity rule = ruleRepository.findById(requirementRuleId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND));
        requireRuleScopeMemberOrHide(rule, actorUserId, SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND);
        return evaluateInternal(studentUserId, requirementRuleId);
    }

    /**
     * 生徒の出席要件評価を実行し、結果を保存（upsert）して返す（内部・バッチ用）。
     *
     * <p>規程の閾値に基づき、出席率・欠席日数から評価ステータスを算出する。
     * 既存評価がある場合は更新、ない場合は新規作成する。</p>
     *
     * <p><b>認可は行わない。</b> HTTP 経由の呼び出しは必ず
     * {@link #evaluate(Long, Long, Long)} を入口とすること。本メソッドは
     * {@code AttendanceRequirementBatchService} の日次バッチのように、スコープを
     * 呼び出し元が確定済みでユーザー主体を持たない経路専用である
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
     * <p>認可: 評価 → 規程と辿った entity 由来スコープのメンバーのみ実行可。URL パスにスコープを
     * 持たない evaluationId 直指定 EP のため、権限が無い場合は 404（{@code EVALUATION_NOT_FOUND}）に
     * 収束させ、評価の存在有無を非権限者に開示しない（存在秘匿）。</p>
     *
     * @param evaluationId   対象の評価ID
     * @param resolverUserId 解消を記録した教員のユーザーID（認可判定にも使用）
     * @param request        解消リクエスト（解消理由を含む）
     * @return 更新後の評価レスポンス
     */
    @Transactional
    public EvaluationResponse resolveViolation(
            Long evaluationId, Long resolverUserId, ResolveEvaluationRequest request) {
        // 1. 評価取得
        AttendanceRequirementEvaluationEntity entity = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));

        // 1-2. 認可（評価 entity 由来スコープ・権限が無ければ存在秘匿の404）
        AttendanceRequirementRuleEntity rule = ruleRepository.findById(entity.getRequirementRuleId())
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));
        requireRuleScopeMemberOrHide(rule, resolverUserId, SchoolErrorCode.EVALUATION_NOT_FOUND);

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
    // プライベートヘルパー（認可）
    // ========================================

    /**
     * 生徒の評価一覧閲覧を三経路（本人／同スコープ教職員／保護者）で認可する。
     *
     * <p>全経路が失敗した場合のみ {@code checkCareLink} が 403（COMMON_002）を送出する。
     * 評価が 1 件も無くスコープを解決できない場合は、教職員経路を判定できないため
     * 本人経路と保護者経路のみで認可する（同ドメイン
     * {@code AttendanceLocationService#authorizeTimelineView} と同じフォールバック方針）。</p>
     *
     * @param studentUserId 対象生徒のユーザーID
     * @param evaluations   対象生徒の評価一覧（スコープ解決に使用）
     * @param currentUserId 閲覧者のユーザーID
     */
    private void authorizeStudentEvaluationView(
            Long studentUserId,
            List<AttendanceRequirementEvaluationEntity> evaluations,
            Long currentUserId) {

        // 1. 本人経路
        if (currentUserId != null && currentUserId.equals(studentUserId)) {
            return;
        }

        // 2. 教職員経路: 評価が属する規程の entity 由来スコープに所属していれば許可。
        List<Long> ruleIds = evaluations.stream()
                .map(AttendanceRequirementEvaluationEntity::getRequirementRuleId)
                .distinct()
                .collect(Collectors.toList());
        if (currentUserId != null && !ruleIds.isEmpty()) {
            for (AttendanceRequirementRuleEntity rule : ruleRepository.findAllById(ruleIds)) {
                Long scopeId = resolveScopeId(rule);
                if (scopeId != null
                        && accessControlService.isMember(currentUserId, scopeId, resolveScopeType(rule))) {
                    return;
                }
            }
        }

        // 3. 保護者経路: ACTIVE な careLink が無ければ COMMON_002（403）を送出する。
        accessControlService.checkCareLink(currentUserId, studentUserId);
    }

    /**
     * 規程 entity 由来スコープ（組織優先・無ければチーム）のメンバーであることを要求する。
     *
     * <p>URL にスコープを持たない bare id EP 用。権限が無い場合は 403 ではなく引数の
     * ErrorCode（404 系）を送出し、リソースの存在有無を非権限者に開示しない。</p>
     *
     * <p>{@code accessControlService} は本メソッドから<b>直接</b>呼ぶこと。番人テスト
     * {@code AuthzControllerGuardArchTest} は Controller 起点で 2 ホップまでしか委譲を辿らないため、
     * さらに private メソッドへ委譲すると認可シグナルを検出できなくなる。</p>
     *
     * @param rule        対象規程エンティティ
     * @param actorUserId 操作ユーザーID
     * @param hideAs      権限が無い場合に送出するエラーコード（存在秘匿用）
     */
    private void requireRuleScopeMemberOrHide(
            AttendanceRequirementRuleEntity rule, Long actorUserId, SchoolErrorCode hideAs) {
        Long scopeId = resolveScopeId(rule);
        if (actorUserId == null || scopeId == null
                || !accessControlService.isMember(actorUserId, scopeId, resolveScopeType(rule))) {
            throw new BusinessException(hideAs);
        }
    }

    /**
     * 規程 entity 由来スコープの scopeId を解決する（組織スコープ優先）。
     *
     * @param rule 対象規程エンティティ
     * @return 組織ID または チームID（どちらも無ければ null）
     */
    private Long resolveScopeId(AttendanceRequirementRuleEntity rule) {
        if (rule == null) {
            return null;
        }
        return rule.getOrganizationId() != null ? rule.getOrganizationId() : rule.getTeamId();
    }

    /**
     * 規程 entity 由来スコープの scopeType を解決する。
     *
     * @param rule 対象規程エンティティ
     * @return {@code ORGANIZATION} または {@code TEAM}
     */
    private String resolveScopeType(AttendanceRequirementRuleEntity rule) {
        return rule != null && rule.getOrganizationId() != null ? SCOPE_ORGANIZATION : SCOPE_TEAM;
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
