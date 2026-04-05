package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.contact.dto.ContactInviteTokenResponse;
import com.mannschaft.app.contact.dto.CreateInviteTokenBody;
import com.mannschaft.app.contact.service.ContactInviteTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 連絡先招待トークンコントローラー。
 */
@RestController
@RequestMapping("/api/v1/contact-invite-tokens")
@Tag(name = "Contact Invite Tokens")
@RequiredArgsConstructor
public class ContactInviteTokenController {

    private final ContactInviteTokenService contactInviteTokenService;

    @PostMapping
    @Operation(summary = "招待トークンを発行する")
    public ResponseEntity<ApiResponse<ContactInviteTokenResponse>> createToken(
            @Valid @RequestBody CreateInviteTokenBody req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ContactInviteTokenResponse response = contactInviteTokenService.createToken(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "発行済みトークン一覧")
    public ResponseEntity<ApiResponse<List<ContactInviteTokenResponse>>> listTokens() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactInviteTokenService.listTokens(userId)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "招待トークンを無効化する")
    public ResponseEntity<Void> revokeToken(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactInviteTokenService.revokeToken(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{token}/qr")
    @Operation(summary = "QRコード画像を取得する（PNG）")
    public ResponseEntity<byte[]> getQrCode(
            @PathVariable String token,
            @RequestParam(defaultValue = "300") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        // sizeのクランプ（100〜1000）
        int safeSize = Math.max(100, Math.min(size, 1000));
        byte[] png = contactInviteTokenService.generateQrCode(userId, token, safeSize);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(png);
    }
}
