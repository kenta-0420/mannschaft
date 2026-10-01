package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.event.MarketListingReachedFullEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * F22.1 市: 申込で札が {@code FULL} に到達したことを受けて、札主へ最終認証の確認通知を送るリスナ
 * （02_api_design §6.1。CMP-260930-1932）。
 *
 * <p><b>TX境界（backend/.claudecode.md 原則5）</b>: 申込の業務TXがコミットされた後に
 * {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取り、確認通知は {@code sendFromSource} 自身の
 * 別TXで作る。通知が失敗（受信者上限の {@code SEND_FAILED}・受信者行の失敗など）しても申込は確定済みのまま
 * で、失敗は ERROR ログ（listingId 入り）で可視化する。業務TXがロールバックした場合は発火しない。</p>
 *
 * <p><b>最新状態の再取得</b>: 送信内容は {@link MarketFinalizeService#planFinalizeConfirmation(Long)} が札を
 * 読み直して決める。コミット時点で {@code FULL} でない札・未確認の最終認証通知が既にある札には送らない。
 * 送信そのものは本リスナーから {@link ConfirmableNotificationService} を直接呼ぶ（{@code @Transactional} な
 * 業務サービスの中で同期送信しない）。</p>
 *
 * <p><b>課金</b>: 同期 {@code sendFromSource} は自動・システム通知の経路であり F09.13 のカウント対象外。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketFinalizeConfirmationListener {

    private final MarketFinalizeService marketFinalizeService;
    private final ConfirmableNotificationService confirmableNotificationService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確定済みの申込で定員に達した札の最終認証を札主へ知らせるため常時実行する。"
                    + "イベントは再生されず、落とすと札主は確定操作の機会を知らされない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReachedFull(MarketListingReachedFullEvent event) {
        Long listingId = event.listingId();
        if (listingId == null) {
            log.warn("F22.1 市: 最終認証通知をスキップ（listingId 欠落）");
            return;
        }
        try {
            Optional<MarketFinalizeService.FinalizeConfirmationPlan> plan =
                    marketFinalizeService.planFinalizeConfirmation(listingId);
            if (plan.isEmpty()) {
                return;
            }
            MarketFinalizeService.FinalizeConfirmationPlan p = plan.get();
            confirmableNotificationService.sendFromSource(
                    MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE,
                    p.listingId(),
                    p.scopeType(),
                    p.scopeId(),
                    p.title(),
                    p.body(),
                    p.priority(),
                    null,
                    p.actionUrl(),
                    p.createdByUserId(),
                    p.recipientUserIds());
            log.info("F22.1 市: 最終認証の確認通知を送信: listingId={}, recipients={}",
                    listingId, p.recipientUserIds().size());
        } catch (RuntimeException e) {
            log.error("F22.1 市: 最終認証の確認通知の配送失敗（申込は確定済み）: listingId={}", listingId, e);
        }
    }
}
