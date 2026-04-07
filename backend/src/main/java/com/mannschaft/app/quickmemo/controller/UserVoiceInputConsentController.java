package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.quickmemo.dto.VoiceInputConsentRequest;
import com.mannschaft.app.quickmemo.dto.VoiceInputConsentResponse;
import com.mannschaft.app.quickmemo.service.UserVoiceInputConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 音声入力同意 コントローラー。
 * GDPR 対応のため同意取得・確認・撤回を担当する。
 */
@RestController
@RequestMapping("/api/v1/me/voice-input-consents")
@Tag(name = "音声入力同意", description = "F02.5 GDPR音声入力同意管理")
@RequiredArgsConstructor
public class UserVoiceInputConsentController {

    private final UserVoiceInputConsentService consentService;

    @GetMapping("/active")
    @Operation(summary = "有効な同意確認", description = "指定バージョンの有効な同意が存在するか確認する")
    public ResponseEntity<ApiResponse<VoiceInputConsentResponse>> getActiveConsent(
            @RequestParam(defaultValue = "1") int version) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(consentService.getActiveConsent(userId, version)));
    }

    @PostMapping
    @Operation(summary = "音声入力同意登録",
               description = "同意ポリシーに同意する。同一バージョンへの同意は冪等（重複登録しない）")
    public ResponseEntity<ApiResponse<VoiceInputConsentResponse>> grantConsent(
            @Valid @RequestBody VoiceInputConsentRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SecurityUtils.getCurrentUserId();
        String ip = getClientIp(httpRequest);
        String ua = httpRequest.getHeader("User-Agent");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(consentService.grantConsent(userId, request, ip, ua)));
    }

    @DeleteMapping("/active")
    @Operation(summary = "音声入力同意撤回", description = "有効な同意を撤回する（revoked_at を現在時刻に設定）")
    public ResponseEntity<Void> revokeConsent() {
        Long userId = SecurityUtils.getCurrentUserId();
        consentService.revokeConsent(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * X-Forwarded-For → Remote-Addr の順でクライアント IP を取得する。
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
