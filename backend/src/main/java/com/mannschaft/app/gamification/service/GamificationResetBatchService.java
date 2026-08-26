package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.gamification.TransactionType;
import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import com.mannschaft.app.gamification.entity.PointTransactionEntity;
import com.mannschaft.app.gamification.repository.GamificationConfigRepository;
import com.mannschaft.app.gamification.repository.PointTransactionQueryRepository;
import com.mannschaft.app.gamification.repository.PointTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ゲーミフィケーション・ポイントリセットバッチサービス。
 * 毎朝4:00 (Asia/Tokyo) にpoint_reset_monthが一致するスコープのポイントをリセットする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GamificationResetBatchService {

    private static final int CHUNK_SIZE = 500;

    private final GamificationConfigRepository gamificationConfigRepository;
    private final PointTransactionQueryRepository pointTransactionQueryRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * ポイントリセットバッチ処理。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>point_reset_month が現在月と一致するスコープをDB側でフィルタしてチャンク取得</li>
     *   <li>当月1日にリセット済みでないか確認（同月のRESETトランザクション存在チェック）</li>
     *   <li>対象スコープの全ユーザーの現在ポイント合計を取得</li>
     *   <li>マイナスのADJUST（RESET type）PointTransactionをINSERT（相殺）</li>
     *   <li>処理件数をログ出力</li>
     * </ol>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_GAMIFICATION_ENABLED",
            reason = "同じキーで付与側リスナーも同時に止まるため、加算されないポイントをリセットしないという首尾一貫した状態になる。ポイントは金銭ではなくゲーム内状態である")
    @BatchEndpoint(name = "gamification-point-reset-daily", description = "ポイントリセット設定に該当するスコープを毎日 04:00 にリセットする")
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "gamification_point_reset", lockAtMostFor = "PT30M")
    @Transactional
    public void runPointReset() {
        log.info("ポイントリセットバッチ開始");

        LocalDate today = LocalDate.now();
        int currentMonth = today.getMonthValue();
        LocalDate monthStart = today.withDayOfMonth(1);

        AtomicInteger resetScopeCount = new AtomicInteger(0);
        AtomicInteger resetTransactionCount = new AtomicInteger(0);

        // point_reset_monthが現在月と一致するスコープをDB側でフィルタしてチャンク処理
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<GamificationConfigEntity> page;
        do {
            page = gamificationConfigRepository.findByPointResetMonth((byte) currentMonth, pageable);

            for (GamificationConfigEntity config : page.getContent()) {
                String scopeType = config.getScopeType();
                Long scopeId = config.getScopeId();

                // 当月1日にリセット済みか確認（同月のRESETトランザクション存在チェック）
                boolean alreadyReset = isAlreadyResetThisMonth(scopeType, scopeId, monthStart);
                if (alreadyReset) {
                    log.info("当月リセット済みのためスキップ: scopeType={}, scopeId={}", scopeType, scopeId);
                    continue;
                }

                // 対象スコープの全ユーザーとポイント合計を取得
                List<java.util.Map<String, Object>> userPoints = getUserPointsInScope(scopeType, scopeId);

                for (java.util.Map<String, Object> row : userPoints) {
                    Long userId = ((Number) row.get("user_id")).longValue();
                    int totalPoints = ((Number) row.get("total_points")).intValue();

                    if (totalPoints == 0) {
                        continue;
                    }

                    // マイナスのRESETトランザクションをINSERT（相殺）
                    PointTransactionEntity resetTransaction = PointTransactionEntity.builder()
                            .userId(userId)
                            .scopeType(scopeType)
                            .scopeId(scopeId)
                            .transactionType(TransactionType.RESET)
                            .points(-totalPoints)
                            .earnedOn(today)
                            .build();

                    pointTransactionRepository.save(resetTransaction);
                    resetTransactionCount.incrementAndGet();

                    log.debug("ポイントリセット: userId={}, scopeType={}, scopeId={}, points={}",
                            userId, scopeType, scopeId, -totalPoints);
                }

                resetScopeCount.incrementAndGet();
                log.info("スコープリセット完了: scopeType={}, scopeId={}, ユーザー数={}",
                        scopeType, scopeId, userPoints.size());
            }

            pageable = pageable.next();
        } while (page.hasNext());

        log.info("ポイントリセットバッチ完了: 対象スコープ数={}, リセットトランザクション件数={}",
                resetScopeCount.get(), resetTransactionCount.get());
    }

    /**
     * 当月にリセット済みか確認する。
     */
    private boolean isAlreadyResetThisMonth(String scopeType, Long scopeId, LocalDate monthStart) {
        String sql = """
                SELECT COUNT(*)
                FROM point_transactions
                WHERE scope_type = ?
                  AND scope_id = ?
                  AND transaction_type = 'RESET'
                  AND earned_on >= ?
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                scopeType, scopeId, monthStart);
        return count != null && count > 0;
    }

    /**
     * スコープ内の全ユーザーとポイント合計を取得する。
     * 各Mapのキー: user_id (Long), total_points (Long)
     */
    private List<java.util.Map<String, Object>> getUserPointsInScope(String scopeType, Long scopeId) {
        String sql = """
                SELECT user_id, SUM(points) AS total_points
                FROM point_transactions
                WHERE scope_type = ?
                  AND scope_id = ?
                GROUP BY user_id
                HAVING SUM(points) != 0
                """;
        return jdbcTemplate.queryForList(sql, scopeType, scopeId);
    }
}
