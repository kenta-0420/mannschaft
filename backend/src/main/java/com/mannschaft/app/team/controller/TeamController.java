package com.mannschaft.app.team.controller;

import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.service.FollowService;
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
import com.mannschaft.app.common.dto.SlugAvailabilityResponse;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.RenameSlugRequest;
import com.mannschaft.app.team.dto.TeamOrgSummaryResponse;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.dto.TeamSummaryResponse;
import com.mannschaft.app.team.dto.UpdateTeamRequest;
import com.mannschaft.app.supporter.dto.BulkApproveRequest;
import com.mannschaft.app.supporter.dto.FollowStatusResponse;
import com.mannschaft.app.supporter.dto.SupporterApplicationResponse;
import com.mannschaft.app.supporter.dto.SupporterResponse;
import com.mannschaft.app.supporter.dto.SupporterSettingsResponse;
import com.mannschaft.app.supporter.dto.UpdateSupporterSettingsRequest;
import com.mannschaft.app.supporter.service.SupporterService;
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
    private final SupporterService supporterService;
    private final FollowService followService;


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

    @GetMapping("/slug-available")
    @Operation(summary = "slug 可用性チェック（作成前のリアルタイム検証・村方式統一）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "判定結果")
    public ResponseEntity<ApiResponse<SlugAvailabilityResponse>> checkSlugAvailability(
            @RequestParam String slug) {
        return ResponseEntity.ok(ApiResponse.of(teamService.checkSlugAvailability(slug)));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "チーム取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TeamResponse>> getTeam(@PathVariable String slug) {
        return ResponseEntity.ok(teamService.getTeam(slug));
    }

    @PatchMapping("/{slug}")
    @Operation(summary = "チーム更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TeamResponse>> updateTeam(
            @PathVariable String slug, @Valid @RequestBody UpdateTeamRequest req) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(teamService.updateTeam(id, req));
    }

    @PutMapping("/{slug}/slug")
    @Operation(summary = "チーム slug リネーム（ADMIN/DEPUTY のみ・旧slugは301解決用に履歴予約）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リネーム成功")
    public ResponseEntity<ApiResponse<TeamResponse>> renameSlug(
            @PathVariable String slug, @Valid @RequestBody RenameSlugRequest req) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: 当該チームの ADMIN/DEPUTY 相当のみ許可（独自 gate を作らず checkAdminOrAbove に委譲）
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(teamService.renameSlug(id, req.getNewSlug()));
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "チーム削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        Long userId = SecurityUtils.getCurrentUserIdOrNull();
        teamService.deleteTeam(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // メンバー管理
    // ========================================

    @GetMapping("/{slug}/members")
    @Operation(summary = "チームメンバー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<MemberResponse>> getMembers(
            @PathVariable String slug, Pageable pageable) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(teamService.getMembers(id, pageable));
    }

    @PatchMapping("/{slug}/members/{userId}/role")
    @Operation(summary = "メンバーロール変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<Void> changeRole(
            @PathVariable String slug, @PathVariable Long userId,
            @Valid @RequestBody RoleChangeRequest req) {
        Long id = teamService.resolveTeamId(slug);
        roleService.changeRole(id, SCOPE_TYPE, userId, req, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{slug}/members/{userId}")
    @Operation(summary = "メンバー除名")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "除名成功")
    public ResponseEntity<Void> removeMember(
            @PathVariable String slug, @PathVariable Long userId) {
        Long id = teamService.resolveTeamId(slug);
        roleService.removeMember(id, SCOPE_TYPE, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // アーカイブ
    // ========================================

    @PatchMapping("/{slug}/archive")
    @Operation(summary = "チームアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<Void> archiveTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        teamService.archiveTeam(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{slug}/unarchive")
    @Operation(summary = "チームアーカイブ解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ解除成功")
    public ResponseEntity<Void> unarchiveTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        teamService.unarchiveTeam(id);
        return ResponseEntity.ok().build();
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    @PostMapping("/{slug}/follow")
    @Operation(summary = "チームサポーター申請（自動承認ON→即時承認、OFF→PENDING申請作成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "申請/承認成功")
    public ResponseEntity<ApiResponse<FollowStatusResponse>> followTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supporterService.follow(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, id));
    }

    @DeleteMapping("/{slug}/follow")
    @Operation(summary = "チームサポーター解除・申請取消（APPROVED/PENDING どちらも取消可）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unfollowTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        supporterService.unfollow(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{slug}/follow/status")
    @Operation(summary = "チームサポーター申請状態取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FollowStatusResponse>> getFollowStatus(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(
                supporterService.getFollowStatus(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, id));
    }

    // ========================================
    // サポーター管理（管理者向け）
    // ========================================

    @GetMapping("/{slug}/supporters")
    @Operation(summary = "承認済みサポーター一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SupporterResponse>> getSupporters(
            @PathVariable String slug, Pageable pageable) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(supporterService.getSupporters(SCOPE_TYPE, id, pageable));
    }

    @GetMapping("/{slug}/supporter-applications")
    @Operation(summary = "サポーター申請一覧（全ステータス）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SupporterApplicationResponse>> getSupporterApplications(
            @PathVariable String slug, Pageable pageable) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(supporterService.getApplications(SCOPE_TYPE, id, pageable));
    }

    @PostMapping("/{slug}/supporter-applications/{applicationId}/approve")
    @Operation(summary = "サポーター申請を個別承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "承認成功")
    public ResponseEntity<Void> approveSupporterApplication(
            @PathVariable String slug, @PathVariable Long applicationId) {
        Long id = teamService.resolveTeamId(slug);
        supporterService.approve(applicationId, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slug}/supporter-applications/{applicationId}/reject")
    @Operation(summary = "サポーター申請を個別却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "却下成功")
    public ResponseEntity<Void> rejectSupporterApplication(
            @PathVariable String slug, @PathVariable Long applicationId) {
        Long id = teamService.resolveTeamId(slug);
        supporterService.reject(applicationId, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slug}/supporter-applications/bulk-approve")
    @Operation(summary = "サポーター申請を一括承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "一括承認成功")
    public ResponseEntity<Void> bulkApproveSupporterApplications(
            @PathVariable String slug, @Valid @RequestBody BulkApproveRequest request) {
        Long id = teamService.resolveTeamId(slug);
        supporterService.bulkApprove(request, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{slug}/supporter-settings")
    @Operation(summary = "サポーター設定取得（自動承認ON/OFF）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SupporterSettingsResponse>> getSupporterSettings(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(ApiResponse.of(supporterService.getSettings(SCOPE_TYPE, id)));
    }

    @PutMapping("/{slug}/supporter-settings")
    @Operation(summary = "サポーター設定更新（自動承認ON/OFF）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SupporterSettingsResponse>> updateSupporterSettings(
            @PathVariable String slug, @RequestBody UpdateSupporterSettingsRequest request) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(ApiResponse.of(supporterService.updateSettings(SCOPE_TYPE, id, request)));
    }

    // ========================================
    // 招待トークン
    // ========================================

    @PostMapping("/{slug}/invite-tokens")
    @Operation(summary = "招待トークン作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<InviteTokenResponse>> createInviteToken(
            @PathVariable String slug, @Valid @RequestBody CreateInviteTokenRequest req) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inviteService.createInviteToken(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{slug}/invite-tokens")
    @Operation(summary = "招待トークン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<InviteTokenResponse>>> getInviteTokens(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(ApiResponse.of(inviteService.getInviteTokens(id, SCOPE_TYPE)));
    }

    @DeleteMapping("/{slug}/invite-tokens/{tokenId}")
    @Operation(summary = "招待トークン失効")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "失効成功")
    public ResponseEntity<Void> revokeInviteToken(
            @PathVariable String slug, @PathVariable Long tokenId) {
        inviteService.revokeInviteToken(tokenId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 権限グループ
    // ========================================

    @GetMapping("/{slug}/permission-groups")
    @Operation(summary = "権限グループ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> getPermissionGroups(
            @PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(ApiResponse.of(
                permissionGroupService.getPermissionGroups(id, SCOPE_TYPE)));
    }

    @PostMapping("/{slug}/permission-groups")
    @Operation(summary = "権限グループ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> createPermissionGroup(
            @PathVariable String slug, @Valid @RequestBody PermissionGroupRequest req) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(permissionGroupService.createPermissionGroup(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @PatchMapping("/{slug}/permission-groups/{groupId}")
    @Operation(summary = "権限グループ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> updatePermissionGroup(
            @PathVariable String slug, @PathVariable Long groupId,
            @Valid @RequestBody PermissionGroupRequest req) {
        return ResponseEntity.ok(permissionGroupService.updatePermissionGroup(groupId, req));
    }

    @DeleteMapping("/{slug}/permission-groups/{groupId}")
    @Operation(summary = "権限グループ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePermissionGroup(
            @PathVariable String slug, @PathVariable Long groupId) {
        permissionGroupService.deletePermissionGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{slug}/members/{userId}/permission-groups")
    @Operation(summary = "ユーザー権限グループ割当")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "割当成功")
    public ResponseEntity<Void> assignUserPermissionGroups(
            @PathVariable String slug, @PathVariable Long userId,
            @Valid @RequestBody UserPermissionGroupAssignRequest req) {
        Long id = teamService.resolveTeamId(slug);
        permissionGroupService.assignUserPermissionGroups(
                userId, id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    // ========================================
    // ブロック
    // ========================================

    @GetMapping("/{slug}/blocks")
    @Operation(summary = "ブロック一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BlockResponse>>> getBlocks(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(ApiResponse.of(blockService.getBlocks(id, SCOPE_TYPE)));
    }

    @PostMapping("/{slug}/blocks")
    @Operation(summary = "ユーザーブロック")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "ブロック成功")
    public ResponseEntity<ApiResponse<BlockResponse>> blockUser(
            @PathVariable String slug, @Valid @RequestBody BlockRequest req) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(blockService.blockUser(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{slug}/blocks/{userId}")
    @Operation(summary = "ユーザーブロック解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "ブロック解除成功")
    public ResponseEntity<Void> unblockUser(
            @PathVariable String slug, @PathVariable Long userId) {
        Long id = teamService.resolveTeamId(slug);
        Long unblockedBy = SecurityUtils.getCurrentUserIdOrNull();
        blockService.unblockUser(id, SCOPE_TYPE, userId, unblockedBy);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 自分の権限・退会・オーナー譲渡
    // ========================================

    @GetMapping("/{slug}/me/permissions")
    @Operation(summary = "自分の有効権限取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EffectivePermissionsResponse>> getMyPermissions(
            @PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        Long userId = SecurityUtils.getCurrentUserId();
        List<String> permissions = roleService.resolveEffectivePermissions(userId, id, SCOPE_TYPE);
        String roleName = accessControlService.getRoleName(userId, id, SCOPE_TYPE);
        return ResponseEntity.ok(ApiResponse.of(new EffectivePermissionsResponse(roleName, permissions)));
    }

    @PostMapping("/{slug}/transfer-ownership")
    @Operation(summary = "オーナー譲渡")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "譲渡成功")
    public ResponseEntity<Void> transferOwnership(
            @PathVariable String slug, @RequestParam Long targetUserId) {
        Long id = teamService.resolveTeamId(slug);
        roleService.transferOwnership(id, SCOPE_TYPE, SecurityUtils.getCurrentUserId(), targetUserId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{slug}/me")
    @Operation(summary = "チーム退会")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "退会成功")
    public ResponseEntity<Void> leaveTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        roleService.leaveScope(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // チーム所属組織一覧
    // ========================================

    @GetMapping("/{slug}/organizations")
    @Operation(summary = "チーム所属組織一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TeamOrgSummaryResponse>>> getOrganizations(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(ApiResponse.of(teamService.getOrganizations(id)));
    }

    // ========================================
    // チームの復元（SYSTEM_ADMIN専用）
    // ========================================

    @PatchMapping("/{slug}/restore")
    @Operation(summary = "チーム復元（SYSTEM_ADMINのみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "復元成功")
    public ResponseEntity<Void> restoreTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        teamService.restoreTeam(id);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // F04.4 / F01.7 Phase 2: チームフォロワー一覧
    // ========================================

    /**
     * チームのフォロワー一覧を取得する。
     */
    @GetMapping("/{slug}/followers")
    @Operation(summary = "チームフォロワー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getTeamFollowers(
            @PathVariable String slug,
            @RequestParam(defaultValue = "20") int size) {
        Long id = teamService.resolveTeamId(slug);
        List<FollowResponse> followers = followService.getTeamFollowers(id, size);
        return ResponseEntity.ok(ApiResponse.of(followers));
    }
}
