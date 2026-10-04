package com.mannschaft.app.weather.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link UserAnonymizedEvent} を受けて {@code user_weather_locations} を物理削除するリスナー（F02.10）。
 *
 * <p>地理情報は個人特定可能性のあるデータのため、退会時は匿名化ではなく物理削除する。
 * 設計書 §7.8 / §13.8。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherLocationCleanupListener {

    private final AccountPurgeCompletionService completionService;

    private final UserWeatherLocationRepository userWeatherLocationRepository;

    /**
     * ユーザー退会匿名化イベントを受け取り、地点キャッシュを物理削除する。
     *
     * @param event 退会匿名化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会匿名化イベントを購読し天気の地点情報（居住地に相当する PII）を消す。止めると残留し、イベントは再生されない")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            int deleted = userWeatherLocationRepository.deleteByUserId(userId);
            log.info("ユーザー退会: user_weather_locations 物理削除完了: userId={}, deletedRows={}",
                    userId, deleted);
        } catch (Exception e) {
            log.warn("ユーザー退会: user_weather_locations 物理削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "完全削除済み利用者の個人設定を消去する。停止すると設定が残留し、消去イベントは再生されない")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        purgeSettings(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, "weather");
            }
        });
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        purgeSettings(userId);
        return true;
    }

    /** 同じ所有domain内の全削除を一つのTXで実行し、途中失敗を伝播させる。 */
    private void purgeSettings(Long userId) {
        userWeatherLocationRepository.deleteByUserId(userId);
    }
}
