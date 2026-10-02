package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.advertising.campaign.entity.UserAdDeliveryCounter;
import com.mannschaft.app.advertising.campaign.repository.UserAdDeliveryCounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * F09.17 フリークエンシーキャップの Valkey ホットカウンタを RDB に転記する日次バッチ。
 *
 * <p>毎日 02:00 (Asia/Tokyo) に実行し、{@code mannschaft:ad:freq:*} キーを SCAN
 * （KEYS は本番影響大のため禁止）で走査し、各エントリを
 * {@code user_ad_delivery_counters} テーブルに upsert する。</p>
 *
 * <p>Valkey 側のキーは TTL（次週月曜 00:00）で自動失効するため、本バッチでは明示削除しない。
 * 保持期間 90 日のクリーンアップは Phase 11-a で実装済の
 * {@link UserAdDeliveryCounterRepository#deleteOlderThan(LocalDate)} を別バッチで利用する想定。</p>
 *
 * @see AdFrequencyCapService Valkey 原子的カウンタの本体
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdFrequencyCapFlushBatch {

    /** SCAN のチャンクサイズ。Valkey のレイテンシと一回あたりの転送量のバランス。 */
    private static final long SCAN_COUNT = 500L;

    private final StringRedisTemplate redisTemplate;
    private final UserAdDeliveryCounterRepository repository;

    /**
     * 日次 02:00 (Asia/Tokyo) 実行のフリーキャップ flush バッチ。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "Valkey 側のカウンタは週次 TTL で自動失効するため、止めると配信回数が RDB へ転記されないまま消え、フリークエンシーキャップの実績が復元不能に欠損する")
    @BatchEndpoint(name = "advertising-frequency-cap-flush-daily", description = "Valkey の広告フリークエンシーキャップカウンタを毎日 02:00 RDB へ転記する")
    @Scheduled(cron = "${mannschaft.ad.frequency-cap.flush-cron:0 0 2 * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "adFrequencyCapFlush", lockAtMostFor = "15m", lockAtLeastFor = "1m")
    public void execute() {
        runFlush();
    }

    /**
     * バッチ本体（テスト・手動起動からも呼ばれる）。
     *
     * @return 転記したエントリ数
     */
    @Transactional
    public int runFlush() {
        long startMs = System.currentTimeMillis();
        log.info("AdFrequencyCapFlushBatch 開始");

        int scanned = 0;
        int upserted = 0;
        int skipped = 0;

        ScanOptions options = ScanOptions.scanOptions()
                .match(AdFrequencyCapService.KEY_PREFIX_TOTAL + "*")
                .count(SCAN_COUNT)
                .build();

        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                scanned++;
                ParsedKey parsed = parseKey(key);
                if (parsed == null) {
                    skipped++;
                    continue;
                }
                String raw = redisTemplate.opsForValue().get(key);
                if (raw == null) {
                    // TTL 失効直後の競合可能性 — 次回拾うので skip
                    skipped++;
                    continue;
                }
                int count;
                try {
                    count = Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    log.warn("Valkey 値が数値ではない key={} value={}", key, raw);
                    skipped++;
                    continue;
                }
                if (count <= 0) {
                    skipped++;
                    continue;
                }
                upsert(parsed.userId(), parsed.weekStart(), count);
                upserted++;
            }
        } catch (Exception ex) {
            log.error("AdFrequencyCapFlushBatch SCAN 中に例外", ex);
            throw ex;
        }

        log.info("AdFrequencyCapFlushBatch 完了 所要={}ms scanned={} upserted={} skipped={}",
                System.currentTimeMillis() - startMs, scanned, upserted, skipped);
        return upserted;
    }

    /**
     * 1 件分の upsert（INSERT ON DUPLICATE KEY UPDATE 相当）を Hibernate で実現する。
     *
     * <p>UNIQUE 制約は {@code (user_id, week_start_date)} 想定（Phase 11-a の DDL に合わせる）。
     * 既存行が見つかれば delivery_count を最新値に置き換える。これは Valkey 側が常に
     * 「累計値」を保持しているため、上書きで整合性が保たれる。</p>
     */
    private void upsert(Long userId, LocalDate weekStart, int count) {
        Optional<UserAdDeliveryCounter> existing =
                repository.findByUserIdAndWeekStartDate(userId, weekStart);
        UserAdDeliveryCounter entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.setDeliveryCount(count);
            entity.setUpdatedAt(LocalDateTime.now());
        } else {
            entity = UserAdDeliveryCounter.builder()
                    .userId(userId)
                    .weekStartDate(weekStart)
                    .deliveryCount(count)
                    .build();
        }
        repository.save(entity);
    }

    /**
     * Valkey キー {@code mannschaft:ad:freq:{userId}:{weekStartDate}} を分解する。
     *
     * @return パース成功時はレコード、失敗時 null
     */
    static ParsedKey parseKey(String key) {
        if (key == null || !key.startsWith(AdFrequencyCapService.KEY_PREFIX_TOTAL)) {
            return null;
        }
        String suffix = key.substring(AdFrequencyCapService.KEY_PREFIX_TOTAL.length());
        // 期待形式: "{userId}:{yyyy-MM-dd}"
        int sep = suffix.indexOf(':');
        if (sep <= 0 || sep == suffix.length() - 1) {
            return null;
        }
        String userIdStr = suffix.substring(0, sep);
        String dateStr = suffix.substring(sep + 1);
        try {
            Long userId = Long.parseLong(userIdStr);
            LocalDate date = LocalDate.parse(dateStr);
            return new ParsedKey(userId, date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析済みキーレコード。 */
    record ParsedKey(Long userId, LocalDate weekStart) { }
}
