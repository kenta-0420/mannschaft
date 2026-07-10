package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

    /** 予約 EXPIRED しきい値（日）。served_at IS NULL のまま経過したら serve 対象外化 + FreqCap 返却。 */
    private static final int RESERVATION_EXPIRY_DAYS = 14;

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdBannerDeliveryRepository bannerDeliveryRepository;
    private final AdFrequencyCapService frequencyCapService;

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

    /**
     * F09.19.3 §7.4 / §16 AC-3.8: 予約鮮度の日次スキャン。
     *
     * <p>{@code served_at IS NULL} かつ {@code created_at} から {@value #RESERVATION_EXPIRY_DAYS} 日超過した
     * 未表示予約を EXPIRED 扱いとし、<b>予約の消費週</b>の FreqCap カウンタを {@code releaseSlot} で返却する。
     * 予約行自体は残す（serve 対象外化はサービング側の 14 日鮮度フィルタが担う）。</p>
     *
     * <p><b>冪等性</b>: FreqCap キーは週境界 TTL（最大 7 日）のため、14 日経過時点で消費週キーは通常失効済み
     * → {@code releaseSlot} は no-op（{@code decrementIfPositive} が absent キーを安全に無視）。
     * よって行を残したまま日次で再スキャンしても over-decrement は起きない。</p>
     *
     * @return FreqCap 返却を試みた予約行数
     */
    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "adBannerReservationExpiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public int expireStaleReservations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RESERVATION_EXPIRY_DAYS);
        List<AdBannerDelivery> stale = bannerDeliveryRepository.findStaleUnservedReservations(cutoff);
        int released = 0;
        for (AdBannerDelivery delivery : stale) {
            if (delivery.getUserId() == null || delivery.getCreatedAt() == null) {
                continue;
            }
            Long advertiserAccountId = campaignRepository.findById(delivery.getCampaignId())
                    .map(AdMessagingCampaign::getAdvertiserAccountId)
                    .orElse(null);
            if (advertiserAccountId == null) {
                continue;
            }
            // 予約の消費週（created_at の週）を対象にする（現在週を DECR すると別週の生きたカウンタを誤減算するため）。
            LocalDate consumptionWeekStart = AdFrequencyCapService.weekStartOf(
                    delivery.getCreatedAt().toLocalDate());
            frequencyCapService.releaseSlot(delivery.getUserId(), advertiserAccountId, consumptionWeekStart);
            released++;
        }
        log.info("AD_BANNER_RESERVATION_EXPIRED staleCount={} released={}", stale.size(), released);
        return released;
    }
}
