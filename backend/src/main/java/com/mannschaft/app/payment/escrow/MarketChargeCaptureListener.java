package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.recruitment.event.MarketListingFinalizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F22.1 統一決済 P2-c 第一波: 最終認証（札 FULL→COMPLETED）→ 謝礼の払出（capture+transfer）を起こすリスナ
 * （設計書 02 §5.3）。
 *
 * <p>recruitment ドメインの {@link MarketListingFinalizedEvent} を<b>同期</b>（{@link EventListener}・
 * 確定トランザクションと同一・札行 {@code PESSIMISTIC_WRITE} ロック直下）で受け、当該札（{@code source_id}）の
 * {@link EscrowStatus#AUTHORIZED} な escrow を {@link ConnectChargeService#capture} で確定する。札行ロックの
 * 直列化を引き継ぐことで二重払出を防ぐ（02 §5.3）。クロスドメイン FK を作らず ID 連携のみ（README §7・
 * CLAUDE.md 原則1）。</p>
 *
 * <p><b>謝礼なし札（{@code payment_enabled=false}）は capture を呼ばない。</b> また、onboarding 未完で
 * {@link EscrowStatus#HELD} に留まる escrow は capture せず（payouts 不能・§5.2）、onboarding 完了で
 * AUTHORIZED へ昇格してから（webhook 経由）capture される。本リスナは AUTHORIZED のみを払出対象とし、
 * それ以外の状態は {@link ConnectChargeService#capture} 側で適切に扱われる/スキップする。</p>
 *
 * <p>1 札に複数の確定応募がある場合（{@code source_participant_id} ごとに 1 escrow）、各 escrow を順に
 * capture する。{@link ConnectChargeService#capture} は CAPTURED 済みを no-op にするため再実行も安全。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketChargeCaptureListener {

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ConnectChargeService connectChargeService;

    /**
     * 最終認証確定イベントを受けて、当該札の AUTHORIZED escrow を払出（capture）する。
     *
     * <p>同期・同一トランザクション内で実行する（札行ロック直下・02 §5.3）。capture 失敗は症状を隠さず
     * 例外を伝播させ、確定トランザクションをロールバックさせる（払出不能なのに COMPLETED で確定する不整合を防ぐ）。</p>
     *
     * @param event 最終認証確定イベント
     */
    @EventListener
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
            if (escrow.getStatus() != EscrowStatus.AUTHORIZED) {
                // HELD（onboarding 未完で payout 不能）/CANCELLED/CAPTURED 済み等は capture 対象外。
                log.info("F22.1 払出スキップ（AUTHORIZED 以外）: escrowId={}, status={}",
                        escrow.getId(), escrow.getStatus());
                continue;
            }
            connectChargeService.capture(escrow.getId());
        }
    }
}
