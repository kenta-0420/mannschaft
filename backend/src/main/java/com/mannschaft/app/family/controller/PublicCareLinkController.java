package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.family.dto.CareLinkResponse;
import com.mannschaft.app.family.service.CareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * パブリックケアリンクコントローラー（招待トークン処理）。
 * 招待メールのリンクから遷移する際、ログイン不要で操作可能な API を提供する。F03.12。
 */
@RestController
@RequestMapping("/api/v1/care-links/invitations")
@Tag(name = "ケアリンク（招待）", description = "F03.12 招待トークンを使ったケアリンク承認/拒否")
@RequiredArgsConstructor
public class PublicCareLinkController {

    private final CareLinkService careLinkService;

    /**
     * 招待トークンからケアリンク情報を取得する（確認画面表示用）。
     */
    @GetMapping("/{token}")
    @Operation(summary = "招待トークン情報取得")
    public ResponseEntity<ApiResponse<CareLinkResponse>> getInvitationByToken(
            @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getByInvitationToken(token)));
    }

    /**
     * 招待を承認してケアリンクをアクティブにする。
     */
    @PostMapping("/{token}/accept")
    @Operation(summary = "招待承認")
    public ResponseEntity<ApiResponse<CareLinkResponse>> acceptInvitation(
            @PathVariable String token) {
        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.acceptInvitation(token, currentUserId)));
    }

    /**
     * 招待を拒否する。
     */
    @PostMapping("/{token}/reject")
    @Operation(summary = "招待拒否")
    public ResponseEntity<Void> rejectInvitation(@PathVariable String token) {
        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();
        careLinkService.rejectInvitation(token, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
