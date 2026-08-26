package com.mannschaft.app.payment.escrow.event;

import com.mannschaft.app.payment.escrow.EscrowSourceKind;

import java.util.UUID;

/**
 * F22.1 統一決済 P2-c 第一波: 謝礼の払出（capture+transfer）に失敗したことを表すドメインイベント
 * （設計書 02 §5.3 / PAYMENT_C043）。
 *
 * <p>{@link com.mannschaft.app.payment.escrow.MarketChargeCaptureListener} は最終認証の確定
 * （{@code finalizeBySourceId} の {@code REQUIRES_NEW} トランザクション）が <b>コミットされた後</b>
 * （{@code AFTER_COMMIT}）に capture を起動する。確定（{@code FULL→COMPLETED}）は既に durable であり
 * ロールバックできないため、capture が失敗しても COMPLETED は巻き戻せない。例外を {@code AFTER_COMMIT}
 * コールバックのログに埋もれさせると「観測も後続アクションも不能」になる（握り潰し・対処療法）。これを避けるため、
 * 失敗を本イベントとして発火し ERROR ログを残すことで<b>観測可能・後続でアクション可能</b>な結節点を作る（根治）。
 * 実際の capture 確定は {@code payment_intent.succeeded} webhook（安全網・
 * {@link com.mannschaft.app.payment.escrow.EscrowWebhookService}）でも後追いできる結果整合とする。</p>
 *
 * <p>将来の通知/監視リスナ（札主・運用へ「払出失敗→再試行/口座確認」を通知・PAYMENT_C043）が本イベントを
 * 購読する。通知本実装までは購読者は存在しないが、失敗がログに埋もれず将来の救済導線につながることを保証する。</p>
 *
 * <ul>
 *   <li>{@code escrowId} — 払出に失敗したエスクロー取引 ID。</li>
 *   <li>{@code sourceKind} — 出所種別（{@link EscrowSourceKind#RECRUITMENT} 等）。</li>
 *   <li>{@code sourceId} — 札 ID（escrow の source_id）。</li>
 *   <li>{@code reason} — 失敗事由（例外メッセージ等・ログ/通知用の人間可読テキスト）。</li>
 * </ul>
 */
public record ChargeCaptureFailedEvent(
        UUID escrowId,
        EscrowSourceKind sourceKind,
        Long sourceId,
        String reason) {
}
