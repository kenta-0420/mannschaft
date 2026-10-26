package com.mannschaft.app.organization.teamgroup.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.teamgroup.dto.OrgTeamGroupInputRules;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.event.OrgTeamGroupDeletedEvent;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * チームグループの書き込み（作成・変更・削除・並び替え）を、組織内で直列に行うサービス（F01.2.1 §5.2・§7.3）。
 *
 * <p><b>責務の境界</b>: 本クラスは organization ドメインの表（{@code org_team_groups}・{@code organizations}）
 * だけを同一トランザクションで更新する。件数の集計（team ドメイン）と監査ログ（auth ドメイン）は、
 * トランザクションの外にある {@link OrgTeamGroupService} が行う（D-3T: {@code @Transactional} の入口から
 * 他ドメインの Repository へ到達させない）。</p>
 *
 * <p><b>直列化</b>: 上限判定・末尾採番・並び替えは「読んでから書く」ため、いずれも先頭で組織行を
 * {@code FOR UPDATE} で排他ロックし、同じ組織への並行操作を直列にする。重複名は事前確認で
 * 409 {@code ORG_065} に畳み、DB の一意制約（{@code uq_org_team_groups_org_active_name}）は最後の砦として
 * 同じ 409 に畳む（500 にしない。AC-G135）。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgTeamGroupCommandService {

    /** 1 組織あたりの生存グループ上限（マスター裁可: 100 件）。 */
    public static final int MAX_GROUPS_PER_ORG = 100;

    private static final String ACTIVE_NAME_CONSTRAINT = "uq_org_team_groups_org_active_name";

    private final OrganizationRepository organizationRepository;
    private final OrgTeamGroupRepository groupRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * グループを作成する。並び順の末尾（現在の最大値 + 1。無ければ 0）に入る。
     *
     * @throws BusinessException ORG_001（組織なし）・ORG_067（機能 off）・ORG_066（上限）・ORG_065（同名）
     */
    @Transactional
    public OrgTeamGroupView create(Long organizationId, Long userId, String name, String description) {
        lockEnabledOrganization(organizationId);
        String normalizedName = OrgTeamGroupInputRules.normalize(name);

        // 設計書 §7.1 の順: 重複（409）→ 上限（422）
        if (groupRepository.existsByOrganizationIdAndNameAndDeletedAtIsNull(organizationId, normalizedName)) {
            throw new BusinessException(OrgErrorCode.ORG_065);
        }
        if (groupRepository.countByOrganizationIdAndDeletedAtIsNull(organizationId) >= MAX_GROUPS_PER_ORG) {
            throw new BusinessException(OrgErrorCode.ORG_066);
        }
        OrgTeamGroupEntity entity = OrgTeamGroupEntity.builder()
                .organizationId(organizationId)
                .name(normalizedName)
                .description(OrgTeamGroupInputRules.normalizeDescription(description))
                .sortOrder(groupRepository.findMaxSortOrder(organizationId) + 1)
                .createdBy(userId)
                .updatedBy(userId)
                .build();
        return OrgTeamGroupView.of(saveMappingDuplicate(entity));
    }

    /**
     * グループの名前・説明を変更する（部分更新。null は変更しない。説明は空文字で消去）。
     *
     * @throws BusinessException ORG_064（他組織・削除済み・不在は同じ）・ORG_067・ORG_065（改名で同名）
     */
    @Transactional
    public OrgTeamGroupView update(Long organizationId, UUID groupId, Long userId, String name, String description) {
        lockEnabledOrganization(organizationId);
        OrgTeamGroupEntity group = findLiveGroup(organizationId, groupId);

        if (name != null) {
            String normalizedName = OrgTeamGroupInputRules.normalize(name);
            if (groupRepository.existsByOrganizationIdAndNameAndDeletedAtIsNullAndIdNot(
                    organizationId, normalizedName, groupId)) {
                throw new BusinessException(OrgErrorCode.ORG_065);
            }
            group.rename(normalizedName, userId);
        }
        if (description != null) {
            group.changeDescription(OrgTeamGroupInputRules.normalizeDescription(description), userId);
        }
        return OrgTeamGroupView.of(saveMappingDuplicate(group));
    }

    /**
     * グループを論理削除する。同じトランザクションで {@link OrgTeamGroupDeletedEvent} を発行し、
     * コミット後に team ドメインのリスナーが所属チームの {@code group_id} を未分類へ戻す。
     * リスナーの処理前でも、読み手は削除済みグループを指す行を未分類として扱う。
     *
     * @return 削除したグループ（監査ログ用）
     * @throws BusinessException ORG_064・ORG_067
     */
    @Transactional
    public OrgTeamGroupView delete(Long organizationId, UUID groupId, Long userId) {
        lockEnabledOrganization(organizationId);
        OrgTeamGroupEntity group = findLiveGroup(organizationId, groupId);
        group.softDelete(Instant.now(clock), userId);
        groupRepository.saveAndFlush(group);
        eventPublisher.publishEvent(new OrgTeamGroupDeletedEvent(organizationId, groupId, userId));
        return OrgTeamGroupView.of(group);
    }

    /**
     * 並び替える。{@code groupIds} は組織の生存グループ全件と過不足なく一致しなければならない
     * （重複・不足・余分・他組織・削除済みは 409 {@code ORG_068}。順序は変わらない）。
     * 一致すれば sort_order を 0 から振り直す。
     *
     * @return 新しい並び順のグループ
     * @throws BusinessException ORG_068・ORG_067
     */
    @Transactional
    public List<OrgTeamGroupView> reorder(Long organizationId, Long userId, List<UUID> groupIds) {
        lockEnabledOrganization(organizationId);
        List<OrgTeamGroupEntity> live = groupRepository
                .findByOrganizationIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(organizationId);

        Set<UUID> requested = new HashSet<>(groupIds);
        Set<UUID> liveIds = new HashSet<>();
        live.forEach(g -> liveIds.add(g.getId()));
        if (requested.size() != groupIds.size() || !requested.equals(liveIds)) {
            throw new BusinessException(OrgErrorCode.ORG_068);
        }

        java.util.Map<UUID, OrgTeamGroupEntity> byId = new java.util.HashMap<>();
        live.forEach(g -> byId.put(g.getId(), g));
        List<OrgTeamGroupEntity> ordered = new java.util.ArrayList<>(groupIds.size());
        int index = 0;
        for (UUID id : groupIds) {
            OrgTeamGroupEntity g = byId.get(id);
            g.reorder(index++, userId);
            ordered.add(g);
        }
        groupRepository.saveAllAndFlush(ordered);
        return ordered.stream().map(OrgTeamGroupView::of).toList();
    }

    // ───────── 内部 ─────────

    /** 組織行を排他ロックし、存在とグループ機能 on を確かめる（不在 ORG_001・off ORG_067）。 */
    private OrganizationEntity lockEnabledOrganization(Long organizationId) {
        OrganizationEntity org = organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        if (!Boolean.TRUE.equals(org.getTeamGroupsEnabled())) {
            throw new BusinessException(OrgErrorCode.ORG_067);
        }
        return org;
    }

    /** (id, organization_id) の組で生存グループを引く。他組織・削除済み・不在は区別せず ORG_064。 */
    private OrgTeamGroupEntity findLiveGroup(Long organizationId, UUID groupId) {
        return groupRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(groupId, organizationId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_064));
    }

    /** 保存し、生存名の一意制約違反（事前確認をすり抜けた競合）だけを 409 ORG_065 に畳む。 */
    private OrgTeamGroupEntity saveMappingDuplicate(OrgTeamGroupEntity entity) {
        try {
            return groupRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isActiveNameViolation(e)) {
                throw new BusinessException(OrgErrorCode.ORG_065, e);
            }
            throw e;
        }
    }

    private static boolean isActiveNameViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(ACTIVE_NAME_CONSTRAINT)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
