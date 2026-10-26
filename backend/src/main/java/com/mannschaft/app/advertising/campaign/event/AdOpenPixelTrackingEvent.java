package com.mannschaft.app.advertising.campaign.event;

import java.time.Instant;
import java.util.UUID;

/**
 * F09.17 Phase 11-b — メール開封ピクセルへのアクセスを表すドメインイベント。
 *
 * <p>{@code GET /api/v1/ads/pixels/open?token=...} が JWT を検証できた場合、
 * 本イベントが発行される。第三陣 η の Listener
 * （{@code ad_*_deliveries.opened_at} 更新および集計バッチ）が消費する設計。</p>
 *
 * <p>本イベントには {@code user_id} を含めない。
 * 受信者と delivery_id の対応関係は DB 側で保持されているため、
 * 開封イベント経路だけからは受信者個人を特定できない構造とする
 * （PII 保護・設計書 §6）。</p>
 *
 * @param deliveryId  配信 ID（{@code ad_*_deliveries.id}）
 * @param channelType "ANNOUNCEMENT" / "EMAIL" / "PUSH" / "BANNER"
 * @param openedAt    開封ピクセルへアクセスがあった時刻
 */
public record AdOpenPixelTrackingEvent(UUID deliveryId, String channelType, Instant openedAt) {
}
