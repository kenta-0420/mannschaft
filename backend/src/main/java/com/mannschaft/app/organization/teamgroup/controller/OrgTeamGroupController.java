package com.mannschaft.app.organization.teamgroup.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.organization.teamgroup.dto.CreateOrgTeamGroupRequest;
import com.mannschaft.app.organization.teamgroup.dto.OrgTeamGroupListResponse;
import com.mannschaft.app.organization.teamgroup.dto.OrgTeamGroupResponse;
import com.mannschaft.app.organization.teamgroup.dto.ReorderOrgTeamGroupsRequest;
import com.mannschaft.app.organization.teamgroup.dto.UpdateOrgTeamGroupRequest;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * チームグループ管理コントローラー（F01.2.1 §10.7）。
 *
 * <p><b>認可（§3.1）</b>: 一覧は組織の MEMBER 以上と SYSTEM_ADMIN（監査用の閲覧のみ）。作成・変更・削除・並び替えは
 * <b>組織 ADMIN のみ</b>（DEPUTY_ADMIN・MEMBER・SYSTEM_ADMIN・他組織の ADMIN は 403）。
 * 認可の順序は 認証（401）→ 組織の存在（404）→ 権限（403）→ 入力検証（400）→ グループの存在・状態（404/409/422）。
 * 認可判定は各エンドポイント本体で {@link AccessControlService} を直接呼ぶ（認可番人が直接呼び出しを検査するため）。</p>
 *
 * <p>他組織・削除済み・不在のグループ ID は区別せず 404 {@code ORG_064}（存在オラクルを作らない）。
 * グループ機能 off の組織では全エンドポイントが 409 {@code ORG_067}。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "チームグループ")
@RequiredArgsConstructor
public class OrgTeamGroupController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final OrgTeamGroupService orgTeamGroupService;
    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;

    /**
     * チームグループ一覧（並び順・所属チーム数・未分類数）。組織 MEMBER 以上と SYSTEM_ADMIN が閲覧できる。
     */
    @GetMapping("/{slug}/team-groups")
    @Operation(summary = "チームグループ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織のメンバーでも SYSTEM_ADMIN でもない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORG_067: グループ機能が無効")
    public ResponseEntity<OrgTeamGroupListResponse> list(@PathVariable String slug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isSystemAdmin(userId)
                && !accessControlService.hasRoleOrAbove(userId, orgId, SCOPE_TYPE, "MEMBER")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(orgTeamGroupService.list(orgId));
    }

    /**
     * チームグループを作成する（組織 ADMIN のみ）。並び順の末尾に入る。
     */
    @PostMapping("/{slug}/team-groups")
    @Operation(summary = "チームグループ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORG_065: 同名 / ORG_067: 機能無効")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "ORG_066: 上限（100件）")
    public ResponseEntity<ApiResponse<OrgTeamGroupResponse>> create(
            @PathVariable String slug,
            @Valid @RequestBody CreateOrgTeamGroupRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(orgTeamGroupService.create(orgId, userId, req.getName(), req.getDescription())));
    }

    /**
     * チームグループの名前・説明を変更する（組織 ADMIN のみ。部分更新）。
     */
    @PatchMapping("/{slug}/team-groups/{groupId}")
    @Operation(summary = "チームグループ変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORG_064: グループなし（他組織・削除済みも同じ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORG_065: 同名 / ORG_067: 機能無効")
    public ResponseEntity<ApiResponse<OrgTeamGroupResponse>> update(
            @PathVariable String slug,
            @PathVariable UUID groupId,
            @Valid @RequestBody UpdateOrgTeamGroupRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(ApiResponse.of(
                orgTeamGroupService.update(orgId, groupId, userId, req.getName(), req.getDescription())));
    }

    /**
     * チームグループを削除する（組織 ADMIN のみ。論理削除。所属チームはコミット後に未分類へ戻る）。
     */
    @DeleteMapping("/{slug}/team-groups/{groupId}")
    @Operation(summary = "チームグループ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORG_064: グループなし（他組織・削除済みも同じ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORG_067: 機能無効")
    public ResponseEntity<Void> delete(
            @PathVariable String slug,
            @PathVariable UUID groupId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        orgTeamGroupService.delete(orgId, groupId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * チームグループを並び替える（組織 ADMIN のみ）。生存グループ全件の ID を新しい順で送る。
     */
    @PutMapping("/{slug}/team-groups/order")
    @Operation(summary = "チームグループ並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "並び替え成功（新しい一覧）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORG_068: ID 集合が一致しない / ORG_067: 機能無効")
    public ResponseEntity<OrgTeamGroupListResponse> reorder(
            @PathVariable String slug,
            @Valid @RequestBody ReorderOrgTeamGroupsRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(orgTeamGroupService.reorder(orgId, userId, req.getGroupIds()));
    }
}
