package com.mannschaft.app.webhook.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.webhook.service.WebhookEndpointService;
import com.mannschaft.app.webhook.service.WebhookEndpointService.CreateWebhookEndpointRequest;
import com.mannschaft.app.webhook.service.WebhookEndpointService.UpdateWebhookEndpointRequest;
import com.mannschaft.app.webhook.service.WebhookEndpointService.WebhookEndpointCreatedResponse;
import com.mannschaft.app.webhook.service.WebhookEndpointService.WebhookEndpointResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Webhookエンドポイント管理コントローラー。
 * Outgoing Webhook のエンドポイント登録・照会・更新・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/webhooks/endpoints")
@RequiredArgsConstructor
@Validated
public class WebhookEndpointController {

    private final WebhookEndpointService webhookEndpointService;

    /**
     * Webhookエンドポイントを作成する。
     * 認可: ADMIN
     * signingSecret は作成時のレスポンスにのみ含まれる（以降は取得不可）。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<WebhookEndpointCreatedResponse>> createEndpoint(
            @Valid @RequestBody CreateWebhookEndpointRequest request) {
        // 認証済みユーザーIDを取得
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<WebhookEndpointCreatedResponse> response =
                webhookEndpointService.createEndpoint(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * スコープに紐づくWebhookエンドポイント一覧を取得する。
     * 認可: ADMIN / DEPUTY_ADMIN（MANAGE_INTEGRATION権限）
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープID
     */
    @GetMapping
    public ApiResponse<List<WebhookEndpointResponse>> listEndpoints(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        return webhookEndpointService.listEndpoints(scopeType, scopeId);
    }

    /**
     * Webhookエンドポイントを取得する。
     * 認可: ADMIN / DEPUTY_ADMIN（MANAGE_INTEGRATION権限）
     *
     * @param id エンドポイントID
     */
    @GetMapping("/{id}")
    public ApiResponse<WebhookEndpointResponse> getEndpoint(
            @PathVariable Long id) {
        return webhookEndpointService.getEndpoint(id);
    }

    /**
     * Webhookエンドポイントを更新する。
     * 認可: ADMIN
     *
     * @param id      エンドポイントID
     * @param request 更新リクエスト（nullフィールドは変更なし）
     */
    @PutMapping("/{id}")
    public ApiResponse<WebhookEndpointResponse> updateEndpoint(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWebhookEndpointRequest request) {
        return webhookEndpointService.updateEndpoint(id, request);
    }

    /**
     * Webhookエンドポイントを論理削除する。
     * 認可: ADMIN
     *
     * @param id エンドポイントID
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable Long id) {
        webhookEndpointService.deleteEndpoint(id);
        return ResponseEntity.noContent().build();
    }
}
