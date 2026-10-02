package com.mannschaft.app.weather.job;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.weather.config.WeatherLocationProperties;
import com.mannschaft.app.weather.service.GeonamesImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GeoNames Postal Codes 月次取り込みスケジューラ（F02.10）。
 *
 * <p>{@code weather.location.geonames.cron} に従い月 1 回実行する。
 * 既定は毎月 5 日 02:00（UTC）。失敗時はリトライせず ERROR ログを残し、
 * 手動で {@code ./gradlew importPostalCodes} 再実行する運用とする。設計書 §10.3。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeonamesImportScheduler {

    private final GeonamesImportService geonamesImportService;
    private final WeatherLocationProperties properties;

    /**
     * 月次取り込みバッチ。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。GeoNames 郵便番号データの取り込みであり、再開後の実行で最新版を取り込み直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "weather-geonames-import-monthly", description = "GeoNames 郵便番号データを毎月 1 回取り込み（デフォルト 5 日 02:00 UTC）")
    @Scheduled(cron = "${weather.location.geonames.cron:0 0 2 5 * ?}")
    // 起動間隔は月次。GeoNames の郵便番号 ZIP を外部からダウンロードして全件取り込むため、回線状況次第で数時間かかりうる。
    // 月次で次回まで 30 日あるので余裕を取り 4 時間を上限とする。
    @SchedulerLock(name = "weatherGeonamesImportMonthly", lockAtLeastFor = "PT1M", lockAtMostFor = "PT4H")
    public void runMonthlyImport() {
        String url = properties.getGeonames().getDownloadUrl();
        try {
            log.info("GeoNames 月次取り込み開始: url={}", url);
            geonamesImportService.importAll(url);
            log.info("GeoNames 月次取り込み正常終了");
        } catch (Exception e) {
            log.error("GeoNames 月次取り込み失敗: url={}, error={}", url, e.getMessage(), e);
        }
    }
}
