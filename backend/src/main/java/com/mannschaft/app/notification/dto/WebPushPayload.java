package com.mannschaft.app.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Web Push（Service Worker の push イベント）へ送るペイロード。
 *
 * <p>設計書 F04.3「Push ペイロード」の形 {@code {title, body, data:{notification_id, action_url, priority}}} に固定する。
 * WebSocket / REST の {@link NotificationResponse}（camelCase）とは別の契約であり、
 * Service Worker（{@code frontend/public/sw-custom.js}）が読むキー名はここで決まる。
 * 見本: {@code backend/src/test/resources/contracts/web-push-payload.json}。</p>
 *
 * @param title 通知の題（100 コードポイントまで）
 * @param body  通知の本文（200 コードポイントまで。null は空文字）
 * @param data  タップ時に使う付帯情報
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"title", "body", "data"})
public record WebPushPayload(String title, String body, Data data) {

    /**
     * 付帯情報。キー名は Service Worker と共有するため snake_case に固定する。
     *
     * @param notificationId 通知 ID（バルク INSERT 由来で ID が無いときは null）
     * @param actionUrl      遷移先（"/" 始まりの相対パスか FE 公開オリジンの絶対 URL。それ以外は null）
     * @param priority       優先度（LOW / NORMAL / HIGH / URGENT）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"notification_id", "action_url", "priority"})
    public record Data(
            @JsonProperty("notification_id") Long notificationId,
            @JsonProperty("action_url") String actionUrl,
            @JsonProperty("priority") String priority) {
    }
}
