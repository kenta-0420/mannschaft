package com.mannschaft.app.gamification.service;

import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.GamificationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ゲーミフィケーション・バッジ評価バッチサービス。
 * 毎朝3:00 (Asia/Tokyo) にバッジ獲得条件を評価し、対象ユーザーにバッジを付与する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GamificationBadgeBatchService {

    private final GamificationConfigRepository gamificationConfigRepository;
    private final BadgeRepository badgeRepository;

    /**
     * バッジ獲得条件を評価するバッチ処理。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>isEnabled=true の全GamificationConfigを取得</li>
     *   <li>各スコープの全アクティブバッジを取得</li>
     *   <li>conditionType = MANUALのバッジはスキップ</li>
     *   <li>TODO: ATTENDANCE_RATE/MONTHLY_RANK等の条件評価は後続実装</li>
     *   <li>処理件数をログ出力</li>
     * </ol>
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "gamification_badge_evaluation", lockAtMostFor = "PT30M")
    @Transactional
    public void runBadgeEvaluation() {
        log.info("バッジ評価バッチ開始");

        List<GamificationConfigEntity> enabledConfigs = gamificationConfigRepository.findAll()
                .stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsEnabled()))
                .toList();

        AtomicInteger processedBadgeCount = new AtomicInteger(0);
        AtomicInteger skippedBadgeCount = new AtomicInteger(0);

        for (GamificationConfigEntity config : enabledConfigs) {
            String scopeType = config.getScopeType();
            Long scopeId = config.getScopeId();

            List<BadgeEntity> activeBadges =
                    badgeRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                            scopeType, scopeId);

            for (BadgeEntity badge : activeBadges) {
                processedBadgeCount.incrementAndGet();

                // MANUALバッジはスキップ
                if (BadgeConditionType.MANUAL == badge.getConditionType()) {
                    log.debug("MANUALバッジのためスキップ: badgeId={}, scopeType={}, scopeId={}",
                            badge.getId(), scopeType, scopeId);
                    skippedBadgeCount.incrementAndGet();
                    continue;
                }

                // TODO: ATTENDANCE_RATE/MONTHLY_RANK/CUMULATIVE_COUNT/CONSECUTIVE_DAYS等の
                //       条件評価は後続実装。現時点ではスキップしてログに記録する。
                log.debug("バッジ条件評価スキップ（後続実装予定）: badgeId={}, conditionType={}, scopeType={}, scopeId={}",
                        badge.getId(), badge.getConditionType(), scopeType, scopeId);
                skippedBadgeCount.incrementAndGet();
            }
        }

        log.info("バッジ評価バッチ完了: スコープ数={}, バッジ総数={}, スキップ数={}",
                enabledConfigs.size(), processedBadgeCount.get(), skippedBadgeCount.get());
    }
}
