package com.mannschaft.app.weather.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.weather.exception.WeatherLocationDeriveException;
import com.mannschaft.app.weather.service.WeatherLocationDeriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@link UserPostalCodeUpdatedEvent} を受けて座標を再導出するリスナー（F02.10）。
 *
 * <p>処理は {@code AFTER_COMMIT} フェーズ + {@code @Async("event-pool")} で非同期実行。
 * 失敗時は WARN ログのみ出力する（次回ダッシュボード GET 時に同期再導出されるため
 * 機能停止には繋がらない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherLocationEventListener {

    private final WeatherLocationDeriver weatherLocationDeriver;

    /**
     * 郵便番号更新イベントを受信して座標を再導出する。
     *
     * @param event 郵便番号更新イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。郵便番号更新に伴う天気地点の再解決。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePostalCodeUpdated(UserPostalCodeUpdatedEvent event) {
        Long userId = event.getUserId();
        try {
            weatherLocationDeriver.deriveAndPersist(userId);
            log.debug("郵便番号更新イベント処理完了: userId={}", userId);
        } catch (WeatherLocationDeriveException e) {
            log.warn("郵便番号更新イベント処理: 地点導出失敗: userId={}, errorCode={}",
                    userId, e.getErrorCode());
        } catch (Exception e) {
            log.warn("郵便番号更新イベント処理: 想定外エラー: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
