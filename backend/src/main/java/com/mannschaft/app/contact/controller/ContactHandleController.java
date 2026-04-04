package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.contact.dto.ContactHandleResponse;
import com.mannschaft.app.contact.dto.HandleCheckResponse;
import com.mannschaft.app.contact.dto.HandleSearchResponse;
import com.mannschaft.app.contact.dto.UpdateHandleRequest;
import com.mannschaft.app.contact.service.ContactHandleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @ハンドル管理コントローラー。
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Contact Handle")
@RequiredArgsConstructor
public class ContactHandleController {

    private final ContactHandleService contactHandleService;

    @GetMapping("/me/contact-handle")
    @Operation(summary = "自分の@ハンドル情報取得")
    public ResponseEntity<ApiResponse<ContactHandleResponse>> getMyHandle() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.getMyHandle(userId)));
    }

    @PutMapping("/me/contact-handle")
    @Operation(summary = "@ハンドル設定・変更")
    public ResponseEntity<ApiResponse<ContactHandleResponse>> updateHandle(
            @Valid @RequestBody UpdateHandleRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.updateHandle(userId, req)));
    }

    @GetMapping("/contact-handle-check")
    @Operation(summary = "@ハンドル重複確認")
    public ResponseEntity<ApiResponse<HandleCheckResponse>> checkHandle(
            @RequestParam String handle) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.checkHandleAvailability(userId, handle)));
    }

    @GetMapping("/contact-handle/{handle}")
    @Operation(summary = "@ハンドルでユーザー検索")
    public ResponseEntity<ApiResponse<HandleSearchResponse>> searchByHandle(
            @PathVariable String handle) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.searchByHandle(userId, handle)));
    }
}
