package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.event.dto.CreateInviteTokenRequest;
import com.mannschaft.app.event.dto.InviteTokenResponse;
import com.mannschaft.app.event.service.EventInviteTokenService;
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
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/invite-tokens")
@Tag(name = "イベント招待トークン", description = "F03.8 ゲスト招待トークン管理")
@RequiredArgsConstructor
public class EventInviteTokenController {

    private final EventInviteTokenService inviteTokenService;


    /**
     * 招待トークン一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "招待トークン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<InviteTokenResponse>>> listTokens(
            @PathVariable Long eventId) {
        List<InviteTokenResponse> response = inviteTokenService.listTokens(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 招待トークンを作成する。
     */
    @PostMapping
    @Operation(summary = "招待トークン作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<InviteTokenResponse>> createToken(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateInviteTokenRequest request) {
        InviteTokenResponse response = inviteTokenService.createToken(
                eventId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 招待トークンを無効化する。
     */
    @PostMapping("/{tokenId}/deactivate")
    @Operation(summary = "招待トークン無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "無効化成功")
    public ResponseEntity<ApiResponse<InviteTokenResponse>> deactivateToken(
            @PathVariable Long eventId,
            @PathVariable Long tokenId) {
        InviteTokenResponse response = inviteTokenService.deactivateToken(tokenId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
