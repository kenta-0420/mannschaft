package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F09.17 Phase 11-b ε-A メッセージ型キャンペーン状態自動遷移スケジューラ。
 *
 * <p>設計書 §5「キャンペーン状態遷移マシン」のうち、時刻トリガで自動遷移する 2 種:</p>
 * <ol>
 *   <li>{@code SCHEDULED → DELIVERING} : {@code starts_at <= now} に到達したら開始</li>
 *   <li>{@code DELIVERING → COMPLETED} : {@code ends_at <= now} に到達したら完了</li>
 * </ol>
 *
 * <p>cron は 5 分間隔 (本番), プロパティ {@code mannschaft.ad.state-transition.cron} で上書き可能。
 * {@code @SchedulerLock} で多重実行を防止する。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdCampaignStateTransitionScheduler {

    private final AdMessagingCampaignRepository campaignRepository;

    /**
     * 5 分間隔 (Asia/Tokyo) で起動する状態遷移本体。
     */
    @Scheduled(cron = "${mannschaft.ad.state-transition.cron:0 */5 * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "adCampaignStateTransition", lockAtMostFor = "5m", lockAtLeastFor = "1m")
    public void runTransitions() {
        long startMs = System.currentTimeMillis();
        log.info("AdCampaignStateTransitionScheduler 開始");
        int promoted = promoteScheduledToDelivering();
        int completed = completeDeliveringPastEndsAt();
        log.info("AdCampaignStateTransitionScheduler 完了 所要={}ms promoted={} completed={}",
                System.currentTimeMillis() - startMs, promoted, completed);
    }

    /**
     * {@code status=SCHEDULED AND starts_at <= now} を {@code DELIVERING} に遷移させる。
     *
     * <p>credit_limit 同期判定は launch 時にすでに行われているため、ここでは再判定しない
     * (再判定は ε-C 課金ブリッジが {@code MessagingCampaignAutoPausedEvent} 経由で別途実施予定)。</p>
     *
     * @return 遷移したキャンペーン数
     */
    @Transactional
    public int promoteScheduledToDelivering() {
        LocalDateTime now = LocalDateTime.now();
        List<AdMessagingCampaign> targets = campaignRepository
                .findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(AdCampaignStatus.SCHEDULED, now);
        for (AdMessagingCampaign campaign : targets) {
            campaign.setStatus(AdCampaignStatus.DELIVERING);
            campaignRepository.save(campaign);
            log.info("CAMPAIGN_DELIVERING_STARTED campaignId={}", campaign.getId());
            // TODO(F09.17 ε-C): F10.3 監査ログイベント CAMPAIGN_DELIVERING_STARTED を発火する
        }
        return targets.size();
    }

    /**
     * {@code status=DELIVERING AND ends_at <= now} を {@code COMPLETED} に遷移させる。
     *
     * @return 遷移したキャンペーン数
     */
    @Transactional
    public int completeDeliveringPastEndsAt() {
        LocalDateTime now = LocalDateTime.now();
        List<AdMessagingCampaign> targets = campaignRepository
                .findByStatusAndEndsAtLessThanEqualAndDeletedAtIsNull(AdCampaignStatus.DELIVERING, now);
        for (AdMessagingCampaign campaign : targets) {
            campaign.setStatus(AdCampaignStatus.COMPLETED);
            campaignRepository.save(campaign);
            log.info("CAMPAIGN_COMPLETED campaignId={}", campaign.getId());
            // TODO(F09.17 ε-C): F10.3 監査ログイベント CAMPAIGN_COMPLETED を発火する
        }
        return targets.size();
    }
}
