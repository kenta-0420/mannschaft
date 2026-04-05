package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.contact.dto.ContactInvitePreviewResponse;
import com.mannschaft.app.contact.dto.SendContactRequestResponse;
import com.mannschaft.app.contact.service.ContactInviteTokenService;
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
 * 招待URL公開エンドポイントコントローラー。
 */
@RestController
@RequestMapping("/api/v1/contact-invite")
@Tag(name = "Contact Invite")
@RequiredArgsConstructor
public class ContactInviteController {

    private final ContactInviteTokenService contactInviteTokenService;

    /**
     * 招待プレビュー（認証不要）。
     * 情報最小化: 発行者の表示名・ハンドル・有効期限のみ返す。
     */
    @GetMapping("/{token}")
    @Operation(summary = "招待プレビュー取得（認証不要）")
    public ResponseEntity<ApiResponse<ContactInvitePreviewResponse>> getPreview(
            @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.of(contactInviteTokenService.getPreview(token)));
    }

    /**
     * 招待URLから連絡先追加（認証必須）。
     */
    @PostMapping("/{token}/accept")
    @Operation(summary = "招待リンクで連絡先追加（認証必須）")
    public ResponseEntity<ApiResponse<SendContactRequestResponse>> acceptInvite(
            @PathVariable String token) {
        Long userId = SecurityUtils.getCurrentUserId();
        SendContactRequestResponse response = contactInviteTokenService.acceptInvite(userId, token);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
