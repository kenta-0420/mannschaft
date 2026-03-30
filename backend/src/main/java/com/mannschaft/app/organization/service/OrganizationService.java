package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
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
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
     */
    public PagedResponse<MemberResponse> getMembers(Long orgId, Pageable pageable) {
        findOrganizationOrThrow(orgId);

        Page<UserRoleEntity> page = userRoleRepository.findByOrganizationId(orgId, pageable);

        var data = page.getContent().stream()
                .map(ur -> {
                    UserEntity user = userRepository.findById(ur.getUserId()).orElse(null);
                    RoleEntity role = roleRepository.findById(ur.getRoleId()).orElse(null);
                    return new MemberResponse(
                            ur.getUserId(),
                            user != null ? user.getDisplayName() : null,
                            user != null ? user.getAvatarUrl() : null,
                            role != null ? role.getName() : null,
                            ur.getCreatedAt());
                })
                .toList();

        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    /**
     * 組織をフォロー（SUPPORTER ロールで参加）する。
     */
    @Transactional
    public void followOrganization(Long userId, Long orgId) {
        findOrganizationOrThrow(orgId);

        if (userRoleRepository.existsByUserIdAndOrganizationId(userId, orgId)) {
            throw new BusinessException(OrgErrorCode.ORG_007);
        }

        RoleEntity supporterRole = roleRepository.findByName("SUPPORTER")
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_005));

        UserRoleEntity userRole = UserRoleEntity.builder()
                .userId(userId)
                .roleId(supporterRole.getId())
                .organizationId(orgId)
                .build();
        userRoleRepository.save(userRole);
        log.info("組織フォロー完了: userId={}, orgId={}", userId, orgId);
    }

    /**
     * 組織のフォローを解除する。
     */
    @Transactional
    public void unfollowOrganization(Long userId, Long orgId) {
        findOrganizationOrThrow(orgId);
        userRoleRepository.deleteByUserIdAndOrganizationId(userId, orgId);
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
                org.getVersion(), memberCount, org.getArchivedAt(), org.getCreatedAt());
    }
}
