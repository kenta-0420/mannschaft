package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.event.MarketListingReachedFullEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * F22.1 市: 申込で札が {@code FULL} に到達したことを受けて、札主へ最終認証の確認通知を送るリスナ
 * （02_api_design §6.1。CMP-260930-1932）。
 *
 * <p><b>TX境界（backend/.claudecode.md 原則5）</b>: 申込の業務TXがコミットされた後に
 * {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取り、確認通知は本リスナーが開く
 * <b>通知専用TX</b>（{@code REQUIRES_NEW} の {@link TransactionTemplate}）で作る。業務TXは既にコミット済みで
 * 参加しないため、通知が失敗（受信者上限の {@code SEND_FAILED}・受信者行の失敗など）しても申込は確定済みの
 * まま、通知TXだけが巻き戻り、失敗は ERROR ログ（listingId 入り）で可視化する。業務TXがロールバックした場合は
 * 発火しない。</p>
 *
 * <p><b>札の行ロック下での直列化（Codex 検分 P2）</b>: 通知TXの最初の操作として
 * {@link MarketFinalizeService#planFinalizeConfirmation(Long)} が札行を {@code PESSIMISTIC_WRITE} で取り、
 * {@code FULL} であること・未確認（ACTIVE）の最終認証通知が無いことを確認し、<b>同じTXのまま</b>
 * {@code sendFromSource} で通知を作ってコミットする（ロックはコミットで外れる）。これにより
 * ①確認の後に参加者キャンセルで {@code OPEN} に戻った札へ無効な通知を送ること、
 * ② FULL→OPEN→再FULL の2イベントが並行処理されて双方が ACTIVE 不在を見て重複通知を作ること、
 * の両方を塞ぐ（{@code MARKET_FINALIZE} は once_per_source_key 一意制約の対象外で、DB では防げない）。
 * 送信そのものは本リスナーから {@link ConfirmableNotificationService} を直接呼ぶ（{@code @Transactional} な
 * 業務サービスの中で同期送信しない）。</p>
 *
 * <p><b>課金</b>: 同期 {@code sendFromSource} は自動・システム通知の経路であり F09.13 のカウント対象外。</p>
 */
@Slf4j
@Component
public class MarketFinalizeConfirmationListener {

    private final MarketFinalizeService marketFinalizeService;
    private final ConfirmableNotificationService confirmableNotificationService;
    /**
     * 札の行ロック・状態再確認・通知作成を1つに束ねる通知専用TX（業務TXとは独立に開く）。
     *
     * <p><b>ドメイン越境の理由（原則5）</b>: このTXは recruitment の札行ロックと notification の確認通知作成を
     * 1つにまとめる。直列化の単位が「札」で、状態確認と作成の間に札が動かないことを保証するには、作成のコミット
     * までロックを握り続けるしかないため（別TXに分けると Codex 検分 P2 の競合窓が再び開く）。業務（申込）TXは
     * 含まず、ここでの失敗は通知TXだけを巻き戻す。</p>
     */
    private final TransactionTemplate notificationTx;

    public MarketFinalizeConfirmationListener(MarketFinalizeService marketFinalizeService,
                                              ConfirmableNotificationService confirmableNotificationService,
                                              PlatformTransactionManager transactionManager) {
        this.marketFinalizeService = marketFinalizeService;
        this.confirmableNotificationService = confirmableNotificationService;
        this.notificationTx = new TransactionTemplate(transactionManager);
        this.notificationTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
            // 札行ロック → 状態・既存 ACTIVE 通知の再確認 → 通知作成 を同一の通知TXで直列化する。
            // 失敗（例外）は通知TXごと巻き戻り、下の catch で ERROR ログにする（申込は確定済みで巻き戻らない）。
            Integer sent = notificationTx.execute(status -> {
                Optional<MarketFinalizeService.FinalizeConfirmationPlan> plan =
                        marketFinalizeService.planFinalizeConfirmation(listingId);
                if (plan.isEmpty()) {
                    return null;
                }
                MarketFinalizeService.FinalizeConfirmationPlan p = plan.get();
                confirmableNotificationService.sendFromSource(
                        MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE,
                        p.listingId(),
                        p.scopeType(),
                        p.scopeId(),
                        p.title(),
                        p.body(),
                        marketFinalizeService.finalizeConfirmationPriority(),
                        null,
                        p.actionUrl(),
                        p.createdByUserId(),
                        p.recipientUserIds());
                return p.recipientUserIds().size();
            });
            if (sent != null) {
                log.info("F22.1 市: 最終認証の確認通知を送信: listingId={}, recipients={}", listingId, sent);
            }
        } catch (RuntimeException e) {
            log.error("F22.1 市: 最終認証の確認通知の配送失敗（申込は確定済み）: listingId={}", listingId, e);
        }
    }
}
