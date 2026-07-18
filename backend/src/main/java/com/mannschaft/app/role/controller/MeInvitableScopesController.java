package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.role.dto.InvitableScopesResponse;
import com.mannschaft.app.role.service.MembershipInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 招待発行できるスコープ一覧コントローラー（F04.12・{@code GET /api/v1/me/invitable-scopes}）。
 *
 * <p>招待モーダルの選択肢。自分が ADMIN/DEPUTY_ADMIN（{@code INVITE_MEMBERS} 権限）として
 * 招待発行できるチーム/組織の一覧を返す。認可の真実源は BE（設計書 B-6）。</p>
 *
 * <p>設計書: docs/features/F04.12_chat_membership_invite.md §4。</p>
 */
@RestController
@RequestMapping("/api/v1/me/invitable-scopes")
@Tag(name = "招待発行可能スコープ", description = "F04.12 招待発行できるチーム/組織一覧")
@RequiredArgsConstructor
public class MeInvitableScopesController {

    private final MembershipInviteService membershipInviteService;

    /**
     * 自分が招待発行できるスコープ一覧を取得する（管理スコープ 0 件でも 200 空配列）。
     */
    @GetMapping
    @Operation(summary = "招待発行可能スコープ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<InvitableScopesResponse>> getInvitableScopes() {
        InvitableScopesResponse response =
                membershipInviteService.getInvitableScopes(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
