package com.mannschaft.app.weather.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
}
