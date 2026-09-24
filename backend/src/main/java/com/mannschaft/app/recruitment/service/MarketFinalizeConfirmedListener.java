package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.event.ConfirmableNotificationConfirmedEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F22.1 市: 最終認証の確認応答を受けて札を {@code FULL→COMPLETED} に遷移させるリスナ
 * （02_api_design §6.1）。
 *
 * <p>F04.9 の {@link ConfirmableNotificationConfirmedEvent} を {@code AFTER_COMMIT} + {@code @Async}
 * で受け取り、確認通知の {@code source_type} を判定する。{@code MARKET_FINALIZE} のときだけ札を引いて
 * 確定する。</p>
 *
 * <p><strong>未知 source_type の安全な無視（根治・症状を隠さない）</strong>: 本リスナは
 * {@code MARKET_FINALIZE} 以外をそのまま無視する（{@code IllegalArgumentException} を投げない）。
 * これにより既存の {@code EMERGENCY_CLOSURE}/{@code RECRUITMENT_LISTING} 等の確認応答を壊さない
 * （01_data_model §5 警告・F04.9 既存処理保護）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketFinalizeConfirmedListener {

    private final ConfirmableNotificationRepository confirmableNotificationRepository;
    private final MarketFinalizeService marketFinalizeService;

    /**
     * 確認応答イベントを処理する。{@code source_type='MARKET_FINALIZE'} のときのみ札を確定する。
     *
     * @param event 確認応答イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。募集確定通知の確認受付に伴う締結処理。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(ConfirmableNotificationConfirmedEvent event) {
        ConfirmableNotificationEntity notification =
                confirmableNotificationRepository.findById(event.getConfirmableNotificationId())
                        .orElse(null);
        if (notification == null) {
            log.warn("F22.1 市: 確認通知が不在のため最終認証スキップ: notificationId={}",
                    event.getConfirmableNotificationId());
            return;
        }

        String sourceType = notification.getSourceType();
        // 未知 / 他ドメインの source_type は安全に無視（症状を隠さず情報ログ）。
        if (!MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE.equals(sourceType)) {
            return;
        }

        Long listingId = notification.getSourceId();
        if (listingId == null) {
            log.warn("F22.1 市: MARKET_FINALIZE だが source_id が NULL のため確定不能: notificationId={}",
                    notification.getId());
            return;
        }
        marketFinalizeService.finalizeBySourceId(listingId);
    }
}
