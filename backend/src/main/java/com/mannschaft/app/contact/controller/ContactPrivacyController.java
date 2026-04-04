package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.contact.dto.ContactPrivacyRequest;
import com.mannschaft.app.contact.dto.ContactPrivacyResponse;
import com.mannschaft.app.contact.service.ContactPrivacyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 連絡先プライバシー設定コントローラー。
 */
@RestController
@RequestMapping("/api/v1/users/me/contact-privacy")
@Tag(name = "Contact Privacy")
@RequiredArgsConstructor
public class ContactPrivacyController {

    private final ContactPrivacyService contactPrivacyService;

    @GetMapping
    @Operation(summary = "プライバシー設定取得")
    public ResponseEntity<ApiResponse<ContactPrivacyResponse>> getPrivacySettings() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactPrivacyService.getPrivacySettings(userId)));
    }

    @PutMapping
    @Operation(summary = "プライバシー設定更新")
    public ResponseEntity<ApiResponse<ContactPrivacyResponse>> updatePrivacySettings(
            @Valid @RequestBody ContactPrivacyRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactPrivacyService.updatePrivacySettings(userId, req)));
    }
}
