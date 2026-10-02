package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.TeamApplicationGroupMode;
import com.mannschaft.app.organization.dto.OrgAffiliationEligibilityResponse;
import com.mannschaft.app.organization.dto.TeamAffiliationSettingsResponse;
import com.mannschaft.app.organization.dto.TeamApplicationFormResponse;
import com.mannschaft.app.organization.dto.UpdateTeamAffiliationSettingsRequest;
import com.mannschaft.app.organization.service.TeamAffiliationSettingsService.OrgAffiliationSnapshot;
import com.mannschaft.app.role.service.PermissionScopeQueryService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamAffiliationApplicantQueryService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * F01.2.1 §10.2・§10.3: 申請受付設定・申請フォーム・申請ボタン判定の入口（認可と組み立て）。
 *
 * <h2>トランザクションを持たない理由</h2>
 * <p>組織の値（organization ドメイン）・加盟と制限の集計（team ドメイン）・権限判定（role ドメインを読む
 * {@link AccessControlService}）をまたいで組み立てるため、本クラスは {@code @Transactional} を付けず、
 * 各ドメインの Service がそれぞれのトランザクションを持つ（CLAUDE.md 原則 5。越境する入口を作らない）。</p>
 *
 * <h2>存在オラクルを作らない判定順（§10 共通事項）</h2>
 * <p>認証（401。フィルタ）→ 組織の存在と可視性（404）→ 権限（403）→ 入力検証（400）→ 状態（409/422）。
 * 見えない組織（非公開で非所属・アーカイブ済み）は、存在しない slug と<b>同じステータス・同じエラーコード
 * （{@code ORG_001}）</b>で返す。受付状態（{@code TEAM_064}）や入力不備（400）は、見える組織にしか返さない。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamAffiliationFacade {

    /** チーム側の加盟操作権限（§3.2）。正本は Flyway V231 の permissions 行。 */
    static final String MANAGE_ORG_AFFILIATION = "MANAGE_ORG_AFFILIATION";

    private static final String ORGANIZATION = "ORGANIZATION";

    private final TeamAffiliationSettingsService settingsService;
    private final TeamAffiliationApplicantQueryService applicantQueryService;
    private final AccessControlService accessControlService;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final PermissionScopeQueryService permissionScopeQueryService;
    private final Validator validator;

    // ========================================
    // 設定（§10.2。組織 ADMIN、SYSTEM_ADMIN は閲覧のみ）
    // ========================================

    /**
     * 設定を取得する。組織 ADMIN と SYSTEM_ADMIN（監査用の閲覧）だけ。
     */
    public TeamAffiliationSettingsResponse getSettings(String slug, Long userId) {
        OrgAffiliationSnapshot org = requireExisting(slug);
        if (!accessControlService.isAdmin(userId, org.id(), ORGANIZATION)
                && !accessControlService.isSystemAdmin(userId)) {
            throw forbiddenOrNotFound(org, userId);
        }
        return toSettingsResponse(org);
    }

    /**
     * 設定を置き換える。組織 ADMIN だけ（SYSTEM_ADMIN は閲覧のみで 403。§3.1）。
     * 入力検証は認可の後に行う（見えない組織に 400 を返して存在を漏らさないため）。
     */
    public TeamAffiliationSettingsResponse updateSettings(
            String slug, Long userId, UpdateTeamAffiliationSettingsRequest req) {
        OrgAffiliationSnapshot org = requireExisting(slug);
        if (!accessControlService.isAdmin(userId, org.id(), ORGANIZATION)) {
            throw forbiddenOrNotFound(org, userId);
        }
        validate(req);
        return toSettingsResponse(settingsService.updateSettings(org.id(), req));
    }

    // ========================================
    // 申請フォーム（§10.3。認証済み・組織が見えること）
    // ========================================

    /**
     * 申請フォームの内容を返す。見えない組織は 404（不在と同じ）、受付 off は 403 {@code TEAM_064}。
     */
    public TeamApplicationFormResponse getApplicationForm(String slug, Long userId) {
        OrgAffiliationSnapshot org = requireVisible(slug, userId);
        if (org.archived()) {
            // 見える組織（SYSTEM_ADMIN のみがここへ来る）でアーカイブ済みなら既存のアーカイブ拒否（§6.1 step 5）
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
        if (!org.applicationEnabled()) {
            throw new BusinessException(TeamErrorCode.TEAM_064);
        }
        List<TeamApplicationFormResponse.GroupOption> groups =
                org.effectiveGroupMode() == TeamApplicationGroupMode.OFF
                        ? List.of()
                        : settingsService.listLiveGroups(org.id());
        List<TeamApplicationFormResponse.MyTeam> myTeams = applicantQueryService
                .findApplicantTeams(findOperableTeamIds(userId), org.id()).stream()
                .map(t -> new TeamApplicationFormResponse.MyTeam(t.slug(), t.name(), t.iconUrl(), t.status().name()))
                .toList();
        return new TeamApplicationFormResponse(
                new TeamApplicationFormResponse.OrganizationRef(org.slug(), org.name(), org.iconUrl()),
                org.guidance(),
                org.effectiveGroupMode(),
                groups,
                myTeams);
    }

    // ========================================
    // 申請ボタン判定（§10.3・M3。常に 200）
    // ========================================

    /**
     * 「組織が見えて・アーカイブされておらず・受付中で・閲覧者が加盟操作権限を持つチームが1つ以上ある」ときだけ true。
     * それ以外はすべて同じ false（理由を区別しない）。例外は投げない。
     */
    public OrgAffiliationEligibilityResponse eligibility(String slug, Long userId) {
        OrgAffiliationSnapshot org = settingsService.findActiveOrganization(slug).orElse(null);
        if (org == null
                || org.archived()
                || !org.applicationEnabled()
                || !contentVisibilityChecker.canView(ReferenceType.ORGANIZATION, org.id(), userId)) {
            return OrgAffiliationEligibilityResponse.no();
        }
        // 申請フォームの myTeams と同じ条件（削除・アーカイブ・PROVISIONED のチームを除く）で数える
        return new OrgAffiliationEligibilityResponse(
                applicantQueryService.hasApplicableTeam(findOperableTeamIds(userId)));
    }

    // ========================================
    // 内部
    // ========================================

    private OrgAffiliationSnapshot requireExisting(String slug) {
        return settingsService.findActiveOrganization(slug).orElseThrow(TeamAffiliationFacade::notFound);
    }

    private OrgAffiliationSnapshot requireVisible(String slug, Long userId) {
        OrgAffiliationSnapshot org = requireExisting(slug);
        if (!contentVisibilityChecker.canView(ReferenceType.ORGANIZATION, org.id(), userId)) {
            throw notFound();
        }
        return org;
    }

    /** 見える組織なら 403、見えない組織なら不在と同じ 404。 */
    private BusinessException forbiddenOrNotFound(OrgAffiliationSnapshot org, Long userId) {
        if (contentVisibilityChecker.canView(ReferenceType.ORGANIZATION, org.id(), userId)) {
            return new BusinessException(CommonErrorCode.COMMON_002);
        }
        return notFound();
    }

    private static BusinessException notFound() {
        return new BusinessException(OrgErrorCode.ORG_001);
    }

    private void validate(UpdateTeamAffiliationSettingsRequest req) {
        Set<ConstraintViolation<UpdateTeamAffiliationSettingsRequest>> violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    /**
     * 閲覧者が加盟操作権限（チーム ADMIN は常に持つ。DEPUTY_ADMIN・MEMBER は権限グループで付与されたときだけ）を
     * 持つチームの ID（§3.2）。
     */
    private Set<Long> findOperableTeamIds(Long userId) {
        // 所属チームごとに単票判定を回さず、ロールと権限グループを1本の SQL でまとめて判定する（N+1 回避）
        return permissionScopeQueryService.findTeamIdsWithAdminOrGroupPermission(userId, MANAGE_ORG_AFFILIATION);
    }

    private TeamAffiliationSettingsResponse toSettingsResponse(OrgAffiliationSnapshot org) {
        return new TeamAffiliationSettingsResponse(
                org.applicationEnabled(),
                org.groupsEnabled(),
                org.storedGroupMode(),
                org.effectiveGroupMode(),
                org.guidance(),
                applicantQueryService.countPendingApplications(org.id()));
    }
}
