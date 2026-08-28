package com.mannschaft.app.weather.job;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.weather.config.WeatherLocationProperties;
import com.mannschaft.app.weather.entity.WeatherLocationBootstrapJobEntity;
import com.mannschaft.app.weather.exception.WeatherLocationDeriveException;
import com.mannschaft.app.weather.repository.GeonamesMetadataRepository;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import com.mannschaft.app.weather.repository.WeatherLocationBootstrapJobRepository;
import com.mannschaft.app.weather.service.WeatherLocationDeriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 既存ユーザー全員に対して初回座標導出を行うワンタイムジョブ（F02.10）。
 *
 * <p>処理フロー（設計書 §8.3）:
 * <ol>
 *   <li>{@code weather_location_bootstrap_jobs(id=1).completed_at} が非 null ならスキップ</li>
 *   <li>{@code geonames_metadata} 未取り込みなら処理保留（後続バッチ完了後の再起動を待つ）</li>
 *   <li>未削除ユーザーをチャンクサイズ単位でページング取得し、各ユーザーで
 *       {@link WeatherLocationDeriver#deriveAndPersist(Long)} を呼出</li>
 *   <li>失敗（郵便番号未登録 / マスタ未ヒット）は WARN ログでスキップ継続</li>
 *   <li>完了後 {@code completed_at} / カウンタを記録</li>
 * </ol>
 *
 * <p>{@link ApplicationReadyEvent} 受信後 + {@link Async} で非同期実行するため、
 * 起動 SLA に影響しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherLocationBootstrapJob {

    /** シングルトン行の固定 ID。 */
    private static final short SINGLETON_ID = 1;

    private final WeatherLocationBootstrapJobRepository bootstrapJobRepository;
    private final GeonamesMetadataRepository geonamesMetadataRepository;
    private final UserRepository userRepository;
    private final UserWeatherLocationRepository userWeatherLocationRepository;
    private final WeatherLocationDeriver weatherLocationDeriver;
    private final WeatherLocationProperties properties;

    /**
     * アプリケーション起動完了時に実行されるブートストラップ。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。起動時の天気地点マスタ初期化であり、次回起動時に再度実行される。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            run();
        } catch (Exception e) {
            log.error("WeatherLocationBootstrapJob: 想定外エラーで中断: error={}", e.getMessage(), e);
        }
    }

    /**
     * 本体処理。テスト容易性のため public で切り出す。
     *
     * <p>{@code @Transactional} は付けない（チャンクループ内で個別に
     * {@link WeatherLocationDeriver#deriveAndPersist(Long)} が
     * {@code REQUIRED} で各 1 ユーザートランザクションを張る）。</p>
     */
    public void run() {
        // 1) 冪等フラグ確認
        WeatherLocationBootstrapJobEntity jobEntity = bootstrapJobRepository
                .findById(SINGLETON_ID)
                .orElse(null);
        if (jobEntity != null && jobEntity.getCompletedAt() != null) {
            log.debug("WeatherLocationBootstrapJob: 既に完了済みのためスキップ");
            return;
        }

        // 2) GeoNames 取り込み確認
        boolean geonamesReady = geonamesMetadataRepository.findById(SINGLETON_ID).isPresent();
        if (!geonamesReady) {
            log.info("WeatherLocationBootstrapJob: GeoNames 未取り込みのためブートストラップを保留");
            return;
        }

        // 3) ページングループ
        int chunkSize = properties.getBootstrap().getChunkSize();
        log.info("WeatherLocationBootstrapJob: 開始 chunkSize={}", chunkSize);

        long processed = 0;
        long skipped = 0;
        int pageIndex = 0;
        while (true) {
            Pageable pageable = PageRequest.of(pageIndex, chunkSize);
            // UserRepository#findAll は @SQLRestriction("deleted_at IS NULL") により未削除のみ返す
            Page<UserEntity> page = userRepository.findAll(pageable);
            if (page.isEmpty()) {
                break;
            }

            for (UserEntity user : page.getContent()) {
                Long userId = user.getId();
                // 既に地点が存在するならスキップ
                if (userWeatherLocationRepository.findByUserIdAndLabel(userId, "home").isPresent()) {
                    skipped++;
                    continue;
                }
                try {
                    weatherLocationDeriver.deriveAndPersist(userId);
                    processed++;
                } catch (WeatherLocationDeriveException e) {
                    skipped++;
                    log.warn("WeatherLocationBootstrapJob: 地点導出スキップ: userId={}, errorCode={}",
                            userId, e.getErrorCode());
                } catch (Exception e) {
                    skipped++;
                    log.warn("WeatherLocationBootstrapJob: 想定外スキップ: userId={}, error={}",
                            userId, e.getMessage());
                }
            }

            if (!page.hasNext()) {
                break;
            }
            pageIndex++;
        }

        // 4) 完了記録
        markCompleted(processed, skipped);
        log.info("WeatherLocationBootstrapJob: 完了 processed={}, skipped={}", processed, skipped);
    }

    @Transactional
    public void markCompleted(long processed, long skipped) {
        WeatherLocationBootstrapJobEntity entity = bootstrapJobRepository
                .findById(SINGLETON_ID)
                .orElseGet(() -> WeatherLocationBootstrapJobEntity.builder()
                        .id(SINGLETON_ID)
                        .build());
        entity.setCompletedAt(LocalDateTime.now());
        entity.setProcessedUserCount(processed);
        entity.setSkippedUserCount(skipped);
        bootstrapJobRepository.save(entity);
    }
}
