package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import com.mannschaft.app.team.service.TeamAffiliationOrganizationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * team ドメインの加盟書き込みが組織の情報を引くための窓口の実装（F01.2.1 2-B1）。
 *
 * <p>team ドメインは organization ドメインの Repository を直接参照できないため、
 * {@link TeamAffiliationOrganizationPort}（team ドメイン側のインターフェース）越しに呼ばれる。
 * 組織行のロックは、呼び出し側（チームの加盟 Service）のトランザクションに参加して取る
 * （チーム行 → 組織行の固定順ロックを1つのトランザクションで保持するため。§6.1 step 7・§6.9）。</p>
 */
@Service
@RequiredArgsConstructor
public class OrganizationAffiliationPortAdapter implements TeamAffiliationOrganizationPort {

    private final OrganizationRepository organizationRepository;
    private final OrgTeamGroupRepository orgTeamGroupRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findVisibleOrganizationId(String slug, Long viewerUserId) {
        // ACTIVE 限定: 承諾前（PROVISIONED）の組織へは slug で到達させない（OrganizationService#resolveOrgId と同じ境界）
        return organizationRepository
                .findBySlugAndDeletedAtIsNullAndLifecycleStatus(slug, OrganizationEntity.LifecycleStatus.ACTIVE)
                .map(OrganizationEntity::getId)
                // 可視性は F00 の ORGANIZATION ラダーに委譲する。見えない非公開組織・アーカイブ済み組織は
                // 存在しない slug と区別できない空を返す（存在オラクルを作らない。§6.1 step 4）
                .filter(id -> contentVisibilityChecker.canView(ReferenceType.ORGANIZATION, id, viewerUserId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrganizationAffiliationState lockForAffiliation(Long organizationId) {
        OrganizationEntity org = organizationRepository.findByIdForUpdate(organizationId)
                .filter(o -> o.getLifecycleStatus() == OrganizationEntity.LifecycleStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        return toState(org);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationAffiliationState> findAffiliationState(Long organizationId) {
        // チームの書き込みトランザクションの外から呼ばれ、組織ドメインの読み取りトランザクションで閉じる。
        // 論理削除済みは Entity の @SQLRestriction が除外する。承諾前（PROVISIONED）は不在と同じ扱い
        return organizationRepository.findById(organizationId)
                .filter(o -> o.getLifecycleStatus() == OrganizationEntity.LifecycleStatus.ACTIVE)
                .map(OrganizationAffiliationPortAdapter::toState);
    }

    @Override
    @Transactional
    public Optional<OrganizationAffiliationState> recheckAffiliationStateLocked(Long organizationId) {
        // 組織ドメインの短いトランザクションで共有ロックを取って読む（アーカイブの PESSIMISTIC_WRITE と直列化する。§6.9）
        return organizationRepository.findByIdForShare(organizationId)
                .filter(o -> o.getLifecycleStatus() == OrganizationEntity.LifecycleStatus.ACTIVE)
                .map(OrganizationAffiliationPortAdapter::toState);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationRef> findOrganizationRefBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return organizationRepository
                .findBySlugAndDeletedAtIsNullAndLifecycleStatus(slug, OrganizationEntity.LifecycleStatus.ACTIVE)
                .map(OrganizationAffiliationPortAdapter::toRef);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<OrganizationClosureState> findClosureStateIncludingDeleted(Long organizationId) {
        // 呼び出し側（team ドメインの片付け）の tx には参加せず、組織ドメインの新しい読み取り tx で最新のコミット済みの
        // 状態を読む。論理削除済みも読む（ネイティブ）
        return organizationRepository.findByIdIncludingDeleted(organizationId)
                .map(OrganizationAffiliationPortAdapter::toClosureState);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, OrganizationClosureState> findClosureStatesIncludingDeleted(Collection<Long> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, OrganizationClosureState> result = new HashMap<>();
        for (OrganizationEntity org : organizationRepository.findAllByIdInIncludingDeleted(organizationIds)) {
            result.put(org.getId(), toClosureState(org));
        }
        return result;
    }

    private static OrganizationClosureState toClosureState(OrganizationEntity org) {
        return new OrganizationClosureState(org.getId(), org.getSlug(), org.getName(),
                org.getArchivedAt() != null, org.getDeletedAt() != null, org.getClosureGeneration());
    }

    private static OrganizationAffiliationState toState(OrganizationEntity org) {
        return new OrganizationAffiliationState(
                org.getId(),
                org.getSlug(),
                org.getName(),
                org.getArchivedAt() != null,
                Boolean.TRUE.equals(org.getTeamApplicationEnabled()),
                Boolean.TRUE.equals(org.getTeamGroupsEnabled()),
                org.getTeamApplicationGroupMode(),
                org.getClosureGeneration());
    }

    private static OrganizationRef toRef(OrganizationEntity org) {
        return new OrganizationRef(org.getId(), org.getSlug(), org.getName(),
                org.getIconUrl(), Boolean.TRUE.equals(org.getTeamGroupsEnabled()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAliveGroupOfOrganization(Long organizationId, UUID groupId) {
        return orgTeamGroupRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(groupId, organizationId)
                .isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, OrganizationRef> findOrganizationRefs(Collection<Long> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, OrganizationRef> result = new HashMap<>();
        for (OrganizationEntity org : organizationRepository.findAllById(organizationIds)) {
            result.put(org.getId(), new OrganizationRef(org.getId(), org.getSlug(), org.getName(),
                    org.getIconUrl(), Boolean.TRUE.equals(org.getTeamGroupsEnabled())));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, GroupRef> findAliveGroupRefs(Collection<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, GroupRef> result = new HashMap<>();
        for (OrgTeamGroupEntity group : orgTeamGroupRepository.findAllById(groupIds)) {
            if (group.getDeletedAt() == null) {
                result.put(group.getId(), new GroupRef(group.getId(), group.getOrganizationId(), group.getName()));
            }
        }
        return result;
    }
}
