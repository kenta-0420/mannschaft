package com.mannschaft.app.webhook.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.webhook.service.ApiKeyService;
import com.mannschaft.app.webhook.service.ApiKeyService.ApiKeyIssuedResponse;
import com.mannschaft.app.webhook.service.ApiKeyService.ApiKeyResponse;
import com.mannschaft.app.webhook.service.ApiKeyService.IssueApiKeyRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * APIキー管理コントローラー。
 * 外部連携用APIキーの発行・一覧取得・失効APIを提供する。
 */
@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
@Validated
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /**
     * APIキーを発行する。
     * 認可: ADMIN
     * rawKey は発行時のレスポンスにのみ含まれる（以降は取得不可）。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ApiKeyIssuedResponse>> issueApiKey(
            @Valid @RequestBody IssueApiKeyRequest request) {
        // 認証済みユーザーIDを取得
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<ApiKeyIssuedResponse> response = apiKeyService.issueApiKey(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * スコープに紐づくAPIキー一覧を取得する。
     * 認可: ADMIN / DEPUTY_ADMIN（MANAGE_INTEGRATION権限）
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープID
     */
    @GetMapping
    public ApiResponse<List<ApiKeyResponse>> listApiKeys(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        return apiKeyService.listApiKeys(scopeType, scopeId);
    }

    /**
     * APIキーを失効（論理削除）する。
     * 認可: ADMIN
     *
     * @param id 失効対象のAPIキーID
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable Long id) {
        apiKeyService.revokeApiKey(id);
        return ResponseEntity.noContent().build();
    }
}
