package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.event.dto.CreateInviteTokenRequest;
import com.mannschaft.app.event.dto.InviteTokenResponse;
import com.mannschaft.app.event.service.EventInviteTokenService;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * イベント招待トークンコントローラー。ゲスト招待トークンの作成・照会・無効化APIを提供する。
 *
 * <p>認可: 招待トークンはゲスト参加登録を可能にする発行物であり、一覧に列挙されている未使用
 * トークン文字列を非管理者が閲覧できると招待経路を乗っ取れる（登録バイパス）ため、
 * 一覧・作成・無効化のいずれも当該イベントスコープの ADMIN/DEPUTY_ADMIN 専用とする。</p>
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/invite-tokens")
@Tag(name = "イベント招待トークン", description = "F03.8 ゲスト招待トークン管理")
@RequiredArgsConstructor
public class EventInviteTokenController {

    private final EventInviteTokenService inviteTokenService;
    private final EventScopeAccessGuard eventScopeAccessGuard;

    /**
     * 招待トークン一覧を取得する（ADMIN専用）。
     */
    @GetMapping
    @Operation(summary = "招待トークン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<InviteTokenResponse>>> listTokens(
            @PathVariable Long eventId) {
        eventScopeAccessGuard.requireAdminByEventId(SecurityUtils.getCurrentUserId(), eventId);
        List<InviteTokenResponse> response = inviteTokenService.listTokens(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 招待トークンを作成する（ADMIN専用）。
     */
    @PostMapping
    @Operation(summary = "招待トークン作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<InviteTokenResponse>> createToken(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateInviteTokenRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        eventScopeAccessGuard.requireAdminByEventId(userId, eventId);
        InviteTokenResponse response = inviteTokenService.createToken(eventId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 招待トークンを無効化する（ADMIN専用）。
     */
    @PostMapping("/{tokenId}/deactivate")
    @Operation(summary = "招待トークン無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "無効化成功")
    public ResponseEntity<ApiResponse<InviteTokenResponse>> deactivateToken(
            @PathVariable Long eventId,
            @PathVariable Long tokenId) {
        eventScopeAccessGuard.requireAdminByEventId(SecurityUtils.getCurrentUserId(), eventId);
        // 親子BOLA根治: tokenId が eventId に属するかは Service 側で突合し、越境は404秘匿する。
        InviteTokenResponse response = inviteTokenService.deactivateToken(eventId, tokenId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
