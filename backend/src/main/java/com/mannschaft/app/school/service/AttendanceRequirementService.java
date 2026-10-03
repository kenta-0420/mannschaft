package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.CreateRequirementRuleRequest;
import com.mannschaft.app.school.dto.RequirementRuleListResponse;
import com.mannschaft.app.school.dto.RequirementRuleResponse;
import com.mannschaft.app.school.dto.UpdateRequirementRuleRequest;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 出席要件規程管理サービス。
 *
 * <p>組織・チームスコープの規程 CRUD と、生徒への適用規程解決を提供する。</p>
 */
@Service
@RequiredArgsConstructor
public class AttendanceRequirementService {

    private final AttendanceRequirementRuleRepository ruleRepository;
    private final AccessControlService accessControlService;

    // スコープ種別文字列（AccessControlService の用法に合わせる）。
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";
    private static final String SCOPE_TEAM = "TEAM";

    // ========================================
    // 一覧取得
    // ========================================

    /**
     * 組織スコープの規程一覧を取得する。
     *
     * <p>認可: 組織メンバーのみ参照可（{@link AccessControlService#checkMembership}）。</p>
     *
     * @param orgId        組織ID
     * @param academicYear 学年度
     * @param actorUserId  操作ユーザーID（認可判定に使用）
     * @return 規程一覧レスポンス
     */
    @Transactional(readOnly = true)
    public RequirementRuleListResponse listOrganizationRules(Long orgId, short academicYear, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, orgId, SCOPE_ORGANIZATION);
        List<RequirementRuleResponse> rules = ruleRepository
                .findByOrganizationIdAndAcademicYear(orgId, academicYear, LocalDate.now())
                .stream()
                .map(RequirementRuleResponse::from)
                .collect(Collectors.toList());
        return RequirementRuleListResponse.builder()
                .rules(rules)
                .total(rules.size())
                .build();
    }

    /**
     * チームスコープの規程一覧を取得する。
     *
     * <p>認可: チームメンバーのみ参照可（{@link AccessControlService#checkMembership}）。</p>
     *
     * @param teamId       チームID
     * @param academicYear 学年度
     * @param actorUserId  操作ユーザーID（認可判定に使用）
     * @return 規程一覧レスポンス
     */
    @Transactional(readOnly = true)
    public RequirementRuleListResponse listTeamRules(Long teamId, short academicYear, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);
        List<RequirementRuleResponse> rules = ruleRepository
                .findByTeamIdAndAcademicYear(teamId, academicYear, LocalDate.now())
                .stream()
                .map(RequirementRuleResponse::from)
                .collect(Collectors.toList());
        return RequirementRuleListResponse.builder()
                .rules(rules)
                .total(rules.size())
                .build();
    }

    // ========================================
    // 作成
    // ========================================

    /**
     * 組織スコープの規程を作成する。
     *
     * <p>認可: 組織 ADMIN/DEPUTY_ADMIN のみ作成可（{@link AccessControlService#checkAdminOrAbove}）。</p>
     *
     * @param orgId       組織ID（パスから取得）
     * @param req         作成リクエスト
     * @param actorUserId 操作ユーザーID（認可判定に使用）
     * @return 作成された規程レスポンス
     */
    @Transactional
    public RequirementRuleResponse createOrganizationRule(
            Long orgId, CreateRequirementRuleRequest req, Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, orgId, SCOPE_ORGANIZATION);
        // 有効期間の整合性チェック
        validateEffectiveDates(req.getEffectiveFrom(), req.getEffectiveUntil());
        AttendanceRequirementRuleEntity entity = buildEntity(req, orgId, null);
        return RequirementRuleResponse.from(ruleRepository.save(entity));
    }

    /**
     * チームスコープの規程を作成する。
     *
     * <p>認可: チーム ADMIN/DEPUTY_ADMIN のみ作成可（{@link AccessControlService#checkAdminOrAbove}）。</p>
     *
     * @param teamId      チームID（パスから取得）
     * @param req         作成リクエスト
     * @param actorUserId 操作ユーザーID（認可判定に使用）
     * @return 作成された規程レスポンス
     */
    @Transactional
    public RequirementRuleResponse createTeamRule(
            Long teamId, CreateRequirementRuleRequest req, Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TEAM);
        // 有効期間の整合性チェック
        validateEffectiveDates(req.getEffectiveFrom(), req.getEffectiveUntil());
        AttendanceRequirementRuleEntity entity = buildEntity(req, null, teamId);
        return RequirementRuleResponse.from(ruleRepository.save(entity));
    }

    // ========================================
    // 更新
    // ========================================

    /**
     * 規程を更新する。
     *
     * <p>認可: 規程 entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ更新可。権限が無い場合は
     * 存在秘匿のため 404（{@link #requireScopeAdminOrHide}）。</p>
     *
     * @param ruleId      規程ID
     * @param req         更新リクエスト
     * @param actorUserId 操作ユーザーID（認可判定に使用）
     * @return 更新後の規程レスポンス
     */
    @Transactional
    public RequirementRuleResponse updateRule(
            Long ruleId, UpdateRequirementRuleRequest req, Long actorUserId) {
        AttendanceRequirementRuleEntity entity = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND));
        requireScopeAdminOrHide(entity, actorUserId);

        // 有効期間の整合性チェック（null の場合は既存値を維持）
        LocalDate from = req.getEffectiveFrom() != null ? req.getEffectiveFrom() : entity.getEffectiveFrom();
        LocalDate until = req.getEffectiveUntil() != null ? req.getEffectiveUntil() : entity.getEffectiveUntil();
        validateEffectiveDates(from, until);

        AttendanceRequirementRuleEntity updated = entity.toBuilder()
                .termId(req.getTermId() != null ? req.getTermId() : entity.getTermId())
                .category(req.getCategory() != null ? req.getCategory() : entity.getCategory())
                .name(req.getName() != null ? req.getName() : entity.getName())
                .description(req.getDescription() != null ? req.getDescription() : entity.getDescription())
                .minAttendanceRate(req.getMinAttendanceRate() != null ? req.getMinAttendanceRate() : entity.getMinAttendanceRate())
                .maxAbsenceDays(req.getMaxAbsenceDays() != null ? req.getMaxAbsenceDays() : entity.getMaxAbsenceDays())
                .maxAbsenceRate(req.getMaxAbsenceRate() != null ? req.getMaxAbsenceRate() : entity.getMaxAbsenceRate())
                .countSickBayAsPresent(req.getCountSickBayAsPresent() != null ? req.getCountSickBayAsPresent() : entity.getCountSickBayAsPresent())
                .countSeparateRoomAsPresent(req.getCountSeparateRoomAsPresent() != null ? req.getCountSeparateRoomAsPresent() : entity.getCountSeparateRoomAsPresent())
                .countLibraryAsPresent(req.getCountLibraryAsPresent() != null ? req.getCountLibraryAsPresent() : entity.getCountLibraryAsPresent())
                .countOnlineAsPresent(req.getCountOnlineAsPresent() != null ? req.getCountOnlineAsPresent() : entity.getCountOnlineAsPresent())
                .countHomeLearningAsOfficialAbsence(req.getCountHomeLearningAsOfficialAbsence() != null ? req.getCountHomeLearningAsOfficialAbsence() : entity.getCountHomeLearningAsOfficialAbsence())
                .countLateAsAbsenceThreshold(req.getCountLateAsAbsenceThreshold() != null ? req.getCountLateAsAbsenceThreshold() : entity.getCountLateAsAbsenceThreshold())
                .warningThresholdRate(req.getWarningThresholdRate() != null ? req.getWarningThresholdRate() : entity.getWarningThresholdRate())
                .effectiveFrom(from)
                .effectiveUntil(until)
                .build();
        return RequirementRuleResponse.from(ruleRepository.save(updated));
    }

    // ========================================
    // 削除
    // ========================================

    /**
     * 規程を削除する。
     *
     * <p>認可: 規程 entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ削除可。権限が無い場合は
     * 存在秘匿のため 404（{@link #requireScopeAdminOrHide}）。</p>
     *
     * @param ruleId      規程ID
     * @param actorUserId 操作ユーザーID（認可判定に使用）
     */
    @Transactional
    public void deleteRule(Long ruleId, Long actorUserId) {
        AttendanceRequirementRuleEntity entity = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND));
        requireScopeAdminOrHide(entity, actorUserId);
        ruleRepository.delete(entity);
    }

    // ========================================
    // 適用規程解決
    // ========================================

    /**
     * 生徒に適用される規程をスコープ優先度順で解決する。
     *
     * <p>チームスコープ規程が組織スコープ規程より優先される。</p>
     *
     * @param orgId        組織ID
     * @param teamId       チームID
     * @param academicYear 学年度
     * @return 優先度順の適用規程一覧（チームスコープが先頭）
     */
    @Transactional(readOnly = true)
    public List<RequirementRuleResponse> getApplicableRules(Long orgId, Long teamId, short academicYear) {
        List<AttendanceRequirementRuleEntity> teamRules =
                ruleRepository.findByTeamIdAndAcademicYear(teamId, academicYear, LocalDate.now());
        List<AttendanceRequirementRuleEntity> orgRules =
                ruleRepository.findByOrganizationIdAndAcademicYear(orgId, academicYear, LocalDate.now());
        // チームスコープを先頭に（チームが組織規程を上書き）
        return Stream.concat(teamRules.stream(), orgRules.stream())
                .map(RequirementRuleResponse::from)
                .collect(Collectors.toList());
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /**
     * ruleId 直指定 EP（更新/削除）の書き込み認可。
     *
     * <p>規程 entity 由来のスコープ（{@code organizationId} が非 null なら ORGANIZATION、
     * そうでなければ TEAM）で ADMIN/DEPUTY_ADMIN を要求する。URL パスにスコープを持たない
     * bare id EP のため、権限が無い場合は 403 ではなく 404（{@code REQUIREMENT_RULE_NOT_FOUND}）に
     * 収束させ、規程の存在有無を非権限者に開示しない（存在秘匿）。</p>
     *
     * @param entity      対象規程エンティティ
     * @param actorUserId 操作ユーザーID
     */
    private void requireScopeAdminOrHide(AttendanceRequirementRuleEntity entity, Long actorUserId) {
        boolean orgScope = entity.getOrganizationId() != null;
        Long scopeId = orgScope ? entity.getOrganizationId() : entity.getTeamId();
        String scopeType = orgScope ? SCOPE_ORGANIZATION : SCOPE_TEAM;
        if (!accessControlService.isAdminOrAbove(actorUserId, scopeId, scopeType)) {
            throw new BusinessException(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND);
        }
    }

    /**
     * 有効期間の整合性を検証する。
     *
     * @param from  有効開始日
     * @param until 有効終了日（null可）
     */
    private void validateEffectiveDates(LocalDate from, LocalDate until) {
        if (until != null && until.isBefore(from)) {
            throw new BusinessException(SchoolErrorCode.REQUIREMENT_RULE_DATE_INVALID);
        }
    }

    /**
     * リクエストからエンティティを構築する。
     *
     * @param req            作成リクエスト
     * @param organizationId 組織ID（チームスコープ時は null）
     * @param teamId         チームID（組織スコープ時は null）
     * @return 出席要件規程エンティティ
     */
    private AttendanceRequirementRuleEntity buildEntity(
            CreateRequirementRuleRequest req, Long organizationId, Long teamId) {
        return AttendanceRequirementRuleEntity.builder()
                .organizationId(organizationId)
                .teamId(teamId)
                .termId(req.getTermId())
                .academicYear(req.getAcademicYear())
                .category(req.getCategory())
                .name(req.getName())
                .description(req.getDescription())
                .minAttendanceRate(req.getMinAttendanceRate())
                .maxAbsenceDays(req.getMaxAbsenceDays())
                .maxAbsenceRate(req.getMaxAbsenceRate())
                .countSickBayAsPresent(req.getCountSickBayAsPresent() != null ? req.getCountSickBayAsPresent() : true)
                .countSeparateRoomAsPresent(req.getCountSeparateRoomAsPresent() != null ? req.getCountSeparateRoomAsPresent() : true)
                .countLibraryAsPresent(req.getCountLibraryAsPresent() != null ? req.getCountLibraryAsPresent() : true)
                .countOnlineAsPresent(req.getCountOnlineAsPresent() != null ? req.getCountOnlineAsPresent() : true)
                .countHomeLearningAsOfficialAbsence(req.getCountHomeLearningAsOfficialAbsence() != null ? req.getCountHomeLearningAsOfficialAbsence() : false)
                .countLateAsAbsenceThreshold(req.getCountLateAsAbsenceThreshold() != null ? req.getCountLateAsAbsenceThreshold() : (byte) 0)
                .warningThresholdRate(req.getWarningThresholdRate())
                .effectiveFrom(req.getEffectiveFrom())
                .effectiveUntil(req.getEffectiveUntil())
                .build();
    }
}
