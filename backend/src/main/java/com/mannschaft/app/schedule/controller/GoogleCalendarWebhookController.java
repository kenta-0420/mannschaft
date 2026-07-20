package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google Calendar Webhook 受信エンドポイント（Phase 4 — 双方向同期）。
 *
 * <p>Google Calendar Push Notification（Events.watch API）から送信される
 * Webhook 通知を受信し、{@link GoogleCalendarWebhookService} に処理を委譲する。</p>
 *
 * <p>このエンドポイントは認証不要（Google からの外部コールバック）であり、
 * トークン検証は {@link GoogleCalendarWebhookService} 内で行う。</p>
 *
 * <p><b>認可根拠（{@link AuthorizedInService} クラス付与・全 1 EP が該当）</b>:
 * {@link #receiveWebhook} は {@code GoogleCalendarWebhookService.java:102-103} の
 * {@code MessageDigest.isEqual(channelToken.getBytes(UTF_8), ...)} で
 * {@code X-Goog-Channel-Token} を DB 保持のチャンネルトークンと<b>定数時間比較</b>し、
 * 不一致なら {@code GCAL_009} → 403 で中断する（チャンネル ID 不在は {@code GCAL_008} → 404）。
 * 認可根治戦役 Wave5 監査済。</p>
 *
 * <p>設計書: docs/features/F02.12_google_calendar_sync/phase4.md — P4-5 コントローラー設計</p>
 */
@Slf4j
@AuthorizedInService
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Google Calendar Webhook", description = "Google Calendar 双方向同期 Webhook 受信")
public class GoogleCalendarWebhookController {

    private final GoogleCalendarWebhookService webhookService;

    /**
     * Google Calendar Push Notification を受信する。
     *
     * <p>Google から送信される通知ヘッダー:</p>
     * <ul>
     *   <li>{@code X-Goog-Channel-ID} — チャンネル ID（DB の {@code channel_id} と照合）</li>
     *   <li>{@code X-Goog-Channel-Token} — チャンネルトークン（DB と定数時間比較）</li>
     *   <li>{@code X-Goog-Resource-State} — リソース状態（{@code sync} / {@code exists} / {@code not_exists}）</li>
     *   <li>{@code X-Goog-Resource-ID} — リソース ID（チャンネル停止時に使用）</li>
     * </ul>
     *
     * <p>AC 対応:</p>
     * <ul>
     *   <li>AC-5: トークン不一致 → 403（{@link GoogleCalendarWebhookService} で処理）</li>
     *   <li>AC-6: チャンネル ID 不在 → 404（{@link GoogleCalendarWebhookService} で処理）</li>
     *   <li>AC-7: {@code Resource-State=sync} → 200（ノーオペレーション）</li>
     * </ul>
     *
     * @param channelId     Google Webhook チャンネル ID
     * @param channelToken  Google Webhook チャンネルトークン（HMAC/ランダム hex）
     * @param resourceState Google リソース状態（sync / exists / not_exists）
     * @param resourceId    Google リソース ID
     * @return 200 OK（処理成功）
     */
    @PostMapping("/google-calendar")
    @Operation(summary = "Google Calendar Webhook 受信", description = "Google Calendar Push Notification を受信し双方向同期を実行する")
    public ResponseEntity<Void> receiveWebhook(
            @RequestHeader("X-Goog-Channel-ID") String channelId,
            @RequestHeader("X-Goog-Channel-Token") String channelToken,
            @RequestHeader("X-Goog-Resource-State") String resourceState,
            @RequestHeader("X-Goog-Resource-ID") String resourceId) {

        log.debug("Google Calendar Webhook 受信: channelId={}, resourceState={}", channelId, resourceState);

        // AC-7: Resource-State=sync は Google の初回チャンネル確認通知（ノーオペレーション）
        if ("sync".equals(resourceState)) {
            log.debug("Google Calendar Webhook: sync 通知を受信（ノーオペレーション）: channelId={}", channelId);
            return ResponseEntity.ok().build();
        }

        // AC-1〜AC-4, AC-5, AC-6, AC-8〜AC-11: チャンネル検証 → イベント取り込み
        // 内部で BusinessException（GCAL_008 → 404, GCAL_009 → 403）を throw
        webhookService.receiveWebhookNotification(channelId, resourceState, channelToken, resourceId);

        return ResponseEntity.ok().build();
    }
}
