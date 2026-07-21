package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.dto.SlugAvailabilityResponse;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.dto.AncestorsResponse;
import com.mannschaft.app.organization.dto.ChildrenResponse;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrgAllMembersResponse;
import com.mannschaft.app.organization.dto.OrgTeamSummaryResponse;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.OrganizationSummaryResponse;
import com.mannschaft.app.organization.dto.RenameSlugRequest;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
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
 * 組織管理コントローラー。
 * 組織のCRUD・アーカイブ・メンバー管理・招待・権限グループ・ブロックのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "組織管理")
@RequiredArgsConstructor
public class OrganizationController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final OrganizationService organizationService;
    private final RoleService roleService;
    private final AccessControlService accessControlService;
    private final InviteService inviteService;
    private final PermissionGroupService permissionGroupService;
    private final BlockService blockService;
    private final SupporterService supporterService;
    private final ContentVisibilityChecker contentVisibilityChecker;


    // ========================================
    // 組織 CRUD
    // ========================================

    /**
     * 組織を作成する。
     *
     * <p>親組織（{@code parentOrganizationId}）を指定する場合は、
     * <b>指定した親組織が実在すること</b>（不在は {@code ORG_001} → 404）と、
     * <b>操作者がその親組織の ADMIN/DEPUTY 相当であること</b>（不足は {@code COMMON_002} → 403）を要求する。
     * 判定は同クラスの兄弟 EP（{@code renameSlug} / {@code deleteOrganization} 等）と同じ
     * {@code AccessControlService.checkAdminOrAbove} に委譲し、独自 gate を作らない（F00 正準）。</p>
     *
     * <p>親組織の指定が無い（null）場合は従来どおり認可を要求せず、認証済みユーザーであれば作成できる。
     * 大多数の組織作成はこの経路であり、ここに認可を課すと正常系を壊す。</p>
     */
    @PostMapping
    @Operation(summary = "組織作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "指定した親組織の ADMIN/DEPUTY 権限がない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "指定した親組織が存在しない / 論理削除済み")
    public ResponseEntity<ApiResponse<OrganizationResponse>> createOrganization(
            @Valid @RequestBody CreateOrganizationRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long parentOrgId = req.getParentOrganizationId();
        if (parentOrgId != null) {
            // 親組織の実在確認（不在は 404 で秘匿）→ 親組織 ADMIN/DEPUTY 相当の権限確認（403）の順。
            organizationService.assertOrganizationExists(parentOrgId);
            accessControlService.checkAdminOrAbove(userId, parentOrgId, SCOPE_TYPE);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(organizationService.createOrganization(userId, req));
    }

    /**
     * 組織をキーワード検索する。
     *
     * <p>結果は <b>PUBLIC かつ未アーカイブ</b>の組織のみに限定される（可視性フィルタは
     * {@code OrganizationRepository#searchByKeyword} のクエリが担保。論理削除は
     * {@code @SQLRestriction} が除外）。未認証でも呼べる公開検索であるため、
     * チームの {@code searchPublicTeams} と同じく「公開スコープのみ」という最も安全側の流儀に揃える。</p>
     */
    @GetMapping("/search")
    @Operation(summary = "組織検索（PUBLIC かつ未アーカイブの組織のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<OrganizationSummaryResponse>> searchOrganizations(
            @RequestParam(required = false) String keyword, Pageable pageable) {
        return ResponseEntity.ok(organizationService.searchOrganizations(keyword, pageable));
    }

    @GetMapping("/slug-available")
    @Operation(summary = "slug 可用性チェック（作成前のリアルタイム検証・村方式統一）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "判定結果")
    public ResponseEntity<ApiResponse<SlugAvailabilityResponse>> checkSlugAvailability(
            @RequestParam String slug) {
        return ResponseEntity.ok(ApiResponse.of(organizationService.checkSlugAvailability(slug)));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "組織取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "可視性レベル未満（非メンバー等）でアクセス不可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "組織が存在しない / 論理削除済み")
    public ResponseEntity<ApiResponse<OrganizationResponse>> getOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        // F00 正準: 組織の visibility ラダーを ContentVisibilityChecker に委譲して判定する。
        // PUBLIC は未認証含め公開、PRIVATE は非メンバーに 403、不在は 404。
        contentVisibilityChecker.assertCanView(
                ReferenceType.ORGANIZATION, id, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(organizationService.getOrganization(slug));
    }

    @PatchMapping("/{slug}")
    @Operation(summary = "組織更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<OrganizationResponse>> updateOrganization(
            @PathVariable String slug, @Valid @RequestBody UpdateOrganizationRequest req) {
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.ok(organizationService.updateOrganization(id, req));
    }

    @PutMapping("/{slug}/slug")
    @Operation(summary = "組織 slug リネーム（ADMIN/DEPUTY のみ・旧slugは301解決用に履歴予約）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リネーム成功")
    public ResponseEntity<ApiResponse<OrganizationResponse>> renameSlug(
            @PathVariable String slug, @Valid @RequestBody RenameSlugRequest req) {
        Long id = organizationService.resolveOrgId(slug);
        // F00 正準: 当該組織の ADMIN/DEPUTY 相当のみ許可（独自 gate を作らず checkAdminOrAbove に委譲）
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(organizationService.renameSlug(id, req.getNewSlug()));
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "組織削除（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該組織の ADMIN/DEPUTY でない")
    public ResponseEntity<Void> deleteOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        Long userId = SecurityUtils.getCurrentUserId();
        // F00 正準: 当該組織の ADMIN/DEPUTY 相当のみ許可（兄弟 EP renameSlug と同じ流儀）
        accessControlService.checkAdminOrAbove(userId, id, SCOPE_TYPE);
        organizationService.deleteOrganization(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // メンバー管理
    // ========================================

    @GetMapping("/{slug}/members")
    @Operation(summary = "組織メンバー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "可視性レベル未満（非メンバー等）でアクセス不可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "組織が存在しない / 論理削除済み")
    public ResponseEntity<PagedResponse<MemberResponse>> getMembers(
            @PathVariable String slug, Pageable pageable) {
        Long id = organizationService.resolveOrgId(slug);
        // F00 正準: メンバー一覧は組織本体と同じ visibility ラダーで保護する。
        // 非メンバーがメンバー情報を列挙する漏洩を塞ぐ。
        contentVisibilityChecker.assertCanView(
                ReferenceType.ORGANIZATION, id, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(organizationService.getMembers(id, pageable));
    }

    @PatchMapping("/{slug}/members/{userId}/role")
    @Operation(summary = "メンバーロール変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<Void> changeRole(
            @PathVariable String slug, @PathVariable Long userId,
            @Valid @RequestBody RoleChangeRequest req) {
        Long id = organizationService.resolveOrgId(slug);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 束1 権限昇格根治（入口二重防御）: 当該組織の ADMIN/DEPUTY_ADMIN のみロール変更可。
        accessControlService.checkAdminOrAbove(currentUserId, id, SCOPE_TYPE);
        roleService.changeRole(id, SCOPE_TYPE, userId, req, currentUserId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{slug}/members/{userId}")
    @Operation(summary = "メンバー除名")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "除名成功")
    public ResponseEntity<Void> removeMember(
            @PathVariable String slug, @PathVariable Long userId) {
        Long id = organizationService.resolveOrgId(slug);
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        // 束1 権限昇格根治（入口二重防御）: 当該組織の ADMIN/DEPUTY_ADMIN のみ除名可。
        accessControlService.checkAdminOrAbove(operatorUserId, id, SCOPE_TYPE);
        roleService.removeMember(id, SCOPE_TYPE, userId, operatorUserId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // アーカイブ
    // ========================================

    @PatchMapping("/{slug}/archive")
    @Operation(summary = "組織アーカイブ（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該組織の ADMIN/DEPUTY でない")
    public ResponseEntity<Void> archiveOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        // F00 正準: 当該組織の ADMIN/DEPUTY 相当のみ許可（兄弟 EP renameSlug と同じ流儀）。
        // なお SYSTEM_ADMIN による凍結は SystemAdminDashboardController の別 EP
        //（/api/v1/system-admin/** = SecurityConfig で hasRole("SYSTEM_ADMIN")）が担う。
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        organizationService.archiveOrganization(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{slug}/unarchive")
    @Operation(summary = "組織アーカイブ解除（ADMIN/DEPUTY のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ解除成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該組織の ADMIN/DEPUTY でない")
    public ResponseEntity<Void> unarchiveOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        // F00 正準: 当該組織の ADMIN/DEPUTY 相当のみ許可（兄弟 EP renameSlug と同じ流儀）。
        // SYSTEM_ADMIN による凍結解除は SystemAdminDashboardController の別 EP が担う。
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        organizationService.unarchiveOrganization(id);
        return ResponseEntity.ok().build();
    }

    // ========================================
    // フォロー（SUPPORTER）
    // ========================================

    @PostMapping("/{slug}/follow")
    @Operation(summary = "組織サポーター申請（自動承認ON→即時承認、OFF→PENDING申請作成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "申請/承認成功")
    public ResponseEntity<ApiResponse<FollowStatusResponse>> followOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supporterService.follow(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, id));
    }

    @DeleteMapping("/{slug}/follow")
    @Operation(summary = "組織サポーター解除・申請取消（APPROVED/PENDING どちらも取消可）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unfollowOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        supporterService.unfollow(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{slug}/follow/status")
    @Operation(summary = "組織サポーター申請状態取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FollowStatusResponse>> getFollowStatus(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.ok(
                supporterService.getFollowStatus(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, id));
    }

    // ========================================
    // サポーター管理（管理者向け）
    // ========================================

    // 認可根治戦役 Wave3-B1b: 以下 7EP は双子コントローラー TeamController（Wave3-B5 済）と
    // 完全に同型のエンドポイントであり、申請者の個人情報（氏名・メッセージ）や承認/却下操作を扱うため
    // checkAdminOrAbove で保護する（非会員/一般メンバーの無防備アクセスを根治。SupporterService 側は既に
    // applicationId ↔ scope の不一致を SUPPORTER_003 として存在秘匿する実装済み・BOLA対策は温存）。

    @GetMapping("/{slug}/supporters")
    @Operation(summary = "承認済みサポーター一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SupporterResponse>> getSupporters(
            @PathVariable String slug, Pageable pageable) {
        Long id = organizationService.resolveOrgId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(supporterService.getSupporters(SCOPE_TYPE, id, pageable));
    }

    @GetMapping("/{slug}/supporter-applications")
    @Operation(summary = "サポーター申請一覧（全ステータス）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SupporterApplicationResponse>> getSupporterApplications(
            @PathVariable String slug, Pageable pageable) {
        Long id = organizationService.resolveOrgId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(supporterService.getApplications(SCOPE_TYPE, id, pageable));
    }

    @PostMapping("/{slug}/supporter-applications/{applicationId}/approve")
    @Operation(summary = "サポーター申請を個別承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "承認成功")
    public ResponseEntity<Void> approveSupporterApplication(
            @PathVariable String slug, @PathVariable Long applicationId) {
        Long id = organizationService.resolveOrgId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        supporterService.approve(applicationId, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slug}/supporter-applications/{applicationId}/reject")
    @Operation(summary = "サポーター申請を個別却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "却下成功")
    public ResponseEntity<Void> rejectSupporterApplication(
            @PathVariable String slug, @PathVariable Long applicationId) {
        Long id = organizationService.resolveOrgId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        supporterService.reject(applicationId, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slug}/supporter-applications/bulk-approve")
    @Operation(summary = "サポーター申請を一括承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "一括承認成功")
    public ResponseEntity<Void> bulkApproveSupporterApplications(
            @PathVariable String slug, @Valid @RequestBody BulkApproveRequest request) {
        Long id = organizationService.resolveOrgId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        supporterService.bulkApprove(request, SCOPE_TYPE, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{slug}/supporter-settings")
    @Operation(summary = "サポーター設定取得（自動承認ON/OFF）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SupporterSettingsResponse>> getSupporterSettings(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.ok(ApiResponse.of(supporterService.getSettings(SCOPE_TYPE, id)));
    }

    @PutMapping("/{slug}/supporter-settings")
    @Operation(summary = "サポーター設定更新（自動承認ON/OFF）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SupporterSettingsResponse>> updateSupporterSettings(
            @PathVariable String slug, @RequestBody UpdateSupporterSettingsRequest request) {
        Long id = organizationService.resolveOrgId(slug);
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
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inviteService.createInviteToken(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{slug}/invite-tokens")
    @Operation(summary = "招待トークン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<InviteTokenResponse>>> getInviteTokens(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
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
    @Operation(summary = "権限グループ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> getPermissionGroups(
            @PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.ok(ApiResponse.of(
                permissionGroupService.getPermissionGroups(id, SCOPE_TYPE)));
    }

    @PostMapping("/{slug}/permission-groups")
    @Operation(summary = "権限グループ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> createPermissionGroup(
            @PathVariable String slug, @Valid @RequestBody PermissionGroupRequest req) {
        Long id = organizationService.resolveOrgId(slug);
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
        Long id = organizationService.resolveOrgId(slug);
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
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.ok(ApiResponse.of(
                blockService.getBlocks(id, SCOPE_TYPE, SecurityUtils.getCurrentUserId())));
    }

    @PostMapping("/{slug}/blocks")
    @Operation(summary = "ユーザーブロック")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "ブロック成功")
    public ResponseEntity<ApiResponse<BlockResponse>> blockUser(
            @PathVariable String slug, @Valid @RequestBody BlockRequest req) {
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(blockService.blockUser(id, SCOPE_TYPE, req, SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{slug}/blocks/{userId}")
    @Operation(summary = "ユーザーブロック解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "ブロック解除成功")
    public ResponseEntity<Void> unblockUser(
            @PathVariable String slug, @PathVariable Long userId) {
        Long id = organizationService.resolveOrgId(slug);
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
        Long id = organizationService.resolveOrgId(slug);
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
        Long id = organizationService.resolveOrgId(slug);
        roleService.transferOwnership(id, SCOPE_TYPE, SecurityUtils.getCurrentUserId(), targetUserId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{slug}/me")
    @Operation(summary = "組織退会")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "退会成功")
    public ResponseEntity<Void> leaveOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        roleService.leaveScope(SecurityUtils.getCurrentUserId(), id, SCOPE_TYPE);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 階層表示API (F01.2)
    // ========================================

    /**
     * 対象組織の祖先チェーン（root → 直近の親）を返す。
     *
     * <p>未認証でもアクセス可能（対象組織が PUBLIC の場合）。PRIVATE の場合は未認証で 401・
     * 非メンバー＆非子孫メンバーで 403 を返す。各祖先はその visibility / hierarchyVisibility に応じて
     * フル情報・限定情報・プレースホルダ（{@code hidden: true}）のいずれかとして返す。</p>
     */
    @GetMapping("/{slug}/ancestors")
    @Operation(summary = "祖先組織一覧（階層パンくず用）",
            description = "対象組織の上位組織チェーンを root から直近の親の順に返す。" +
                    "max-depth (default 5) を超える場合は途中で打ち切り、meta.truncated=true を立てる。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "未認証（対象組織が PRIVATE の場合のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "PRIVATE 組織で呼び出し者が直接所属でも子孫メンバーでもない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "対象組織が存在しない / 論理削除済み")
    public ResponseEntity<AncestorsResponse> getAncestors(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        Long requesterId = SecurityUtils.getCurrentUserIdOrNull();
        return ResponseEntity.ok(organizationService.getAncestors(id, requesterId));
    }

    /**
     * 対象組織の直近の子組織一覧を返す（深い孫は含まない）。
     */
    @GetMapping("/{slug}/children")
    @Operation(summary = "直近の子組織一覧",
            description = "parent_organization_id = id かつ未削除の子組織のみ返す。" +
                    "PRIVATE 子組織は呼び出し者が直接所属メンバーの場合のみ含める。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認証")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "対象組織が PRIVATE で呼び出し者が直接所属メンバーでない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "対象組織が存在しない / 論理削除済み")
    public ResponseEntity<ChildrenResponse> getChildren(
            @PathVariable String slug,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        Long id = organizationService.resolveOrgId(slug);
        Long requesterId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(organizationService.getChildren(id, requesterId, cursor, size));
    }

    // ========================================
    // 組織所属チーム一覧
    // ========================================

    @GetMapping("/{slug}/teams")
    @Operation(summary = "組織所属チーム一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<OrgTeamSummaryResponse>>> getTeams(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.ok(ApiResponse.of(organizationService.getTeams(id)));
    }

    // ========================================
    // 組織配下全メンバー一覧
    // ========================================

    @GetMapping("/{slug}/members/all")
    @Operation(summary = "組織配下全メンバー一覧（カスケード通知用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<OrgAllMembersResponse>> getAllMembers(
            @PathVariable String slug,
            @RequestParam(defaultValue = "INDIVIDUAL") String scope,
            @RequestParam(defaultValue = "50") int size) {
        Long id = organizationService.resolveOrgId(slug);
        List<OrgAllMembersResponse> members = organizationService.getAllMembers(id, scope);
        var meta = new CursorPagedResponse.CursorMeta(null, false, size);
        return ResponseEntity.ok(CursorPagedResponse.of(members, meta));
    }

    // ========================================
    // 組織の復元（SYSTEM_ADMIN専用）
    // ========================================

    @PatchMapping("/{slug}/restore")
    @Operation(summary = "組織復元（SYSTEM_ADMINのみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "復元成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "SYSTEM_ADMIN でない（当該組織の ADMIN であっても不可）")
    public ResponseEntity<Void> restoreOrganization(@PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        // 本 EP は SYSTEM_ADMIN 専用（Service 側 Javadoc・@Operation の宣言どおり）。
        // 組織 ADMIN に開放すると自組織を任意に復活させられるため checkAdminOrAbove では緩すぎる。
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        organizationService.restoreOrganization(id);
        return ResponseEntity.noContent().build();
    }
}
