package com.mannschaft.app.team.controller;

import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
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
    private final ContentVisibilityChecker contentVisibilityChecker;


    // ========================================
    // チーム CRUD
    // ========================================

    @SelfScopedEndpoint("TeamService#createTeam は SecurityUtils.getCurrentUserId() を"
            + "作成者として新規チームを作るのみで、他人の識別子を指定する余地が無い")
    @PostMapping
    @Operation(summary = "チーム作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TeamResponse>> createTeam(
            @Valid @RequestBody CreateTeamRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.createTeam(SecurityUtils.getCurrentUserId(), req));
    }

    /**
     * チームをキーワード検索する。
     *
     * <p>結果は <b>PUBLIC かつ未アーカイブ</b>のチームのみに限定される（可視性フィルタは
     * {@code TeamRepository#searchByKeyword} のクエリが担保。論理削除は {@code @SQLRestriction} が除外）。</p>
     */
    // 全ユーザーに同一内容を返す（PUBLIC チームのみ）参照系 EP。/api/v1/teams/search は
    // permitAll 未登録のため SecurityConfig の anyRequest().authenticated() で
    // 認証必須が強制される（同名の /api/v1/public/teams/search とは別エンドポイント）。
    @AuthorizedByPathConfig("anyRequest().authenticated()")
    @GetMapping("/search")
    @Operation(summary = "チーム検索（PUBLIC かつ未アーカイブのチームのみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<TeamSummaryResponse>> searchTeams(
            @RequestParam(required = false) String keyword, Pageable pageable) {
        return ResponseEntity.ok(teamService.searchTeams(keyword, pageable));
    }

    // 全ユーザーに同一内容を返す（重複有無のみ）参照系 EP。/api/v1/teams/slug-available は
    // permitAll 未登録のため SecurityConfig の anyRequest().authenticated() で
    // 認証必須が強制される。
    @AuthorizedByPathConfig("anyRequest().authenticated()")
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "可視性レベル未満（非メンバー等）でアクセス不可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "チームが存在しない / 論理削除済み")
    public ResponseEntity<ApiResponse<TeamResponse>> getTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: チームの visibility ラダーを ContentVisibilityChecker に委譲して判定する。
        // PUBLIC は未認証含め公開、それ以外は閲覧可能ロール未満（非メンバー等）に 403、不在は 404。
        contentVisibilityChecker.assertCanView(
                ReferenceType.TEAM, id, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(teamService.getTeam(slug));
    }

    @PatchMapping("/{slug}")
    @Operation(summary = "チーム更新（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY でない")
    public ResponseEntity<ApiResponse<TeamResponse>> updateTeam(
            @PathVariable String slug, @Valid @RequestBody UpdateTeamRequest req) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: チームそのものの設定変更は当該チームの ADMIN/DEPUTY 相当のみ許可する
        //（同一クラスの兄弟 EP renameSlug と同じ流儀に揃える）。
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
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
    @Operation(summary = "チーム削除（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY でない")
    public ResponseEntity<Void> deleteTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        Long userId = SecurityUtils.getCurrentUserId();
        // F00 正準: 当該チームの ADMIN/DEPUTY 相当のみ許可（兄弟 EP renameSlug と同じ流儀）。
        accessControlService.checkAdminOrAbove(userId, id, SCOPE_TYPE);
        teamService.deleteTeam(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // メンバー管理
    // ========================================

    @GetMapping("/{slug}/members")
    @Operation(summary = "チームメンバー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "可視性レベル未満（非メンバー等）でアクセス不可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "チームが存在しない / 論理削除済み")
    public ResponseEntity<PagedResponse<MemberResponse>> getMembers(
            @PathVariable String slug, Pageable pageable) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: メンバー一覧はチーム本体と同じ visibility ラダーで保護する。
        // 非メンバーがメンバー情報（userId/displayName/role/joinedAt）を列挙する漏洩を塞ぐ。
        contentVisibilityChecker.assertCanView(
                ReferenceType.TEAM, id, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(teamService.getMembers(id, pageable));
    }

    @PatchMapping("/{slug}/members/{userId}/role")
    @Operation(summary = "メンバーロール変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<Void> changeRole(
            @PathVariable String slug, @PathVariable Long userId,
            @Valid @RequestBody RoleChangeRequest req) {
        Long id = teamService.resolveTeamId(slug);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 束1 権限昇格根治（入口二重防御）: 当該チームの ADMIN/DEPUTY_ADMIN のみロール変更可。
        accessControlService.checkAdminOrAbove(currentUserId, id, SCOPE_TYPE);
        roleService.changeRole(id, SCOPE_TYPE, userId, req, currentUserId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{slug}/members/{userId}")
    @Operation(summary = "メンバー除名")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "除名成功")
    public ResponseEntity<Void> removeMember(
            @PathVariable String slug, @PathVariable Long userId) {
        Long id = teamService.resolveTeamId(slug);
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        // 束1 権限昇格根治（入口二重防御）: 当該チームの ADMIN/DEPUTY_ADMIN のみ除名可。
        accessControlService.checkAdminOrAbove(operatorUserId, id, SCOPE_TYPE);
        roleService.removeMember(id, SCOPE_TYPE, userId, operatorUserId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // アーカイブ
    // ========================================

    @PatchMapping("/{slug}/archive")
    @Operation(summary = "チームアーカイブ（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY でない")
    public ResponseEntity<Void> archiveTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: 当該チームの ADMIN/DEPUTY 相当のみ許可（兄弟 EP renameSlug と同じ流儀）。
        // 組織側の archiveOrganization と異なり、TeamService#archiveTeam は
        // SystemAdminDashboardController と共有していない（本 Controller が唯一の呼び出し元）。
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        teamService.archiveTeam(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{slug}/unarchive")
    @Operation(summary = "チームアーカイブ解除（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ解除成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY でない")
    public ResponseEntity<Void> unarchiveTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: 当該チームの ADMIN/DEPUTY 相当のみ許可（archiveTeam と対称）。
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        teamService.unarchiveTeam(id);
        return ResponseEntity.ok().build();
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    @PostMapping("/{slug}/follow")
    @Operation(summary = "チームサポーター申請（自動承認ON→即時承認、OFF→PENDING申請作成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "申請/承認成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "可視性レベル未満（当該チームを閲覧できない）/ サポーター受け入れが無効")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "チームが存在しない / 論理削除済み")
    public ResponseEntity<ApiResponse<FollowStatusResponse>> followTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        Long userId = SecurityUtils.getCurrentUserId();
        // F00 正準: サポーター自己登録は「当該チームを閲覧できる利用者」に限る。
        // 兄弟 EP getTeam / getMembers と同じ visibility ラダーへ委譲し、独自述語を作らない。
        contentVisibilityChecker.assertCanView(ReferenceType.TEAM, id, userId);
        // 運営者が受け入れを無効化しているチームへの自己登録は拒否する。
        teamService.assertSupporterEnabled(id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supporterService.follow(userId, SCOPE_TYPE, id));
    }

    @SelfScopedEndpoint("SupporterService#unfollow は SecurityUtils.getCurrentUserId() 自身の"
            + "フォロー関係のみを解除し、他人のフォロー関係を指定する余地が無い")
    @DeleteMapping("/{slug}/follow")
    @Operation(summary = "チームサポーター解除・申請取消（APPROVED/PENDING どちらも取消可）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unfollowTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        supporterService.unfollow(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @SelfScopedEndpoint("SupporterService#getFollowStatus は SecurityUtils.getCurrentUserId() と"
            + "対象チームの間の自分自身のフォロー関係のみを返し、他人のフォロー状態を"
            + "指定する余地が無い")
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

    // 認可根治戦役 Wave3-B5: 以下 7EP は「サポーター管理（管理者向け）」区画に属し、
    // 申請者の個人情報（氏名・メッセージ）や承認/却下操作を扱うため checkAdminOrAbove で保護する
    // （非会員/一般メンバーの無防備アクセスを根治。SupporterService 側は既に
    // applicationId ↔ scope の不一致を SUPPORTER_003 として存在秘匿する実装済み・BOLA対策は温存）。

    @GetMapping("/{slug}/supporters")
    @Operation(summary = "承認済みサポーター一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SupporterResponse>> getSupporters(
            @PathVariable String slug, Pageable pageable) {
        Long id = teamService.resolveTeamId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(supporterService.getSupporters(SCOPE_TYPE, id, pageable));
    }

    @GetMapping("/{slug}/supporter-applications")
    @Operation(summary = "サポーター申請一覧（全ステータス）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SupporterApplicationResponse>> getSupporterApplications(
            @PathVariable String slug, Pageable pageable) {
        Long id = teamService.resolveTeamId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(supporterService.getApplications(SCOPE_TYPE, id, pageable));
    }

    @PostMapping("/{slug}/supporter-applications/{applicationId}/approve")
    @Operation(summary = "サポーター申請を個別承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "承認成功")
    public ResponseEntity<Void> approveSupporterApplication(
            @PathVariable String slug, @PathVariable Long applicationId) {
        Long id = teamService.resolveTeamId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        supporterService.approve(applicationId, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slug}/supporter-applications/{applicationId}/reject")
    @Operation(summary = "サポーター申請を個別却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "却下成功")
    public ResponseEntity<Void> rejectSupporterApplication(
            @PathVariable String slug, @PathVariable Long applicationId) {
        Long id = teamService.resolveTeamId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        supporterService.reject(applicationId, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slug}/supporter-applications/bulk-approve")
    @Operation(summary = "サポーター申請を一括承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "一括承認成功")
    public ResponseEntity<Void> bulkApproveSupporterApplications(
            @PathVariable String slug, @Valid @RequestBody BulkApproveRequest request) {
        Long id = teamService.resolveTeamId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        supporterService.bulkApprove(request, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{slug}/supporter-settings")
    @Operation(summary = "サポーター設定取得（自動承認ON/OFF）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SupporterSettingsResponse>> getSupporterSettings(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(ApiResponse.of(supporterService.getSettings(SCOPE_TYPE, id)));
    }

    @PutMapping("/{slug}/supporter-settings")
    @Operation(summary = "サポーター設定更新（自動承認ON/OFF）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SupporterSettingsResponse>> updateSupporterSettings(
            @PathVariable String slug, @RequestBody UpdateSupporterSettingsRequest request) {
        Long id = teamService.resolveTeamId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
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
        return ResponseEntity.ok(ApiResponse.of(
                inviteService.getInviteTokens(id, SCOPE_TYPE, SecurityUtils.getCurrentUserId())));
    }

    @DeleteMapping("/{slug}/invite-tokens/{tokenId}")
    @Operation(summary = "招待トークン失効")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "失効成功")
    public ResponseEntity<Void> revokeInviteToken(
            @PathVariable String slug, @PathVariable Long tokenId) {
        inviteService.revokeInviteToken(tokenId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 権限グループ
    // ========================================

    @GetMapping("/{slug}/permission-groups")
    @Operation(summary = "権限グループ一覧（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY でない")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> getPermissionGroups(
            @PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: 権限グループはチームの権限設計そのものであり、作成/更新/削除
        //（PermissionGroupService 側で checkAdminOrAbove 済み）と同じ粒度で読み取りも保護する。
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
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
        return ResponseEntity.ok(
                permissionGroupService.updatePermissionGroup(groupId, req, SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{slug}/permission-groups/{groupId}")
    @Operation(summary = "権限グループ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePermissionGroup(
            @PathVariable String slug, @PathVariable Long groupId) {
        permissionGroupService.deletePermissionGroup(groupId, SecurityUtils.getCurrentUserId());
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
        return ResponseEntity.ok(ApiResponse.of(
                blockService.getBlocks(id, SCOPE_TYPE, SecurityUtils.getCurrentUserId())));
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
    @Operation(summary = "オーナー譲渡（ADMIN/DEPUTY のみ・最終判定は ADMIN 限定）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "譲渡成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY でない")
    public ResponseEntity<Void> transferOwnership(
            @PathVariable String slug, @RequestParam Long targetUserId) {
        Long id = teamService.resolveTeamId(slug);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 入口二重防御（changeRole / removeMember と同じ流儀）。
        // RoleService#transferOwnership は最終判定として「操作者が当該スコープの ADMIN」を要求するため、
        // 本ガードは判定を緩めない（ADMIN/DEPUTY で入口を絞り、ADMIN 以外は Service 側で弾かれる）。
        accessControlService.checkAdminOrAbove(currentUserId, id, SCOPE_TYPE);
        roleService.transferOwnership(id, SCOPE_TYPE, currentUserId, targetUserId);
        return ResponseEntity.ok().build();
    }

    /**
     * 呼び出し者自身を当該チームから退会させる。
     *
     * <p>認可は {@code RoleService#leaveScope} が担う。同メソッドは
     * {@code (userId, scopeId, scopeType)} の複合キーで {@code user_roles} を引き、
     * 行が無ければ {@code ROLE_001} を送出する＝<b>自分の所属行しか操作できない</b>
     * （認可根治戦役の判定規律「リポジトリ引きの時点で currentUserId と複合キー化」に合致）。
     * 対象ユーザーは常に {@code SecurityUtils.getCurrentUserId()} であり、path から与えられない。</p>
     */
    @AuthorizedInService
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "可視性レベル未満（非メンバー等）でアクセス不可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "チームが存在しない / 論理削除済み")
    public ResponseEntity<ApiResponse<List<TeamOrgSummaryResponse>>> getOrganizations(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: チームの所属関係はチーム本体・メンバー一覧と同じ visibility ラダーで保護する
        //（兄弟 EP getTeam / getMembers と同じ流儀）。
        contentVisibilityChecker.assertCanView(
                ReferenceType.TEAM, id, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(ApiResponse.of(teamService.getOrganizations(id)));
    }

    // ========================================
    // チームの復元（SYSTEM_ADMIN専用）
    // ========================================

    @PatchMapping("/{slug}/restore")
    @Operation(summary = "チーム復元（SYSTEM_ADMINのみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "復元成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "SYSTEM_ADMIN でない（当該チームの ADMIN であっても不可）")
    public ResponseEntity<Void> restoreTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        // 本 EP は SYSTEM_ADMIN 専用（@Operation summary・TeamService#restoreTeam の宣言どおり）。
        // チーム ADMIN に開放すると自チームを任意に復活させられるため checkAdminOrAbove では緩すぎる。
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "可視性レベル未満（非メンバー等）でアクセス不可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "チームが存在しない / 論理削除済み")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getTeamFollowers(
            @PathVariable String slug,
            @RequestParam(defaultValue = "20") int size) {
        Long id = teamService.resolveTeamId(slug);
        // F00 正準: フォロワー一覧も個人（userId/表示名）の列挙であり、
        // メンバー一覧（getMembers）と同じ visibility ラダーで保護する。
        contentVisibilityChecker.assertCanView(
                ReferenceType.TEAM, id, SecurityUtils.getCurrentUserIdOrNull());
        List<FollowResponse> followers = followService.getTeamFollowers(id, size);
        return ResponseEntity.ok(ApiResponse.of(followers));
    }
}
