package com.mannschaft.app.role.controller;

import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.dto.OrganizationSummaryResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.dto.TeamSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

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

    /**
     * 自分が所属するチーム一覧を取得する。
     */
    @GetMapping("/teams")
    @Operation(summary = "所属チーム一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TeamSummaryResponse>>> getMyTeams() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);

        List<TeamSummaryResponse> teams = teamRoles.stream()
                .map(ur -> {
                    TeamEntity team = teamRepository.findById(ur.getTeamId()).orElse(null);
                    if (team == null) {
                        return null;
                    }
                    int memberCount = (int) userRoleRepository.countByTeamId(team.getId());
                    return new TeamSummaryResponse(
                            team.getId(), team.getName(), team.getTemplate(),
                            team.getVisibility().name(), memberCount);
                })
                .filter(t -> t != null)
                .toList();

        return ResponseEntity.ok(ApiResponse.of(teams));
    }

    /**
     * 自分が所属する組織一覧を取得する。
     */
    @GetMapping("/organizations")
    @Operation(summary = "所属組織一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<OrganizationSummaryResponse>>> getMyOrganizations() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);

        List<OrganizationSummaryResponse> orgs = orgRoles.stream()
                .map(ur -> {
                    OrganizationEntity org = organizationRepository.findById(ur.getOrganizationId()).orElse(null);
                    if (org == null) {
                        return null;
                    }
                    int memberCount = (int) userRoleRepository.countByOrganizationId(org.getId());
                    return new OrganizationSummaryResponse(
                            org.getId(), org.getName(), org.getOrgType().name(),
                            org.getVisibility().name(), memberCount);
                })
                .filter(o -> o != null)
                .toList();

        return ResponseEntity.ok(ApiResponse.of(orgs));
    }
}
