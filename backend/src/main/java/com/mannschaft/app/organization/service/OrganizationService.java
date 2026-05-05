package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.organization.dto.AncestorOrganizationResponse;
import com.mannschaft.app.organization.dto.AncestorsResponse;
import com.mannschaft.app.organization.dto.ChildOrganizationResponse;
import com.mannschaft.app.organization.dto.ChildrenResponse;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrgAllMembersResponse;
import com.mannschaft.app.organization.dto.OrgTeamSummaryResponse;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.OrganizationSummaryResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 組織管理サービス。組織のCRUD・アーカイブ・メンバー一覧を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final InviteTokenRepository inviteTokenRepository;
    private final MemberQueryDispatcher memberQueryDispatcher;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;

    /** 祖先チェーン探索の最大深度。これを超える祖先は返さず {@code truncated: true} を立てる。 */
    @Value("${app.org.max-depth:5}")
    private int maxDepth;

    /** 子組織カーソルページの最大件数。 */
    private static final int CHILDREN_MAX_PAGE_SIZE = 100;

    /** 子組織カーソルページのデフォルト件数。 */
    private static final int CHILDREN_DEFAULT_PAGE_SIZE = 50;

    /**
     * 組織を作成し、作成者をADMINロールで紐付ける。
     */
    @Transactional
    public ApiResponse<OrganizationResponse> createOrganization(Long userId, CreateOrganizationRequest req) {
        // 組織名の重複チェック
        if (organizationRepository.existsByName(req.getName())) {
            throw new BusinessException(OrgErrorCode.ORG_002);
        }

        OrganizationEntity org = OrganizationEntity.builder()
                .name(req.getName())
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

        log.info("組織作成完了: orgId={}, userId={}", org.getId(), userId);
        return ApiResponse.of(toResponse(org, 1));
    }

    /**
     * 組織を取得する。
     */
    public ApiResponse<OrganizationResponse> getOrganization(Long orgId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        int memberCount = (int) userRoleRepository.countByOrganizationId(orgId);
        return ApiResponse.of(toResponse(org, memberCount));
    }

    /**
     * 組織を更新する。
     */
    @Transactional
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
    public void deleteOrganization(Long orgId) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        org.softDelete();

        // 招待トークン一括失効
        inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(orgId)
                .forEach(InviteTokenEntity::revoke);
        log.info("組織削除完了: orgId={}", orgId);
    }

    /**
     * 組織をアーカイブする。
     */
    @Transactional
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
                            org.getId(), org.getName(), org.getOrgType().name(),
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
     * <p>F00.5 Phase 3: MemberQueryDispatcher 経由で memberships + user_roles を統合参照する。</p>
     */
    public PagedResponse<MemberResponse> getMembers(Long orgId, Pageable pageable) {
        findOrganizationOrThrow(orgId);

        // F00.5 Phase 3: MemberQueryDispatcher 経由で memberships 参照に完全切替
        var memberDtos = memberQueryDispatcher.queryMembers(orgId, ScopeType.ORGANIZATION, null);

        var data = memberDtos.stream()
                .map(dto -> new MemberResponse(
                        dto.userId(),
                        dto.displayName(),
                        dto.avatarUrl(),
                        dto.roleName(),
                        dto.joinedAt()))
                .toList();

        // Dispatcher は全件リストを返すため、ページネーションはアプリ側でエミュレート
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : data.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, data.size());
        List<MemberResponse> pagedData = (fromIndex >= data.size())
                ? List.<MemberResponse>of() : data.subList(fromIndex, toIndex);

        long totalElements = data.size();
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / size);

        var meta = new PagedResponse.PageMeta(totalElements, page, size, totalPages);
        return PagedResponse.of(pagedData, meta);
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    /**
     * 組織をフォロー（SUPPORTER として memberships に入会）する。
     *
     * <p>F00.5 Phase 5: memberships への書き込みに切替。MembershipService.join() 経由で
     * 冪等性保証・イベント発火を一本化する。</p>
     */
    @Transactional
    public void followOrganization(Long userId, Long orgId) {
        findOrganizationOrThrow(orgId);

        // 重複チェック（memberships に既にアクティブな SUPPORTER がいる場合）
        if (membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                userId, ScopeType.ORGANIZATION, orgId, RoleKind.SUPPORTER)) {
            throw new BusinessException(OrgErrorCode.ORG_007);
        }

        // F00.5 Phase 5: memberships に SUPPORTER として入会
        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(userId);
        req.setScopeType(ScopeType.ORGANIZATION);
        req.setScopeId(orgId);
        req.setRoleKind(RoleKind.SUPPORTER);
        req.setSource("SELF_FOLLOW");
        membershipService.join(req);
        log.info("組織フォロー完了: userId={}, orgId={}", userId, orgId);
    }

    /**
     * 組織のフォローを解除する。
     *
     * <p>F00.5 Phase 5: memberships への退会処理に切替。MembershipService.leave() 経由で
     * 退会履歴・イベント発火を一本化する。</p>
     */
    @Transactional
    public void unfollowOrganization(Long userId, Long orgId) {
        findOrganizationOrThrow(orgId);

        // F00.5 Phase 5: memberships から SUPPORTER として退会
        Optional<MembershipEntity> active = membershipRepository.findActiveByUserAndScope(
                userId, ScopeType.ORGANIZATION, orgId);
        if (active.isPresent()) {
            MembershipLeaveRequest leaveReq = new MembershipLeaveRequest();
            leaveReq.setLeaveReason(LeaveReason.SELF);
            membershipService.leave(active.get().getId(), leaveReq);
        }
        log.info("組織フォロー解除完了: userId={}, orgId={}", userId, orgId);
    }

    /**
     * 組織に所属するチーム一覧を取得する（team_org_memberships.status = ACTIVE）。
     */
    public List<OrgTeamSummaryResponse> getTeams(Long orgId) {
        findOrganizationOrThrow(orgId);
        return teamOrgMembershipRepository.findByOrganizationIdAndStatus(orgId, TeamOrgMembershipEntity.Status.ACTIVE)
                .stream()
                .map(m -> teamRepository.findById(m.getTeamId()).orElse(null))
                .filter(team -> team != null)
                .map(team -> new OrgTeamSummaryResponse(
                        team.getId(),
                        team.getName(),
                        null,
                        team.getVisibility().name(),
                        (int) userRoleRepository.countByTeamId(team.getId())))
                .toList();
    }

    /**
     * 組織配下の全メンバーを取得する。
     * scope: ORGANIZATION=直属のみ / TEAM=チームメンバーのみ / INDIVIDUAL=全員
     */
    public List<OrgAllMembersResponse> getAllMembers(Long orgId, String scope) {
        OrganizationEntity org = findOrganizationOrThrow(orgId);
        List<OrgAllMembersResponse> result = new ArrayList<>();

        if ("ORGANIZATION".equals(scope) || "INDIVIDUAL".equals(scope)) {
            // 直属メンバー
            userRoleRepository.findByOrganizationId(orgId, Pageable.unpaged())
                    .getContent()
                    .forEach(ur -> {
                        UserEntity user = userRepository.findById(ur.getUserId()).orElse(null);
                        String roleName = roleRepository.findById(ur.getRoleId())
                                .map(RoleEntity::getName).orElse(null);
                        if (user != null) {
                            result.add(new OrgAllMembersResponse(
                                    user.getId(),
                                    user.getDisplayName(),
                                    user.getAvatarUrl(),
                                    new OrgAllMembersResponse.MemberOf("ORGANIZATION", org.getId(), org.getName()),
                                    roleName));
                        }
                    });
        }

        if ("TEAM".equals(scope) || "INDIVIDUAL".equals(scope)) {
            // 所属チームのメンバー
            teamOrgMembershipRepository.findByOrganizationIdAndStatus(orgId, TeamOrgMembershipEntity.Status.ACTIVE)
                    .forEach(membership -> {
                        TeamEntity team = teamRepository.findById(membership.getTeamId()).orElse(null);
                        if (team == null) return;
                        userRoleRepository.findByTeamId(team.getId(), Pageable.unpaged())
                                .getContent()
                                .forEach(ur -> {
                                    UserEntity user = userRepository.findById(ur.getUserId()).orElse(null);
                                    String roleName = roleRepository.findById(ur.getRoleId())
                                            .map(RoleEntity::getName).orElse(null);
                                    if (user != null) {
                                        result.add(new OrgAllMembersResponse(
                                                user.getId(),
                                                user.getDisplayName(),
                                                user.getAvatarUrl(),
                                                new OrgAllMembersResponse.MemberOf("TEAM", team.getId(), team.getName()),
                                                roleName));
                                    }
                                });
                    });
        }

        return result;
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
     * <p>各祖先は呼び出し者の所属関係と祖先の {@code visibility} / {@code hierarchyVisibility} に応じて
     * フル情報・限定情報・プレースホルダのいずれかとして返す（F01.2 設計書「祖先個別の返却フィルタ」参照）。</p>
     *
     * @param orgId       対象組織ID
     * @param requesterId 呼び出し者のユーザーID（未認証の場合 null）
     * @return 祖先一覧レスポンス
     * @throws BusinessException 対象組織が存在しない（ORG_001）／PRIVATE で未認証（COMMON_000）／
     *                           PRIVATE で外部ユーザー（COMMON_002）
     */
    public AncestorsResponse getAncestors(Long orgId, Long requesterId) {
        OrganizationEntity target = findOrganizationOrThrow(orgId);

        // 対象組織自体のアクセス可否を判定（PRIVATE の場合のみ厳格にチェック）
        boolean isDirectMember = requesterId != null
                && userRoleRepository.existsByUserIdAndOrganizationId(requesterId, orgId);
        boolean isDescendantMember = requesterId != null
                && !isDirectMember
                && isDescendantMember(requesterId, orgId);

        if (target.getVisibility() == OrganizationEntity.Visibility.PRIVATE) {
            if (requesterId == null) {
                throw new BusinessException(CommonErrorCode.COMMON_000);
            }
            if (!isDirectMember && !isDescendantMember) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }

        // 祖先チェーンを root → 親 の順に積む
        List<AncestorOrganizationResponse> chainRootFirst = buildAncestorChain(target, requesterId);
        boolean truncated = isAncestorChainTruncated(target);

        AncestorsResponse.AncestorsMeta meta = new AncestorsResponse.AncestorsMeta(
                chainRootFirst.size(), truncated);
        return new AncestorsResponse(chainRootFirst, meta);
    }

    /**
     * 対象組織の直近の子組織一覧を返す。
     *
     * @param orgId       対象組織ID
     * @param requesterId 呼び出し者のユーザーID
     * @param cursor      ページネーションカーソル（次ページ用 ID）。最初のページは null。
     * @param size        ページサイズ。null/0以下/上限超は補正される。
     * @return 子組織一覧レスポンス
     * @throws BusinessException 対象組織が存在しない（ORG_001）／PRIVATE で非メンバー（COMMON_002）
     */
    public ChildrenResponse getChildren(Long orgId, Long requesterId, String cursor, int size) {
        if (requesterId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        OrganizationEntity target = findOrganizationOrThrow(orgId);

        // PRIVATE 組織は直接所属メンバーのみ閲覧可能
        if (target.getVisibility() == OrganizationEntity.Visibility.PRIVATE
                && !userRoleRepository.existsByUserIdAndOrganizationId(requesterId, orgId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        int pageSize = normalizeChildrenPageSize(size);
        // ID 昇順での簡易カーソル（cursor が数値 ID なら、それより大きい ID を取得）
        Long cursorId = parseCursor(cursor);
        Pageable pageable = PageRequest.of(0, pageSize + 1); // 次ページ判定用に +1 件取得

        List<OrganizationEntity> rows = organizationRepository
                .findByParentOrganizationIdAndDeletedAtIsNull(orgId, pageable)
                .stream()
                .filter(o -> cursorId == null || o.getId() > cursorId)
                .toList();

        // PRIVATE 子組織は呼び出し者がメンバーでない場合のみ除外
        List<OrganizationEntity> visible = rows.stream()
                .filter(child -> child.getVisibility() == OrganizationEntity.Visibility.PUBLIC
                        || userRoleRepository.existsByUserIdAndOrganizationId(requesterId, child.getId()))
                .toList();

        boolean hasNext = visible.size() > pageSize;
        List<OrganizationEntity> page = hasNext ? visible.subList(0, pageSize) : visible;
        String nextCursor = hasNext && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1).getId())
                : null;

        List<ChildOrganizationResponse> data = page.stream()
                .map(child -> ChildOrganizationResponse.builder()
                        .id(child.getId())
                        .name(child.getName())
                        .nickname1(child.getNickname1())
                        .iconUrl(child.getIconUrl())
                        .visibility(child.getVisibility().name())
                        .memberCount((int) userRoleRepository.countByOrganizationId(child.getId()))
                        .archived(child.getArchivedAt() != null)
                        .build())
                .toList();

        CursorPagedResponse.CursorMeta meta = new CursorPagedResponse.CursorMeta(nextCursor, hasNext, pageSize);
        return new ChildrenResponse(data, meta);
    }

    /**
     * 祖先チェーンを root を先頭にした List で構築する。
     *
     * <p>{@code target.parentOrganizationId} を起点に最大 {@code maxDepth} 件まで親方向へ辿る。
     * サイクル（同一IDの再訪）を検出した時点で打ち切り。</p>
     */
    private List<AncestorOrganizationResponse> buildAncestorChain(OrganizationEntity target, Long requesterId) {
        // 直近の親 → さらに上 の順に積み、最後に逆順にする
        List<OrganizationEntity> ancestorsParentFirst = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(target.getId());

        Long currentParentId = target.getParentOrganizationId();
        int hops = 0;
        while (currentParentId != null && hops < maxDepth) {
            if (!visited.add(currentParentId)) {
                // サイクル検出 → 打ち切り
                log.warn("組織階層にサイクルを検出: orgId={}, cycleAt={}", target.getId(), currentParentId);
                break;
            }
            OrganizationEntity ancestor = organizationRepository.findById(currentParentId).orElse(null);
            if (ancestor == null) {
                // 親IDが指し示す組織が存在しない（論理削除済み等）→ 打ち切り
                break;
            }
            ancestorsParentFirst.add(ancestor);
            currentParentId = ancestor.getParentOrganizationId();
            hops++;
        }

        // 各祖先を返却フィルタに通す（root を先頭にする）
        Collections.reverse(ancestorsParentFirst);
        List<AncestorOrganizationResponse> result = new ArrayList<>();
        for (OrganizationEntity ancestor : ancestorsParentFirst) {
            result.add(filterAncestor(ancestor, requesterId));
        }
        return result;
    }

    /**
     * {@code maxDepth} 到達による打ち切りが発生したかを判定する。
     *
     * <p>探索後にも {@code parent_organization_id} が残っている場合 {@code true}。</p>
     */
    private boolean isAncestorChainTruncated(OrganizationEntity target) {
        Set<Long> visited = new HashSet<>();
        visited.add(target.getId());
        Long currentParentId = target.getParentOrganizationId();
        int hops = 0;
        while (currentParentId != null && hops < maxDepth) {
            if (!visited.add(currentParentId)) {
                return false; // サイクル検出（truncated とは別概念）
            }
            OrganizationEntity ancestor = organizationRepository.findById(currentParentId).orElse(null);
            if (ancestor == null) {
                return false;
            }
            currentParentId = ancestor.getParentOrganizationId();
            hops++;
        }
        // hops == maxDepth に到達したのにまだ親が残っているなら truncated
        return currentParentId != null;
    }

    /**
     * 祖先1件を「直接所属／子孫メンバー＋hierarchyVisibility／外部ユーザー＋visibility」で
     * フル / 限定 / プレースホルダのいずれかに変換する。
     */
    private AncestorOrganizationResponse filterAncestor(OrganizationEntity ancestor, Long requesterId) {
        // 直接所属メンバー → フル情報
        if (requesterId != null
                && userRoleRepository.existsByUserIdAndOrganizationId(requesterId, ancestor.getId())) {
            return fullAncestor(ancestor);
        }

        // 子孫メンバー判定
        boolean isDescendant = requesterId != null && isDescendantMember(requesterId, ancestor.getId());
        if (isDescendant) {
            OrganizationEntity.HierarchyVisibility hv = ancestor.getHierarchyVisibility();
            if (hv == OrganizationEntity.HierarchyVisibility.FULL) {
                return fullAncestor(ancestor);
            }
            if (hv == OrganizationEntity.HierarchyVisibility.BASIC) {
                return basicAncestor(ancestor);
            }
            // NONE → プレースホルダ
            return hiddenAncestor(ancestor.getId());
        }

        // 外部ユーザー
        if (ancestor.getVisibility() == OrganizationEntity.Visibility.PUBLIC) {
            return publicLimitedAncestor(ancestor);
        }
        return hiddenAncestor(ancestor.getId());
    }

    /**
     * 呼び出し者が {@code targetOrgId} の子孫（子組織または所属チームのメンバー）かを判定する。
     *
     * <p>ユーザーが所属する全組織・全チームを取得し、それぞれの祖先チェーン（{@code maxDepth} まで）に
     * {@code targetOrgId} が含まれるかをチェックする。</p>
     */
    private boolean isDescendantMember(Long requesterId, Long targetOrgId) {
        // ユーザー所属組織のうち、祖先に targetOrgId を含むものがあれば true
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(requesterId);
        for (UserRoleEntity ur : orgRoles) {
            Long memberOrgId = ur.getOrganizationId();
            if (memberOrgId == null) continue;
            if (memberOrgId.equals(targetOrgId)) continue; // 直接所属は別判定なので除外
            if (hasAncestor(memberOrgId, targetOrgId)) return true;
        }

        // ユーザー所属チームの所属組織を起点に祖先を辿る
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(requesterId);
        for (UserRoleEntity ur : teamRoles) {
            Long teamId = ur.getTeamId();
            if (teamId == null) continue;
            List<TeamOrgMembershipEntity> memberships = teamOrgMembershipRepository
                    .findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE);
            for (TeamOrgMembershipEntity m : memberships) {
                Long anchorOrgId = m.getOrganizationId();
                if (anchorOrgId == null) continue;
                if (anchorOrgId.equals(targetOrgId)) return true;
                if (hasAncestor(anchorOrgId, targetOrgId)) return true;
            }
        }
        return false;
    }

    /**
     * {@code startOrgId} の祖先チェーン（最大 {@code maxDepth}）に {@code targetOrgId} が含まれるか判定する。
     */
    private boolean hasAncestor(Long startOrgId, Long targetOrgId) {
        Set<Long> visited = new HashSet<>();
        visited.add(startOrgId);
        Long current = organizationRepository.findParentOrganizationIdById(startOrgId).orElse(null);
        int hops = 0;
        while (current != null && hops < maxDepth) {
            if (current.equals(targetOrgId)) return true;
            if (!visited.add(current)) return false; // サイクル
            current = organizationRepository.findParentOrganizationIdById(current).orElse(null);
            hops++;
        }
        return false;
    }

    private AncestorOrganizationResponse fullAncestor(OrganizationEntity org) {
        return AncestorOrganizationResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .nickname1(org.getNickname1())
                .description(null) // organizations.description は現状未保持。philosophy 等は別 API で取得
                .iconUrl(org.getIconUrl())
                .visibility(org.getVisibility().name())
                .hidden(false)
                .build();
    }

    private AncestorOrganizationResponse basicAncestor(OrganizationEntity org) {
        return AncestorOrganizationResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .nickname1(org.getNickname1())
                .description(null)
                .iconUrl(org.getIconUrl())
                .visibility(org.getVisibility().name())
                .hidden(false)
                .build();
    }

    private AncestorOrganizationResponse publicLimitedAncestor(OrganizationEntity org) {
        // 外部ユーザー + PUBLIC: id / name / nickname1 / iconUrl / visibility のみ（description は外す）
        return AncestorOrganizationResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .nickname1(org.getNickname1())
                .iconUrl(org.getIconUrl())
                .visibility(org.getVisibility().name())
                .hidden(false)
                .build();
    }

    private AncestorOrganizationResponse hiddenAncestor(Long id) {
        // hidden=true の場合、id 以外のフィールドは null（@JsonInclude(NON_NULL) で省略される）
        return AncestorOrganizationResponse.builder()
                .id(id)
                .hidden(true)
                .build();
    }

    private int normalizeChildrenPageSize(int size) {
        if (size <= 0) return CHILDREN_DEFAULT_PAGE_SIZE;
        if (size > CHILDREN_MAX_PAGE_SIZE) return CHILDREN_MAX_PAGE_SIZE;
        return size;
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return Long.valueOf(cursor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private OrganizationEntity findOrganizationOrThrow(Long orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }

    private void checkNotArchived(OrganizationEntity org) {
        if (org.getArchivedAt() != null) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
    }

    private OrganizationResponse toResponse(OrganizationEntity org, int memberCount) {
        return new OrganizationResponse(
                org.getId(), org.getName(), org.getNameKana(),
                org.getNickname1(), org.getNickname2(), org.getOrgType().name(),
                org.getParentOrganizationId(), org.getPrefecture(), org.getCity(),
                org.getVisibility().name(), org.getHierarchyVisibility().name(), org.getSupporterEnabled(),
                org.getVersion(), memberCount, org.getIconUrl(), org.getBannerUrl(),
                org.getArchivedAt(), org.getCreatedAt());
    }
}
