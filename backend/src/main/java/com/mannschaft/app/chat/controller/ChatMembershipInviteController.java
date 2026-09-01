package com.mannschaft.app.chat.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.role.dto.MembershipInviteRequest;
import com.mannschaft.app.role.dto.MembershipInviteResponse;
import com.mannschaft.app.role.service.MembershipInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チャットからチーム/組織への承諾型招待コントローラー（F04.12）。
 *
 * <p>DM 相手を自分が管理するチーム/組織へ名指しで招待する（宛先付きトークン発行＋DM への招待カード投稿）。
 * 承諾は既存 {@code POST /api/v1/invite/{token}/join}、辞退は {@code POST /api/v1/invite/{token}/decline}。</p>
 *
 * <p>設計書: docs/features/F04.12_chat_membership_invite.md §4。</p>
 */
@RestController
@RequestMapping("/api/v1/chat/channels/{channelId}/membership-invite")
@Tag(name = "チャット承諾型招待", description = "F04.12 チャットからチーム/組織への承諾型招待")
@RequiredArgsConstructor
public class ChatMembershipInviteController {

    private final MembershipInviteService membershipInviteService;

    /**
     * DM 相手を指定スコープへ招待する（宛先付きトークン発行＋招待カード投稿）。
     *
     * <p>認可: 認証必須の method-level シグナルを置く。対象スコープの {@code INVITE_MEMBERS} 権限
     * （ADMIN/DEPUTY_ADMIN）・DM 当事者チェック・宛先導出は Service 層で行う（設計書 §4「認可」・B-9・B-6。
     * 実装は /出陣）。scopeId/scopeType はボディ由来のため public 入口の SpEL では参照せず
     * Service で {@code AccessControlService} により解決する（この機能領域の既存定石＝サービス層認可に踏襲）。</p>
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チャットからの指名型メンバー招待は中核の所属管理機能として常時提供する")
    @PostMapping
    @Operation(summary = "承諾型招待の発行",
            description = "DM 相手を指定チーム/組織へ招待。宛先付きトークン発行＋招待カード投稿")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MembershipInviteResponse>> issueMembershipInvite(
            @PathVariable Long channelId,
            @Valid @RequestBody MembershipInviteRequest request) {
        MembershipInviteResponse response = membershipInviteService.issueMembershipInvite(
                channelId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 招待を取消す（発行者 or 対象スコープ ADMIN）。
     *
     * <p>認可: 認証必須。発行者 or 対象スコープ ADMIN の照合は Service 層で行う（実装は /出陣）。</p>
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "発行済み指名型メンバー招待の取消は中核の所属管理機能として常時提供する")
    @DeleteMapping("/{tokenId}")
    @Operation(summary = "承諾型招待の取消",
            description = "発行者または対象スコープ ADMIN が招待を取消す（revoked_at を立てる）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revokeMembershipInvite(
            @PathVariable Long channelId,
            @PathVariable Long tokenId) {
        membershipInviteService.revokeMembershipInvite(channelId, tokenId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
