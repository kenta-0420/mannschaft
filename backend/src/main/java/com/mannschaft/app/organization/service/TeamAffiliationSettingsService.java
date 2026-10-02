package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.TeamApplicationGroupMode;
import com.mannschaft.app.organization.dto.TeamApplicationFormResponse;
import com.mannschaft.app.organization.dto.UpdateTeamAffiliationSettingsRequest;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * F01.2.1 §5.5・§10.2・§10.3: 組織のチーム加盟の申請受付・グループ設定（organization ドメイン内で完結する部分）。
 *
 * <p>本サービスは organization ドメインの Repository だけを参照する。認可（組織 ADMIN・可視性）と
 * team ドメインの集計の組み立ては、非トランザクションの {@link TeamAffiliationFacade} が行う
 * （{@code @Transactional} をドメイン内に閉じるため。CLAUDE.md 原則 5）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamAffiliationSettingsService {

    private final OrganizationRepository organizationRepository;
    private final OrgTeamGroupRepository orgTeamGroupRepository;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 加盟まわりの判定に要る組織の値（Entity を外へ出さないための写し）。
     *
     * @param effectiveGroupMode 申請時のグループ選択の実効値（§5.5）
     * @param iconUrl            表示用に解決済みの URL（null 可）
     */
    public record OrgAffiliationSnapshot(
            Long id,
            String slug,
            String name,
            String iconUrl,
            boolean archived,
            boolean applicationEnabled,
            boolean groupsEnabled,
            TeamApplicationGroupMode storedGroupMode,
            TeamApplicationGroupMode effectiveGroupMode,
            String guidance) {
    }

    /**
     * slug から、未削除かつ承諾前（PROVISIONED）でない組織を引く。可視性はここでは判定しない。
     */
    public Optional<OrgAffiliationSnapshot> findActiveOrganization(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return organizationRepository
                .findBySlugAndDeletedAtIsNullAndLifecycleStatus(slug, OrganizationEntity.LifecycleStatus.ACTIVE)
                .map(this::toSnapshot);
    }

    /**
     * 組織の申請時グループ選択の実効値（§5.5）。申請・承認の検証（§6.1 step 10）からも使う。
     */
    public TeamApplicationGroupMode effectiveGroupMode(Long organizationId) {
        OrganizationEntity org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        return toSnapshot(org).effectiveGroupMode();
    }

    /**
     * 設定を置き換える（§10.2。PUT は全項目）。
     *
     * <p>REQUIRED は「グループ機能 on かつ生存グループ1件以上」を満たすときだけ保存でき、満たさなければ
     * 422 {@code ORG_070} で何も変えない（§5.5）。</p>
     *
     * <p>{@code GET /organizations/{slug}} の応答（{@code teamApplication.enabled} を含む）は
     * {@code org-detail} にキャッシュされるため、更新時に追い出す（兄弟の {@code updateOrganization} と同じ作法）。</p>
     */
    @Transactional
    @CacheEvict(value = "org-detail", allEntries = true)
    public OrgAffiliationSnapshot updateSettings(Long organizationId, UpdateTeamAffiliationSettingsRequest req) {
        OrganizationEntity org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        boolean groupsEnabled = req.teamGroupsEnabled();
        if (req.applicationGroupMode() == TeamApplicationGroupMode.REQUIRED
                && (!groupsEnabled || countLiveGroups(organizationId) == 0)) {
            throw new BusinessException(OrgErrorCode.ORG_070);
        }
        org.updateTeamAffiliationSettings(
                req.teamApplicationEnabled(), groupsEnabled, req.applicationGroupMode(), req.normalizedGuidance());
        organizationRepository.save(org);
        return toSnapshot(org);
    }

    /**
     * 組織の生存チームグループを並び順（sortOrder → 名前）で返す（申請フォームの選択肢）。
     */
    public List<TeamApplicationFormResponse.GroupOption> listLiveGroups(Long organizationId) {
        return orgTeamGroupRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId).stream()
                .sorted(Comparator.comparingInt(OrgTeamGroupEntity::getSortOrder)
                        .thenComparing(OrgTeamGroupEntity::getName))
                .map(g -> new TeamApplicationFormResponse.GroupOption(
                        g.getId().toString(), g.getName(), g.getDescription()))
                .toList();
    }

    private long countLiveGroups(Long organizationId) {
        return orgTeamGroupRepository.countByOrganizationIdAndDeletedAtIsNull(organizationId);
    }

    private OrgAffiliationSnapshot toSnapshot(OrganizationEntity org) {
        boolean groupsEnabled = Boolean.TRUE.equals(org.getTeamGroupsEnabled());
        TeamApplicationGroupMode stored = org.getTeamApplicationGroupMode() != null
                ? org.getTeamApplicationGroupMode() : TeamApplicationGroupMode.OFF;
        // 件数が実効値を左右するのは「グループ機能 on・保存値 REQUIRED」のときだけなので、そのときだけ数える
        long liveGroups = groupsEnabled && stored == TeamApplicationGroupMode.REQUIRED
                ? countLiveGroups(org.getId()) : 0L;
        TeamApplicationGroupMode effective = TeamApplicationGroupMode.effective(stored, groupsEnabled, liveGroups);
        return new OrgAffiliationSnapshot(
                org.getId(),
                org.getSlug(),
                org.getName(),
                mediaUrlResolver.resolve(org.getIconUrl()),
                org.getArchivedAt() != null,
                Boolean.TRUE.equals(org.getTeamApplicationEnabled()),
                groupsEnabled,
                stored,
                effective,
                org.getTeamApplicationGuidance());
    }
}
