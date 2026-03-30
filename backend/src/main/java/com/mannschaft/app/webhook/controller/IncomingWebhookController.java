package com.mannschaft.app.webhook.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.webhook.service.IncomingWebhookService;
import com.mannschaft.app.webhook.service.IncomingWebhookService.CreateIncomingWebhookRequest;
import com.mannschaft.app.webhook.service.IncomingWebhookService.IncomingWebhookTokenResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Incoming Webhook管理コントローラー。
 * 外部サービスからのWebhook受信トークン管理と受信処理APIを提供する。
 * 受信エンドポイント（/incoming/{token}）は認証不要の公開エンドポイント。
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IncomingWebhookController {

    private final IncomingWebhookService incomingWebhookService;

    /**
     * Incoming Webhookトークンを作成する。
     * 認可: ADMIN
     * 外部サービスからのWebhook受信に使用するトークンを発行する。
     */
    @PostMapping("/api/webhooks/incoming")
    public ResponseEntity<ApiResponse<IncomingWebhookTokenResponse>> createToken(
            @Valid @RequestBody CreateIncomingWebhookRequest request) {
        // 認証済みユーザーIDを取得
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<IncomingWebhookTokenResponse> response =
                incomingWebhookService.createToken(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * スコープに紐づくIncoming Webhookトークン一覧を取得する。
     * 認可: ADMIN / DEPUTY_ADMIN（MANAGE_INTEGRATION権限）
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープID
     */
    @GetMapping("/api/webhooks/incoming")
    public ApiResponse<List<IncomingWebhookTokenResponse>> listTokens(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        return incomingWebhookService.listTokens(scopeType, scopeId);
    }

    /**
     * Incoming Webhookトークンを失効（論理削除）する。
     * 認可: ADMIN
     *
     * @param id 失効対象のトークンID
     */
    @DeleteMapping("/api/webhooks/incoming/{id}")
    public ResponseEntity<Void> revokeToken(
            @PathVariable Long id) {
        incomingWebhookService.revokeToken(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Incoming Webhookリクエストを受信して処理する。
     * 【公開エンドポイント・認証不要】
     * 外部サービス（Stripe、GitHub等）からのWebhookペイロードを受け付ける。
     * トークン検証はサービス層で実施する。
     *
     * @param token     URLパスに含まれるWebhookトークン
     * @param payload   受信ペイロード（JSONオブジェクト）
     * @param eventType イベント種別（任意。Stripe等ではXヘッダーではなくクエリで指定される場合がある）
     */
    @PostMapping("/incoming/{token}")
    public ApiResponse<Void> processIncoming(
            @PathVariable String token,
            @RequestBody Map<String, Object> payload,
            @RequestParam(required = false) String eventType) {
        incomingWebhookService.processIncoming(token, eventType, payload);
        return ApiResponse.of(null);
    }
}
