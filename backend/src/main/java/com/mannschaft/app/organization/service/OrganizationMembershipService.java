package com.mannschaft.app.organization.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.dto.OrgAllMembersResponse;
import com.mannschaft.app.organization.dto.OrgTeamSummaryResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 組織のメンバー・フォロー・所属チーム管理を担当するサービス。
 *
 * <p>{@link OrganizationService} ファサードから委譲される。
 * メンバー一覧取得（{@link MemberQueryDispatcher} 経由）・フォロー（SUPPORTER 入会）・
 * フォロー解除・所属チーム一覧・配下全メンバー取得を提供する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrganizationMembershipService {

    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final MemberQueryDispatcher memberQueryDispatcher;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;

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
                        team.getSlug(),
                        team.getSlug(),
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
    // TODO: OrganizationドメインとAuthドメイン・Roleドメイン・Teamドメインをまたいでいる。将来はMemberQueryServiceで分離予定
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
                                    user.getLastName() + " " + user.getFirstName(),
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
                                                user.getLastName() + " " + user.getFirstName(),
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
     * 組織スコープ配信の宛先ユーザーIDリストを解決する（(B) 組織→参加チーム配信 案C フェーズA 隊A 公開ラッパー）。
     *
     * <p>「直属メンバー ∪ 配下参加チーム(ACTIVE)のメンバー」を {@code DISTINCT user_id} で返す。
     * SUPPORTER（応援者）は {@code includeSupporters=false} のとき既定で除外し、
     * {@code true} のとき含める。判定ロジックの詳細は
     * {@link UserRoleRepository#findDistributionUserIdsForOrganization(Long, boolean)} 参照。</p>
     *
     * <p><b>越境是正の窓口</b>: schedule/survey 等の他ドメインからはこのメソッドを呼び、
     * {@code team_org_memberships} / {@code memberships} を直接参照させない。
     * クエリ本体は SQL 局所性のため {@link UserRoleRepository} に置いている。</p>
     *
     * @param orgId             組織 ID（存在しない場合は {@link OrgErrorCode#ORG_001}）
     * @param includeSupporters true=応援者も含める / false=応援者を除外する
     * @return 配信対象ユーザー ID リスト（重複なし・在籍中のアクティブユーザーのみ）
     */
    public List<Long> resolveOrgDistributionUserIds(Long orgId, boolean includeSupporters) {
        findOrganizationOrThrow(orgId); // 組織存在チェック（不在なら ORG_001）
        return userRoleRepository.findDistributionUserIdsForOrganization(orgId, includeSupporters);
    }

    private OrganizationEntity findOrganizationOrThrow(Long orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }
}
