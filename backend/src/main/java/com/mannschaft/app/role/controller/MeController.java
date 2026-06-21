package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.dto.MyOrganizationResponse;
import com.mannschaft.app.role.dto.MyTeamResponse;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * マイページコントローラー。ログインユーザーが所属するチーム・組織の一覧を提供する。
 *
 * <p><b>F00.5 メンバーシップ基盤再設計に伴う所属判定の統合（#1357 同型退行の取りこぼし根治）:</b>
 * F00.5 で MEMBER / SUPPORTER の所属は {@code user_roles} から {@code memberships} へ移管された。
 * 旧実装は所属一覧を {@code user_roles} のみで列挙していたため、{@code memberships} 専属で所属する
 * （= 当該スコープに {@code user_roles} 行を持たない）ユーザーの組織・チームが API から丸ごと欠落していた。
 * 本コントローラーは所属 scopeId を「{@code user_roles} 由来 ∪ {@code memberships} 由来」の UNION で列挙し、
 * 役割名は {@link AccessControlService#resolveEffectiveRoleName} に委譲（priority 最強を採用）して根治する。</p>
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "マイページ")
@RequiredArgsConstructor
public class MeController {

    private final UserRoleRepository userRoleRepository;
    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final OrganizationRepository organizationRepository;
    private final AccessControlService accessControlService;
    private final MembershipRepository membershipRepository;

    /**
     * 自分が所属するチーム一覧を取得する。
     *
     * <p>所属チーム ID は {@code user_roles}（権限ロール由来）と {@code memberships}
     * （MEMBER / SUPPORTER 所属由来）の和集合で列挙する。役割名は
     * {@link AccessControlService#resolveEffectiveRoleName} で両系統を統合解決し、
     * 該当が無い場合は {@code MEMBER} にフォールバックする。</p>
     */
    @GetMapping("/teams")
    @Operation(summary = "所属チーム一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<MyTeamResponse>> getMyTeams(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int size) {

        Long userId = SecurityUtils.getCurrentUserId();

        // 所属チーム ID を 2 系統の和集合で列挙する（user_roles ∪ memberships）。
        // 列挙順を安定させるため LinkedHashSet を使う（user_roles 先・memberships 後で追加）。
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        List<MembershipEntity> teamMemberships =
                membershipRepository.findActiveByUserAndScopeType(userId, ScopeType.TEAM);

        Set<Long> teamIds = new LinkedHashSet<>();
        // joined_at は membership.joinedAt を優先し、無ければ user_role.createdAt を用いる。
        Map<Long, LocalDateTime> joinedAtByTeamId = new LinkedHashMap<>();
        for (UserRoleEntity ur : teamRoles) {
            teamIds.add(ur.getTeamId());
            joinedAtByTeamId.putIfAbsent(ur.getTeamId(), ur.getCreatedAt());
        }
        for (MembershipEntity m : teamMemberships) {
            teamIds.add(m.getScopeId());
            // membership.joinedAt を優先（user_role.createdAt があっても上書きする）。
            joinedAtByTeamId.put(m.getScopeId(), m.getJoinedAt());
        }

        // 親組織の数値 ID をバルク解決する（F08.10 試合 API の org コンテキスト解決用）。
        // 1 チームが複数組織に所属し得るが、本 Map は ACTIVE な親組織を 1 件返す
        // （F00 ScopeAncestorResolver と同じ findOrganizationIdByTeamIdIn を再利用）。
        // memberships 由来で増えたチームも含めて解決するため、和集合後の teamIds で問い合わせる。
        Map<Long, Long> orgIdByTeamId = teamOrgMembershipRepository.findOrganizationIdByTeamIdIn(teamIds);

        List<MyTeamResponse> teams = new ArrayList<>();
        for (Long teamId : teamIds) {
            TeamEntity team = teamRepository.findById(teamId).orElse(null);
            if (team == null) {
                continue;
            }
            if (!includeArchived && team.getArchivedAt() != null) {
                continue;
            }
            String roleName = accessControlService.resolveEffectiveRoleName(userId, teamId, "TEAM");
            if (roleName == null) {
                roleName = "MEMBER";
            }
            int memberCount = (int) membershipRepository
                    .countActiveDistinctUsersByScope(ScopeType.TEAM, teamId);
            teams.add(new MyTeamResponse(
                    team.getId(),
                    team.getSlug(),
                    orgIdByTeamId.get(team.getId()),
                    team.getName(),
                    null,
                    team.getVisibility().name(),
                    memberCount,
                    roleName,
                    joinedAtByTeamId.get(teamId),
                    team.getArchivedAt() != null,
                    team.getTemplate()));
        }
        teams.sort(Comparator.comparing(MyTeamResponse::getJoinedAt));

        var meta = new CursorPagedResponse.CursorMeta(null, false, size);
        return ResponseEntity.ok(CursorPagedResponse.of(teams, meta));
    }

    /**
     * 自分が所属する組織一覧を取得する。
     *
     * <p>所属組織 ID は {@code user_roles}（権限ロール由来）と {@code memberships}
     * （MEMBER / SUPPORTER 所属由来）の和集合で列挙する。役割名は
     * {@link AccessControlService#resolveEffectiveRoleName} で両系統を統合解決し、
     * 該当が無い場合は {@code MEMBER} にフォールバックする。</p>
     */
    @GetMapping("/organizations")
    @Operation(summary = "所属組織一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<MyOrganizationResponse>> getMyOrganizations(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int size) {

        Long userId = SecurityUtils.getCurrentUserId();

        // 所属組織 ID を 2 系統の和集合で列挙する（user_roles ∪ memberships）。
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);
        List<MembershipEntity> orgMemberships =
                membershipRepository.findActiveByUserAndScopeType(userId, ScopeType.ORGANIZATION);

        Set<Long> orgIds = new LinkedHashSet<>();
        Map<Long, LocalDateTime> joinedAtByOrgId = new LinkedHashMap<>();
        for (UserRoleEntity ur : orgRoles) {
            orgIds.add(ur.getOrganizationId());
            joinedAtByOrgId.putIfAbsent(ur.getOrganizationId(), ur.getCreatedAt());
        }
        for (MembershipEntity m : orgMemberships) {
            orgIds.add(m.getScopeId());
            joinedAtByOrgId.put(m.getScopeId(), m.getJoinedAt());
        }

        List<MyOrganizationResponse> orgs = new ArrayList<>();
        for (Long orgId : orgIds) {
            OrganizationEntity org = organizationRepository.findById(orgId).orElse(null);
            if (org == null) {
                continue;
            }
            if (!includeArchived && org.getArchivedAt() != null) {
                continue;
            }
            String roleName = accessControlService.resolveEffectiveRoleName(userId, orgId, "ORGANIZATION");
            if (roleName == null) {
                roleName = "MEMBER";
            }
            int memberCount = (int) membershipRepository
                    .countActiveDistinctUsersByScope(ScopeType.ORGANIZATION, orgId);
            orgs.add(new MyOrganizationResponse(
                    org.getId(),
                    org.getSlug(),
                    org.getName(),
                    null,
                    org.getVisibility().name(),
                    memberCount,
                    roleName,
                    joinedAtByOrgId.get(orgId),
                    org.getArchivedAt() != null));
        }
        orgs.sort(Comparator.comparing(MyOrganizationResponse::getJoinedAt));

        var meta = new CursorPagedResponse.CursorMeta(null, false, size);
        return ResponseEntity.ok(CursorPagedResponse.of(orgs, meta));
    }
}
