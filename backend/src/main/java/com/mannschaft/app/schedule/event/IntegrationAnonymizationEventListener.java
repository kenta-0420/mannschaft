package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 退会匿名化イベントに応答して schedule ドメインの外部サービス連携データを削除するリスナー。
 *
 * <p>処理内容:
 * <ul>
 *   <li>Google Calendar 連携情報削除（OAuth トークンを含むため GDPR 必須）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntegrationAnonymizationEventListener {

    private final UserGoogleCalendarConnectionRepository userGoogleCalendarConnectionRepository;

    /**
     * ユーザー退会匿名化イベントを受け取り、外部サービス連携データを削除する。
     * Google Calendar の OAuth トークンは個人情報であり GDPR 対象のため、退会時の削除が必須。
     *
     * @param event 退会匿名化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者の外部カレンダー連携情報とトークンが残存し、退会後も外部同期が継続しうる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            userGoogleCalendarConnectionRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: Google Calendar連携削除完了: userId={}", userId);

            log.info("ユーザー退会: scheduleドメイン匿名化完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: scheduleドメイン匿名化失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
