package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * F12.3 退会時Stripeキャンセルハンドラー。
 * 退会申請イベントを受け取り、Stripeサブスクリプションをキャンセルする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalStripeHandler {

    /**
     * 退会申請イベントを受け取り、Stripeサブスクリプションをキャンセルする。
     *
     * @param event 退会申請イベント
     */
    @EventListener
    public void onWithdrawalRequested(WithdrawalRequestedEvent event) {
        try {
            log.info("退会申請を受信: userId={}, email={}", event.getUserId(), event.getEmail());

            // Stripe連携はTODO: 未実装
            log.warn("Stripeサブスクリプションキャンセル: 未実装 userId={}", event.getUserId());
        } catch (Exception e) {
            log.error("Stripeキャンセル処理失敗: userId={}", event.getUserId(), e);
            // サイレント: 例外を外部に伝播させない
        }
    }
}
