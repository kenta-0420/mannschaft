package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.organization.dto.AncestorsResponse;
import com.mannschaft.app.organization.dto.ChildrenResponse;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrgAllMembersResponse;
import com.mannschaft.app.organization.dto.OrgTeamSummaryResponse;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.OrganizationSummaryResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.organization.event.OrganizationCreatedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 組織管理サービス（ファサード）。
 *
 * <p>組織自身の CRUD・アーカイブ・検索・復元・監査イベント発行を直接担い、
 * メンバー / フォロー / 所属チーム参照は {@link OrganizationMembershipService} へ、
 * 祖先・子組織の階層参照は {@link OrganizationHierarchyService} へ委譲する。
 * Controller・テストからの呼び出し interface は分割前と完全に同一。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final InviteTokenRepository inviteTokenRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrganizationMembershipService organizationMembershipService;
    private final OrganizationHierarchyService organizationHierarchyService;
    private final MembershipService membershipService;

    /**
     * 組織を作成し、作成者をADMINロールで紐付ける。
     */
    @Transactional
    // TODO: OrganizationドメインとAuthドメイン・Roleドメインをまたいでいる。将来はOrganizationCreatedEventで分離予定
    public ApiResponse<OrganizationResponse> createOrganization(Long userId, CreateOrganizationRequest req) {
        // 組織名の重複チェック
        if (organizationRepository.existsByName(req.getName())) {
            throw new BusinessException(OrgErrorCode.ORG_002);
        }

        String slug = createUniqueSlug(req.getName());
        OrganizationEntity org = OrganizationEntity.builder()
                .name(req.getName())
                .slug(slug)
                .orgType(OrganizationEntity.OrgType.valueOf(req.getOrgType()))
                .prefecture(req.getPrefecture())
                .city(req.getCity())
                .visibility(req.getVisibility() != null
                        ? OrganizationEntity.Visibility.valueOf(req.getVisibility())
                        : OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .parentOrganizationId(req.getParentOrganizationId())
                .supporterEnabled(false)
                .build();
        organizationRepository.save(org);

        // 作成者をADMINロールで紐付ける
        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_005));
        UserRoleEntity userRole = UserRoleEntity.builder()
                .userId(userId)
                .roleId(adminRole.getId())
                .organizationId(org.getId())
                .build();
        userRoleRepository.save(userRole);

        // F00.5 認可基盤根治: memberships にも MEMBER として入会させる。
        // 認可（AccessControlService.isMember）は memberships を真実の源とするため、
        // user_roles だけでは作成者本人が自組織から 403 で締め出される構造的欠陥を防ぐ。
        // 権限ロール（ADMIN）は user_roles が担い、membership は在籍有無のみ表す（role_kind=MEMBER）。
        MembershipCreateRequest membershipReq = new MembershipCreateRequest();
        membershipReq.setUserId(userId);
        membershipReq.setScopeType(ScopeType.ORGANIZATION);
        membershipReq.setScopeId(org.getId());
        membershipReq.setRoleKind(RoleKind.MEMBER);
        membershipReq.setSource("ORG_CREATE");
        membershipService.join(membershipReq);

        // 監査ログ用イベント発行
        eventPublisher.publishEvent(new OrganizationCreatedEvent(userId, org.getId(), org.getName()));

        log.info("組織作成完了: orgId={}, userId={}", org.getId(), userId);
        return ApiResponse.of(toResponse(org, 1));
    }

    /**
     * 組織を slug（URL識別子）で取得する。
     *
     * <p>Phase 4-E: Valkey にて 10 分キャッシュ。更新・削除時に自動無効化される。</p>
     */
    @Cacheable(value = "org-detail", key = "#slug")
    public ApiResponse<OrganizationResponse> getOrganization(String slug) {
        OrganizationEntity org = findOrganizationBySlugOrThrow(slug);
        Long orgId = org.getId();
        int memberCount = (int) userRoleRepository.countByOrganizationId(orgId);
        return ApiResponse.of(toResponse(org, memberCount));
    }

    /**
     * slug から内部 BIGINT ID を解決する（Controller から他の Service メソッドに渡す用）。
     *
     * @param slug URL 識別子（カスタムスラッグ）
     * @return 内部 BIGINT ID
     */
    public Long resolveOrgId(String slug) {
        return findOrganizationBySlugOrThrow(slug).getId();
    }

    /**
     * 組織名から一意スラッグを生成する。
     *
     * <p>ベーススラッグが既に使用中の場合は数値サフィックス (-1, -2, ...) を付与して一意化する。
     * 100 回試行しても一意にならない場合はタイムスタンプベースのサフィックスを使用する。</p>
     *
     * @param name 組織名
     * @return 一意なスラッグ
     */
    public String createUniqueSlug(String name) {
        String base = SlugGenerator.generate(name);
        if (!organizationRepository.existsBySlugAndDeletedAtIsNull(base)) {
            return base;
        }
        for (int i = 1; i <= 100; i++) {
            String candidate = SlugGenerator.withSuffix(base, i);
            if (!organizationRepository.existsBySlugAndDeletedAtIsNull(candidate)) {
                return candidate;
            }
        }
        return SlugGenerator.withSuffix(base, (int) (System.currentTimeMillis() % 10000));
    }

    /**
     * 組織を更新する。
     */
    @Transactional
    @CacheEvict(value = "org-detail", allEntries = true)
    public ApiResponse<OrganizationResponse> updateOrganization(Long orgId, UpdateOrganizationRequest req) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        checkNotArchived(org);

        // 楽観ロック用バージョンチェックはJPAの@Versionで自動処理
        OrganizationEntity updated = org.toBuilder()
                .name(req.getName() != null ? req.getName() : org.getName())
                .nameKana(req.getNameKana() != null ? req.getNameKana() : org.getNameKana())
                .nickname1(req.getNickname1() != null ? req.getNickname1() : org.getNickname1())
                .nickname2(req.getNickname2() != null ? req.getNickname2() : org.getNickname2())
                .prefecture(req.getPrefecture() != null ? req.getPrefecture() : org.getPrefecture())
                .city(req.getCity() != null ? req.getCity() : org.getCity())
                .visibility(req.getVisibility() != null
                        ? OrganizationEntity.Visibility.valueOf(req.getVisibility())
                        : org.getVisibility())
                .hierarchyVisibility(req.getHierarchyVisibility() != null
                        ? OrganizationEntity.HierarchyVisibility.valueOf(req.getHierarchyVisibility())
                        : org.getHierarchyVisibility())
                .supporterEnabled(req.getSupporterEnabled() != null ? req.getSupporterEnabled() : org.getSupporterEnabled())
                .build();
        organizationRepository.save(updated);

        int memberCount = (int) userRoleRepository.countByOrganizationId(orgId);
        log.info("組織更新完了: orgId={}", orgId);
        return ApiResponse.of(toResponse(updated, memberCount));
    }

    /**
     * 組織を論理削除する。招待トークンも一括失効。
     */
    @Transactional
    // TODO: OrganizationドメインとRoleドメインをまたいでいる。将来はOrganizationDeletedEventで分離予定
    @CacheEvict(value = "org-detail", allEntries = true)
    public void deleteOrganization(Long orgId, Long userId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        org.softDelete();

        // 招待トークン一括失効
        inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(orgId)
                .forEach(InviteTokenEntity::revoke);

        // 監査ログ用イベント発行
        eventPublisher.publishEvent(new OrganizationDeletedEvent(userId, orgId));

        log.info("組織削除完了: orgId={}, userId={}", orgId, userId);
    }

    /**
     * 組織をアーカイブする。
     */
    @Transactional
    @CacheEvict(value = "org-detail", allEntries = true)
    public void archiveOrganization(Long orgId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        if (org.getArchivedAt() != null) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
        org.archive();
        log.info("組織アーカイブ完了: orgId={}", orgId);
    }

    /**
     * 組織のアーカイブを解除する。
     */
    @Transactional
    public void unarchiveOrganization(Long orgId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        org.unarchive();
        log.info("組織アーカイブ解除完了: orgId={}", orgId);
    }

    /**
     * 組織をキーワード検索する。
     */
    public PagedResponse<OrganizationSummaryResponse> searchOrganizations(String keyword, Pageable pageable) {
        Page<OrganizationEntity> page = organizationRepository.searchByKeyword(
                keyword != null ? keyword : "", pageable);

        var data = page.getContent().stream()
                .map(org -> {
                    int memberCount = (int) userRoleRepository.countByOrganizationId(org.getId());
                    return new OrganizationSummaryResponse(
                            org.getSlug(), org.getSlug(), org.getName(), org.getOrgType().name(),
                            org.getVisibility().name(), memberCount);
                })
                .toList();

        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    /**
     * 組織のメンバー一覧を取得する。
     *
     * <p>{@link OrganizationMembershipService#getMembers(Long, Pageable)} へ委譲。</p>
     */
    public PagedResponse<MemberResponse> getMembers(Long orgId, Pageable pageable) {
        return organizationMembershipService.getMembers(orgId, pageable);
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    /**
     * 組織をフォロー（SUPPORTER として memberships に入会）する。
     *
     * <p>{@link OrganizationMembershipService#followOrganization(Long, Long)} へ委譲。</p>
     */
    public void followOrganization(Long userId, Long orgId) {
        organizationMembershipService.followOrganization(userId, orgId);
    }

    /**
     * 組織のフォローを解除する。
     *
     * <p>{@link OrganizationMembershipService#unfollowOrganization(Long, Long)} へ委譲。</p>
     */
    public void unfollowOrganization(Long userId, Long orgId) {
        organizationMembershipService.unfollowOrganization(userId, orgId);
    }

    /**
     * 組織に所属するチーム一覧を取得する（team_org_memberships.status = ACTIVE）。
     *
     * <p>{@link OrganizationMembershipService#getTeams(Long)} へ委譲。</p>
     */
    public List<OrgTeamSummaryResponse> getTeams(Long orgId) {
        return organizationMembershipService.getTeams(orgId);
    }

    /**
     * 組織配下の全メンバーを取得する。
     * scope: ORGANIZATION=直属のみ / TEAM=チームメンバーのみ / INDIVIDUAL=全員
     *
     * <p>{@link OrganizationMembershipService#getAllMembers(Long, String)} へ委譲。</p>
     */
    public List<OrgAllMembersResponse> getAllMembers(Long orgId, String scope) {
        return organizationMembershipService.getAllMembers(orgId, scope);
    }

    /**
     * 論理削除済み組織を復元する（SYSTEM_ADMIN専用）。
     */
    @Transactional
    public void restoreOrganization(Long orgId) {
        if (organizationRepository.countByIdIncludingDeleted(orgId) == 0) {
            throw new BusinessException(OrgErrorCode.ORG_001);
        }
        int updated = organizationRepository.restoreById(orgId);
        if (updated == 0) {
            throw new BusinessException(OrgErrorCode.ORG_006);
        }
        log.info("組織復元完了: orgId={}", orgId);
    }

    // ========================================
    // F01.2 階層表示API
    // ========================================

    /**
     * 対象組織の祖先チェーン（root → 直近の親 の順）を返す。
     *
     * <p>{@link OrganizationHierarchyService#getAncestors(Long, Long)} へ委譲。</p>
     */
    public AncestorsResponse getAncestors(Long orgId, Long requesterId) {
        return organizationHierarchyService.getAncestors(orgId, requesterId);
    }

    /**
     * 対象組織の直近の子組織一覧を返す。
     *
     * <p>{@link OrganizationHierarchyService#getChildren(Long, Long, String, int)} へ委譲。</p>
     */
    public ChildrenResponse getChildren(Long orgId, Long requesterId, String cursor, int size) {
        return organizationHierarchyService.getChildren(orgId, requesterId, cursor, size);
    }

    // ========================================
    // ドメイン間 slug 解決（Todoドメイン等から利用）
    // ========================================

    /**
     * 指定 ID 集合に対して id → slug のマッピングを一括取得する（N+1 回避）。
     *
     * <p>TodoResponseConverter 等の他ドメインが organization Entity を直接参照することを防ぐために
     * プリミティブ（Map&lt;Long, String&gt;）のみを返す。Entity は漏らさない。</p>
     *
     * @param ids 取得対象の組織 ID 集合
     * @return id → slug の Map（論理削除済みは除外）。ids が空の場合は空 Map を返す
     */
    public Map<Long, String> getSlugsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return organizationRepository.findSlugMapByIdIn(ids);
    }

    /**
     * 単一組織 ID から slug を取得する。
     *
     * <p>論理削除済み・存在しない場合は {@code null} を返す（例外を投げない）。</p>
     *
     * @param id 組織 ID
     * @return slug 文字列。存在しない場合は null
     */
    public String getSlugById(Long id) {
        if (id == null) {
            return null;
        }
        return organizationRepository.findById(id).map(o -> o.getSlug()).orElse(null);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private OrganizationEntity findOrganizationOrThrow(Long orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }

    /**
     * slug で組織を取得する。存在しない場合は 404 例外を投げる（IDOR 対策）。
     *
     * @param slug URL 識別子（カスタムスラッグ）
     * @return 組織エンティティ
     */
    private OrganizationEntity findOrganizationBySlugOrThrow(String slug) {
        return organizationRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }

    private void checkNotArchived(OrganizationEntity org) {
        if (org.getArchivedAt() != null) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
    }

    private OrganizationResponse toResponse(OrganizationEntity org, int memberCount) {
        return OrganizationResponse.builder()
                .id(org.getSlug())
                .slug(org.getSlug())
                .basicInfo(new OrganizationResponse.OrgBasicInfoDto(
                        org.getName(), org.getNameKana(),
                        org.getNickname1(), org.getNickname2()))
                .hierarchy(new OrganizationResponse.OrgHierarchyDto(
                        org.getOrgType() != null ? org.getOrgType().name() : null,
                        org.getParentOrganizationId()))
                .location(new OrganizationResponse.OrgLocationDto(
                        org.getPrefecture(), org.getCity()))
                .visibility(new OrganizationResponse.OrgVisibilityDto(
                        org.getVisibility() != null ? org.getVisibility().name() : null,
                        org.getHierarchyVisibility() != null ? org.getHierarchyVisibility().name() : null,
                        org.getSupporterEnabled()))
                .metadata(new OrganizationResponse.OrgMetadataDto(
                        org.getVersion(), memberCount,
                        org.getIconUrl(), org.getBannerUrl()))
                .timestamps(new OrganizationResponse.OrgTimestampsDto(
                        org.getArchivedAt(), org.getCreatedAt()))
                .build();
    }
}
