package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.escrow.event.ChargeCaptureFailedEvent;
import com.mannschaft.app.recruitment.event.MarketListingFinalizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * F22.1 統一決済 P2-c 第一波: 最終認証（札 FULL→COMPLETED）→ 謝礼の払出（capture+transfer）を起こすリスナ
 * （設計書 02 §5.3）。
 *
 * <p>recruitment ドメインの {@link MarketListingFinalizedEvent} を {@link TransactionalEventListener}
 * （{@link TransactionPhase#AFTER_COMMIT}）で受け、当該札（{@code source_id}）の {@link EscrowStatus#AUTHORIZED}
 * な escrow を {@link ConnectChargeService#capture} で確定する。クロスドメイン FK を作らず ID 連携のみ
 * （README §7・CLAUDE.md 原則1）。</p>
 *
 * <p><b>なぜ AFTER_COMMIT か（TX 整合の根治）:</b> 払出（capture）は不可逆な Stripe 送金であり、
 * これを finalize の確定トランザクション（{@code finalizeBySourceId} の {@code REQUIRES_NEW}=TX-F）の
 * <b>コミット前</b>に実行すると、TX-F が後でロールバックした場合「Stripe は送金確定・DB は AUTHORIZED のまま
 * 巻き戻り・ledger 消失」という不整合が起きる。これを避けるため、本リスナは finalize TX-F が<b>コミットされて
 * から</b>（＝札が COMPLETED として durable になってから）capture を起こす。プレーン {@code @EventListener}
 * （同期・TX-F 内実行）は採用しない。</p>
 *
 * <p><b>AFTER_COMMIT ゆえ finalize はロールバックできない前提:</b> 確定（COMPLETED）は既に durable であり、
 * capture が失敗しても COMPLETED は巻き戻らない。capture は {@link ConnectChargeService#capture} 側で
 * {@code REQUIRES_NEW} の新規トランザクションとして実行され、失敗しても COMPLETED に影響しない。capture 失敗は
 * 握り潰さず ERROR ログ＋{@link ChargeCaptureFailedEvent} で観測可能にしたうえで、{@code payment_intent.succeeded}
 * webhook（安全網・{@link EscrowWebhookService}）による後追い確定に委ねる結果整合とする（02 §5.3）。</p>
 *
 * <p><b>謝礼なし札（{@code payment_enabled=false}）は capture を呼ばない。</b> また、onboarding 未完で
 * {@link EscrowStatus#HELD} に留まる escrow は capture せず（payouts 不能・§5.2）、onboarding 完了で
 * AUTHORIZED へ昇格してから（webhook 経由）capture される。本リスナは AUTHORIZED のみを払出対象とする。</p>
 *
 * <p>1 札に複数の確定応募がある場合（{@code source_participant_id} ごとに 1 escrow）、各 escrow を順に
 * capture する。{@link ConnectChargeService#capture} は CAPTURED 済みを no-op にするため再実行も安全。
 * 1 件の capture 失敗が他の escrow の払出を巻き込まないよう、各 escrow を独立に try/catch する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketChargeCaptureListener {

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ConnectChargeService connectChargeService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 最終認証確定イベントを受けて、当該札の AUTHORIZED escrow を払出（capture）する。
     *
     * <p>finalize TX-F が<b>コミットされた後</b>（{@link TransactionPhase#AFTER_COMMIT}）に実行する。capture は
     * {@link ConnectChargeService#capture}（{@code REQUIRES_NEW}）の新規トランザクションで escrow を CAPTURED に
     * し ledger を追記する。AFTER_COMMIT ゆえ確定（COMPLETED）はロールバックできないため、capture 失敗は
     * 握り潰さず ERROR ログ＋{@link ChargeCaptureFailedEvent} で観測可能にし、webhook の安全網による後追い確定に
     * 委ねる（結果整合・02 §5.3）。</p>
     *
     * @param event 最終認証確定イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onListingFinalized(MarketListingFinalizedEvent event) {
        if (!event.paymentEnabled()) {
            // 謝礼なし札は払出対象外。
            return;
        }

        List<EscrowTransactionEntity> escrows = escrowTransactionRepository
                .findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, event.listingId());
        if (escrows.isEmpty()) {
            log.info("F22.1 払出: 謝礼札だが escrow 未登録（与信未成立 等）: listingId={}", event.listingId());
            return;
        }

        for (EscrowTransactionEntity escrow : escrows) {
            if (escrow.getStatus() == EscrowStatus.AUTHORIZED) {
                // 7日以内（従来 escrow・MANUAL）: 既存与信を capture（払出）する（不変）。
                captureOne(escrow, event.listingId());
            } else if (escrow.getStatus() == EscrowStatus.DEFERRED) {
                // 7日超 fallback（第三陣-b）: 成立時に与信せず DEFERRED で起票した謝礼を、最終認証時に即時払い
                // （AUTOMATIC の destination charge）へフォールバックする。chargeDeferred が PI を作成し
                // PENDING_CONFIRMATION へ遷移＝札主は第二陣の決済確認 EP で clientSecret を受け取り confirm する。
                chargeDeferredOne(escrow, event.listingId());
            } else {
                // PENDING_CONFIRMATION（札主未 confirm・真の与信未確定）/HELD（onboarding 未完で payout 不能）/
                // CANCELLED/CAPTURED 済み等は払出対象外（第一陣 status 意味論の根治）。
                log.info("F22.1 払出スキップ（AUTHORIZED/DEFERRED 以外）: escrowId={}, status={}",
                        escrow.getId(), escrow.getStatus());
            }
        }
    }

    /**
     * 単一 escrow の capture を起こす。失敗は握り潰さず ERROR ログ＋失敗イベントで観測可能にし、webhook の
     * 安全網に後追い確定を委ねる（AFTER_COMMIT ゆえ確定はロールバック不可・02 §5.3）。1 件の失敗が他 escrow の
     * 払出を巻き込まないよう、ここで例外を処理し終える。
     */
    private void captureOne(EscrowTransactionEntity escrow, Long listingId) {
        try {
            connectChargeService.capture(escrow.getId());
        } catch (RuntimeException e) {
            log.error("F22.1 謝礼の払出失敗（救済イベント発火・確定はロールバック不可・webhook 安全網に委ねる）: "
                            + "escrowId={}, listingId={}, reason={}",
                    escrow.getId(), listingId, e.getMessage(), e);
            eventPublisher.publishEvent(new ChargeCaptureFailedEvent(
                    escrow.getId(), EscrowSourceKind.RECRUITMENT, listingId, e.getMessage()));
        }
    }

    /**
     * 単一 DEFERRED escrow の完了時即時払い（chargeDeferred）を起こす（第三陣-b 7日超 fallback・02 §5.1）。
     *
     * <p>{@link ConnectChargeService#chargeDeferred} が AUTOMATIC の Destination PaymentIntent を作成し
     * {@link EscrowStatus#DEFERRED}→{@link EscrowStatus#PENDING_CONFIRMATION} へ遷移させる（札主は第二陣の決済確認 EP で
     * clientSecret を受け取り confirm・succeeded webhook で CAPTURED）。capture と同様、失敗は握り潰さず ERROR ログ＋
     * {@link ChargeCaptureFailedEvent} で観測可能にし、1 件の失敗が他 escrow を巻き込まないよう例外をここで処理し終える。
     * AFTER_COMMIT ゆえ確定（COMPLETED）はロールバック不可。</p>
     */
    private void chargeDeferredOne(EscrowTransactionEntity escrow, Long listingId) {
        try {
            connectChargeService.chargeDeferred(escrow.getId());
        } catch (RuntimeException e) {
            log.error("F22.1 完了時即時払い（7日超 fallback）の起票失敗（救済イベント発火・確定はロールバック不可）: "
                            + "escrowId={}, listingId={}, reason={}",
                    escrow.getId(), listingId, e.getMessage(), e);
            eventPublisher.publishEvent(new ChargeCaptureFailedEvent(
                    escrow.getId(), EscrowSourceKind.RECRUITMENT, listingId, e.getMessage()));
        }
    }
}
