package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.gamification.PeriodType;
import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import com.mannschaft.app.gamification.entity.RankingSnapshotEntity;
import com.mannschaft.app.gamification.repository.GamificationConfigRepository;
import com.mannschaft.app.gamification.repository.PointTransactionQueryRepository;
import com.mannschaft.app.gamification.repository.RankingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ゲーミフィケーション・ランキングスナップショットバッチサービス。
 * 毎朝3:30 (Asia/Tokyo) にランキングスナップショットを生成する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GamificationRankingBatchService {

    private static final int CHUNK_SIZE = 500;

    private final GamificationConfigRepository gamificationConfigRepository;
    private final PointTransactionQueryRepository pointTransactionQueryRepository;
    private final RankingSnapshotRepository rankingSnapshotRepository;

    /**
     * ランキングスナップショットを生成するバッチ処理。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>isEnabled=true かつ isRankingEnabled=true の GamificationConfig をチャンクで取得</li>
     *   <li>各スコープについて WEEKLY/MONTHLY/YEARLY の期間ラベルを計算</li>
     *   <li>PointTransactionQueryRepository.findTopUsersByPeriod() でポイント集計</li>
     *   <li>RankingSnapshotEntityをdelete and insertで更新</li>
     *   <li>処理件数をログ出力</li>
     * </ol>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_GAMIFICATION_ENABLED",
            reason = "スナップショットは delete and insert で毎回作り直す派生値であり、止めても前回分が残るだけで元のポイント台帳は壊れない")
    @BatchEndpoint(name = "gamification-ranking-snapshot-daily", description = "ランキングスナップショットを毎日 03:30 に生成する")
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "gamification_ranking_snapshot", lockAtMostFor = "PT30M")
    @Transactional
    public void runRankingSnapshot() {
        log.info("ランキングスナップショットバッチ開始");

        LocalDate today = LocalDate.now();

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger scopeCount = new AtomicInteger(0);

        // isEnabled=true かつ isRankingEnabled=true の設定をDB側でフィルタしてチャンク処理
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<GamificationConfigEntity> page;
        do {
            page = gamificationConfigRepository.findByIsEnabledTrueAndIsRankingEnabledTrue(pageable);

            for (GamificationConfigEntity config : page.getContent()) {
                String scopeType = config.getScopeType();
                Long scopeId = config.getScopeId();
                int displayCount = config.getRankingDisplayCount() > 0 ? config.getRankingDisplayCount() : 10;

                for (PeriodType periodType : PeriodType.values()) {
                    String periodLabel = buildPeriodLabel(periodType, today);
                    LocalDate[] range = buildPeriodRange(periodType, today);
                    LocalDate from = range[0];
                    LocalDate to = range[1];

                    List<Map<String, Object>> topUsers = pointTransactionQueryRepository
                            .findTopUsersByPeriod(scopeType, scopeId, from, to, displayCount);

                    // 既存スナップショットを削除して再挿入（delete and insert）
                    List<RankingSnapshotEntity> existing = rankingSnapshotRepository
                            .findByScopeTypeAndScopeIdAndPeriodTypeAndPeriodLabelOrderByRankPositionAsc(
                                    scopeType, scopeId, periodType, periodLabel);

                    if (!existing.isEmpty()) {
                        rankingSnapshotRepository.deleteAll(existing);
                    }

                    List<RankingSnapshotEntity> newSnapshots = new ArrayList<>();
                    for (int i = 0; i < topUsers.size(); i++) {
                        Map<String, Object> row = topUsers.get(i);
                        Long userId = ((Number) row.get("user_id")).longValue();
                        int totalPoints = ((Number) row.get("total_points")).intValue();

                        RankingSnapshotEntity snapshot = RankingSnapshotEntity.builder()
                                .scopeType(scopeType)
                                .scopeId(scopeId)
                                .periodType(periodType)
                                .periodLabel(periodLabel)
                                .userId(userId)
                                .totalPoints(totalPoints)
                                .rankPosition(i + 1)
                                .build();

                        newSnapshots.add(snapshot);
                    }

                    rankingSnapshotRepository.saveAll(newSnapshots);
                    processedCount.addAndGet(newSnapshots.size());

                    log.debug("ランキングスナップショット生成: scopeType={}, scopeId={}, periodType={}, periodLabel={}, 件数={}",
                            scopeType, scopeId, periodType, periodLabel, newSnapshots.size());
                }

                scopeCount.incrementAndGet();
            }

            pageable = pageable.next();
        } while (page.hasNext());

        log.info("ランキングスナップショットバッチ完了: スコープ数={}, 総スナップショット件数={}",
                scopeCount.get(), processedCount.get());
    }

    /**
     * 期間種別と日付から期間ラベルを生成する。
     * WEEKLY: "2026-W13" / MONTHLY: "2026-03" / YEARLY: "2026"
     */
    private String buildPeriodLabel(PeriodType periodType, LocalDate date) {
        return switch (periodType) {
            case WEEKLY -> {
                int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                int year = date.get(IsoFields.WEEK_BASED_YEAR);
                yield String.format("%d-W%02d", year, week);
            }
            case MONTHLY -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            case YEARLY -> String.valueOf(date.getYear());
        };
    }

    /**
     * 期間種別と日付から集計範囲[from, to]を返す。
     */
    private LocalDate[] buildPeriodRange(PeriodType periodType, LocalDate date) {
        return switch (periodType) {
            case WEEKLY -> new LocalDate[]{
                    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            };
            case MONTHLY -> new LocalDate[]{
                    date.withDayOfMonth(1),
                    date.with(TemporalAdjusters.lastDayOfMonth())
            };
            case YEARLY -> new LocalDate[]{
                    LocalDate.of(date.getYear(), 1, 1),
                    LocalDate.of(date.getYear(), 12, 31)
            };
        };
    }
}
