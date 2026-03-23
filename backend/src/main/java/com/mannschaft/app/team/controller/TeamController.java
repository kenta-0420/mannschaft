package com.mannschaft.app.team.controller;

import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.role.service.BlockService;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.role.service.PermissionGroupService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.role.dto.BlockRequest;
import com.mannschaft.app.role.dto.BlockResponse;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.dto.EffectivePermissionsResponse;
import com.mannschaft.app.role.dto.InviteTokenResponse;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.PermissionGroupResponse;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.dto.TeamSummaryResponse;
import com.mannschaft.app.team.dto.UpdateTeamRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム管理コントローラー。
 * チームのCRUD・アーカイブ・メンバー管理・招待・権限グループ・ブロックのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "チーム管理")
@RequiredArgsConstructor
public class TeamController {

    private static final String SCOPE_TYPE = "TEAM";

    private final TeamService teamService;
    private final RoleService roleService;
    private final AccessControlService accessControlService;
    private final InviteService inviteService;
    private final PermissionGroupService permissionGroupService;
    private final BlockService blockService;


    // ========================================
    // チーム CRUD
    // ========================================

    @PostMapping
    @Operation(summary = "チーム作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TeamResponse>> createTeam(
            @Valid @RequestBody CreateTeamRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.createTeam(SecurityUtils.getCurrentUserId(), req));
    }

    @GetMapping("/search")
    @Operation(summary = "チーム検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<TeamSummaryResponse>> searchTeams(
            @RequestParam(required = false) String keyword, Pageable pageable) {
        return ResponseEntity.ok(teamService.searchTeams(keyword, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "チーム取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TeamResponse>> getTeam(@PathVariable Long id) {
        return ResponseEntity.ok(teamService.getTeam(id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "チーム更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TeamResponse>> updateTeam(
            @PathVariable Long id, @Valid @RequestBody UpdateTeamRequest req) {
        return ResponseEntity.ok(teamService.updateTeam(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "チーム削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTeam(@PathVariable Long id) {
        teamService.deleteTeam(id);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // メンバー管理
    // ========================================

    @GetMapping("/{id}/members")
    @Operation(summary = "チームメンバー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<MemberResponse>> getMembers(
            @PathVariable Long id, Pageable pageable) {
        return ResponseEntity.ok(teamService.getMembers(id, pageable));
    }

    @PatchMapping("/{id}/members/{userId}/role")
    @Operation(summary = "メンバーロール変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<Void> changeRole(
            @PathVariable Long id, @PathVariable Long userId,
            @Valid @RequestBody RoleChangeRequest req) {
        roleService.changeRole(id, SCOPE_TYPE, userId, req, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Operation(summary = "メンバー除名")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "除名成功")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long id, @PathVariable Long userId) {
        roleService.removeMember(id, SCOPE_TYPE, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // アーカイブ
    // ========================================

    @PatchMapping("/{id}/archive")
    @Operation(summary = "チームアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<Void> archiveTeam(@PathVariable Long id) {
        teamService.archiveTeam(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/unarchive")
    @Operation(summary = "チームアーカイブ解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ解除成功")
    public ResponseEntity<Void> unarchiveTeam(@PathVariable Long id) {
        teamService.unarchiveTeam(id);
        return ResponseEntity.ok().build();
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    @PostMapping("/{id}/follow")
    @Operation(summary = "チームフォロー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "フォロー成功")
    public ResponseEntity<Void> followTeam(@PathVariable Long id) {
        teamService.followTeam(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/follow")
    @Operation(summary = "チームフォロー解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "フォロー解除成功")
    public ResponseEntity<Void> unfollowTeam(@PathVariable Long id) {
        teamService.unfollowTeam(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 招待トークン
    // ========================================

    @PostMapping("/{id}/invite-tokens")
    @Operation(summary = "招待トークン作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<InviteTokenResponse>> createInviteToken(
            @PathVariable Long id, @Valid @RequestBody CreateInviteTokenRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inviteService.createInviteToken(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{id}/invite-tokens")
    @Operation(summary = "招待トークン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<InviteTokenResponse>>> getInviteTokens(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(inviteService.getInviteTokens(id, SCOPE_TYPE)));
    }

    @DeleteMapping("/{id}/invite-tokens/{tokenId}")
    @Operation(summary = "招待トークン失効")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "失効成功")
    public ResponseEntity<Void> revokeInviteToken(
            @PathVariable Long id, @PathVariable Long tokenId) {
        inviteService.revokeInviteToken(tokenId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 権限グループ
    // ========================================

    @GetMapping("/{id}/permission-groups")
    @Operation(summary = "権限グループ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> getPermissionGroups(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                permissionGroupService.getPermissionGroups(id, SCOPE_TYPE)));
    }

    @PostMapping("/{id}/permission-groups")
    @Operation(summary = "権限グループ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> createPermissionGroup(
            @PathVariable Long id, @Valid @RequestBody PermissionGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(permissionGroupService.createPermissionGroup(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @PatchMapping("/{id}/permission-groups/{groupId}")
    @Operation(summary = "権限グループ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> updatePermissionGroup(
            @PathVariable Long id, @PathVariable Long groupId,
            @Valid @RequestBody PermissionGroupRequest req) {
        return ResponseEntity.ok(permissionGroupService.updatePermissionGroup(groupId, req));
    }

    @DeleteMapping("/{id}/permission-groups/{groupId}")
    @Operation(summary = "権限グループ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePermissionGroup(
            @PathVariable Long id, @PathVariable Long groupId) {
        permissionGroupService.deletePermissionGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/members/{userId}/permission-groups")
    @Operation(summary = "ユーザー権限グループ割当")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "割当成功")
    public ResponseEntity<Void> assignUserPermissionGroups(
            @PathVariable Long id, @PathVariable Long userId,
            @Valid @RequestBody UserPermissionGroupAssignRequest req) {
        permissionGroupService.assignUserPermissionGroups(
                userId, id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    // ========================================
    // ブロック
    // ========================================

    @GetMapping("/{id}/blocks")
    @Operation(summary = "ブロック一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BlockResponse>>> getBlocks(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(blockService.getBlocks(id, SCOPE_TYPE)));
    }

    @PostMapping("/{id}/blocks")
    @Operation(summary = "ユーザーブロック")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "ブロック成功")
    public ResponseEntity<ApiResponse<BlockResponse>> blockUser(
            @PathVariable Long id, @Valid @RequestBody BlockRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(blockService.blockUser(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{id}/blocks/{userId}")
    @Operation(summary = "ユーザーブロック解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "ブロック解除成功")
    public ResponseEntity<Void> unblockUser(
            @PathVariable Long id, @PathVariable Long userId) {
        blockService.unblockUser(id, SCOPE_TYPE, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 自分の権限・退会・オーナー譲渡
    // ========================================

    @GetMapping("/{id}/me/permissions")
    @Operation(summary = "自分の有効権限取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EffectivePermissionsResponse>> getMyPermissions(
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<String> permissions = roleService.resolveEffectivePermissions(userId, id, SCOPE_TYPE);
        String roleName = accessControlService.getRoleName(userId, id, SCOPE_TYPE);
        return ResponseEntity.ok(ApiResponse.of(new EffectivePermissionsResponse(roleName, permissions)));
    }

    @PostMapping("/{id}/transfer-ownership")
    @Operation(summary = "オーナー譲渡")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "譲渡成功")
    public ResponseEntity<Void> transferOwnership(
            @PathVariable Long id, @RequestParam Long targetUserId) {
        roleService.transferOwnership(id, SCOPE_TYPE, SecurityUtils.getCurrentUserId(), targetUserId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/me")
    @Operation(summary = "チーム退会")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "退会成功")
    public ResponseEntity<Void> leaveTeam(@PathVariable Long id) {
        roleService.leaveScope(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.noContent().build();
    }
}
