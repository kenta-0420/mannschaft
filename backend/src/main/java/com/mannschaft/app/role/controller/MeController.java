package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.dto.MyOrganizationResponse;
import com.mannschaft.app.role.dto.MyTeamResponse;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * マイページコントローラー。ログインユーザーが所属するチーム・組織の一覧を提供する。
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "マイページ")
@RequiredArgsConstructor
public class MeController {

    private final UserRoleRepository userRoleRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final RoleRepository roleRepository;

    /**
     * 自分が所属するチーム一覧を取得する。
     */
    @GetMapping("/teams")
    @Operation(summary = "所属チーム一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<MyTeamResponse>> getMyTeams(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int size) {

        Long userId = SecurityUtils.getCurrentUserId();
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);

        List<MyTeamResponse> teams = teamRoles.stream()
                .map(ur -> {
                    TeamEntity team = teamRepository.findById(ur.getTeamId()).orElse(null);
                    if (team == null) {
                        return null;
                    }
                    if (!includeArchived && team.getArchivedAt() != null) {
                        return null;
                    }
                    String roleName = roleRepository.findById(ur.getRoleId())
                            .map(RoleEntity::getName).orElse("MEMBER");
                    int memberCount = (int) userRoleRepository.countByTeamId(team.getId());
                    return new MyTeamResponse(
                            team.getId(),
                            team.getName(),
                            null,
                            team.getVisibility().name(),
                            memberCount,
                            roleName,
                            ur.getCreatedAt(),
                            team.getArchivedAt() != null,
                            team.getTemplate());
                })
                .filter(t -> t != null)
                .sorted(Comparator.comparing(MyTeamResponse::getJoinedAt))
                .toList();

        var meta = new CursorPagedResponse.CursorMeta(null, false, size);
        return ResponseEntity.ok(CursorPagedResponse.of(teams, meta));
    }

    /**
     * 自分が所属する組織一覧を取得する。
     */
    @GetMapping("/organizations")
    @Operation(summary = "所属組織一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<MyOrganizationResponse>> getMyOrganizations(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int size) {

        Long userId = SecurityUtils.getCurrentUserId();
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);

        List<MyOrganizationResponse> orgs = orgRoles.stream()
                .map(ur -> {
                    OrganizationEntity org = organizationRepository.findById(ur.getOrganizationId()).orElse(null);
                    if (org == null) {
                        return null;
                    }
                    if (!includeArchived && org.getArchivedAt() != null) {
                        return null;
                    }
                    String roleName = roleRepository.findById(ur.getRoleId())
                            .map(RoleEntity::getName).orElse("MEMBER");
                    int memberCount = (int) userRoleRepository.countByOrganizationId(org.getId());
                    return new MyOrganizationResponse(
                            org.getId(),
                            org.getName(),
                            null,
                            org.getVisibility().name(),
                            memberCount,
                            roleName,
                            ur.getCreatedAt(),
                            org.getArchivedAt() != null);
                })
                .filter(o -> o != null)
                .sorted(Comparator.comparing(MyOrganizationResponse::getJoinedAt))
                .toList();

        var meta = new CursorPagedResponse.CursorMeta(null, false, size);
        return ResponseEntity.ok(CursorPagedResponse.of(orgs, meta));
    }
}
