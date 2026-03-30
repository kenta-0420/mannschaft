package com.mannschaft.app.webhook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.webhook.DeliveryStatus;
import com.mannschaft.app.webhook.WebhookErrorCode;
import com.mannschaft.app.webhook.entity.WebhookDeliveryLogEntity;
import com.mannschaft.app.webhook.entity.WebhookEndpointEntity;
import com.mannschaft.app.webhook.entity.WebhookEventSubscriptionEntity;
import com.mannschaft.app.webhook.repository.WebhookDeliveryLogRepository;
import com.mannschaft.app.webhook.repository.WebhookEndpointRepository;
import com.mannschaft.app.webhook.repository.WebhookEventSubscriptionRepository;
import com.mannschaft.app.webhook.util.HmacSignatureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Webhook配信サービス。
 * Outgoing Webhookのイベント配信・配信ログ記録・リトライを担う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService {

    /** レスポンスボディの保存上限文字数 */
    private static final int MAX_RESPONSE_BODY_LENGTH = 1000;

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookEventSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryLogRepository deliveryLogRepository;
    private final HmacSignatureUtil hmacSignatureUtil;
    private final ObjectMapper objectMapper;

    // ========================================
    // DTOクラス定義
    // ========================================

    /**
     * 配信ログレスポンスDTO。
     */
    public record DeliveryLogResponse(
            Long id,
            Long endpointId,
            String eventType,
            Integer statusCode,
            DeliveryStatus status,
            Long durationMs,
            LocalDateTime sentAt
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * イベントを購読しているエンドポイントに非同期で配信する。
     * スコープ(scopeType/scopeId)でフィルタリングした後、各エンドポイントへ非同期送信する。
     *
     * @param eventType イベント種別
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param payload   送信ペイロード（ObjectMapperでJSON化）
     */
    public void deliverEvent(String eventType, String scopeType, Long scopeId, Object payload) {
        // 指定イベント種別をサブスクライブしているサブスクリプションを取得
        List<WebhookEventSubscriptionEntity> subscriptions =
                subscriptionRepository.findByEventType(eventType);

        if (subscriptions.isEmpty()) {
            log.debug("イベント配信対象なし: eventType={}", eventType);
            return;
        }

        // ペイロードをJSON文字列に変換
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("ペイロードのJSON変換に失敗しました: eventType={}", eventType, e);
            return;
        }

        // スコープでフィルタして非同期配信
        for (WebhookEventSubscriptionEntity subscription : subscriptions) {
            endpointRepository.findById(subscription.getEndpointId())
                    .filter(ep -> ep.getScopeType().equals(scopeType) && ep.getScopeId().equals(scopeId))
                    .filter(WebhookEndpointEntity::isActive)
                    .ifPresent(ep -> deliverAsync(ep, eventType, payloadJson));
        }
    }

    /**
     * 配信ログ一覧を取得する。
     *
     * @param endpointId エンドポイントID
     * @return 配信ログ一覧
     */
    public List<DeliveryLogResponse> listDeliveryLogs(Long endpointId) {
        List<WebhookDeliveryLogEntity> logs =
                deliveryLogRepository.findByEndpointIdOrderByCreatedAtDesc(endpointId);
        return logs.stream()
                .map(this::toDeliveryLogResponse)
                .collect(Collectors.toList());
    }

    /**
     * 指定配信ログIDの内容を使って再送する。
     *
     * @param deliveryLogId 再送対象の配信ログID
     */
    @Transactional
    public void retryDelivery(Long deliveryLogId) {
        WebhookDeliveryLogEntity deliveryLog = deliveryLogRepository.findById(deliveryLogId)
                .orElseThrow(() -> new BusinessException(WebhookErrorCode.WEBHOOK_001));

        WebhookEndpointEntity endpoint = endpointRepository.findById(deliveryLog.getEndpointId())
                .orElseThrow(() -> new BusinessException(WebhookErrorCode.WEBHOOK_001));

        log.info("Webhook再送開始: deliveryLogId={}, endpointId={}", deliveryLogId, deliveryLog.getEndpointId());
        deliverAsync(endpoint, deliveryLog.getEventType(), deliveryLog.getRequestPayload());
    }

    // ========================================
    // 内部メソッド: 非同期配信
    // ========================================

    /**
     * エンドポイントへの配信を非同期で実行する（event-poolスレッドプール使用）。
     */
    @Async("event-pool")
    public void deliverAsync(WebhookEndpointEntity endpoint, String eventType, String payloadJson) {
        deliverToEndpoint(endpoint, eventType, payloadJson);
    }

    /**
     * エンドポイントにHTTP POSTでWebhookを配信し、配信ログを記録する。
     * 4xx/5xx/タイムアウト時は status=FAILED で記録し、例外は飲み込む。
     *
     * @param endpoint    配信先エンドポイント
     * @param eventType   イベント種別
     * @param payloadJson JSON文字列ペイロード
     */
    @Transactional
    public void deliverToEndpoint(WebhookEndpointEntity endpoint, String eventType, String payloadJson) {
        // イベントIDを生成（冪等性保証用）
        String eventId = UUID.randomUUID().toString();

        // HMAC-SHA256署名を生成
        String signature = hmacSignatureUtil.sign(payloadJson, endpoint.getSigningSecret());

        // 配信ログを PENDING 状態で先行保存
        WebhookDeliveryLogEntity deliveryLog = WebhookDeliveryLogEntity.builder()
                .endpointId(endpoint.getId())
                .eventType(eventType)
                .eventId(eventId)
                .requestPayload(payloadJson)
                .deliveryStatus(DeliveryStatus.RETRYING) // 送信中扱い
                .build();
        deliveryLog = deliveryLogRepository.save(deliveryLog);

        long startMs = System.currentTimeMillis();
        Integer responseStatus = null;
        String responseBody = null;
        DeliveryStatus deliveryStatus;
        String errorMessage = null;

        try {
            // java.net.http.HttpClient でPOST送信
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(endpoint.getTimeoutMs()))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getUrl()))
                    .timeout(Duration.ofMillis(endpoint.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("X-Mannschaft-Signature", signature)
                    .header("X-Mannschaft-Event-Id", eventId)
                    .header("X-Mannschaft-Event-Type", eventType)
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            responseStatus = response.statusCode();
            String rawBody = response.body();
            // レスポンスボディは先頭1000文字まで保存
            responseBody = rawBody != null && rawBody.length() > MAX_RESPONSE_BODY_LENGTH
                    ? rawBody.substring(0, MAX_RESPONSE_BODY_LENGTH)
                    : rawBody;

            // 2xx は成功、それ以外は失敗
            if (responseStatus >= 200 && responseStatus < 300) {
                deliveryStatus = DeliveryStatus.SUCCESS;
                endpoint.resetFailureCount();
                log.info("Webhook配信成功: endpointId={}, eventType={}, status={}", endpoint.getId(), eventType, responseStatus);
            } else {
                deliveryStatus = DeliveryStatus.FAILED;
                endpoint.recordFailure();
                errorMessage = "HTTPステータスエラー: " + responseStatus;
                log.warn("Webhook配信失敗（HTTPエラー）: endpointId={}, eventType={}, status={}", endpoint.getId(), eventType, responseStatus);
            }

        } catch (Exception e) {
            // タイムアウト・接続エラー等
            deliveryStatus = DeliveryStatus.FAILED;
            endpoint.recordFailure();
            errorMessage = e.getMessage() != null
                    ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500))
                    : "不明なエラー";
            log.warn("Webhook配信例外: endpointId={}, eventType={}, error={}", endpoint.getId(), eventType, errorMessage, e);
        }

        long durationMs = System.currentTimeMillis() - startMs;

        // 配信ログを更新（結果を反映）
        WebhookDeliveryLogEntity finalLog = deliveryLog.toBuilder()
                .responseStatus(responseStatus)
                .deliveryStatus(deliveryStatus)
                .errorMessage(errorMessage)
                .build();
        deliveryLogRepository.save(finalLog);
        endpointRepository.save(endpoint);

        log.debug("Webhook配信ログ記録: endpointId={}, eventId={}, status={}, durationMs={}",
                endpoint.getId(), eventId, deliveryStatus, durationMs);
    }

    // ========================================
    // 内部メソッド: DTO変換
    // ========================================

    /**
     * 配信ログエンティティをレスポンスDTOに変換する。
     */
    private DeliveryLogResponse toDeliveryLogResponse(WebhookDeliveryLogEntity e) {
        return new DeliveryLogResponse(
                e.getId(),
                e.getEndpointId(),
                e.getEventType(),
                e.getResponseStatus(),
                e.getDeliveryStatus(),
                null, // durationMsはエンティティに未定義のためnull
                e.getCreatedAt()
        );
    }
}
