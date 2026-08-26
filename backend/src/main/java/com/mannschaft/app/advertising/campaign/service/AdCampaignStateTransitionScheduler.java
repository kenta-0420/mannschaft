package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.membership.domain.ScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** 予約鮮度スキャンの 1 チャンクあたり件数。 */
    private static final int RESERVATION_EXPIRY_CHUNK_SIZE = 500;

    /** UUIDv7 の最小値（キーセットページングの初期カーソル）。 */
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    /** F09.19.7 §10.5: スケジューラ起因（ユーザー操作なし）イベントの actor = システムユーザー（V1.012 seed）。 */
    private static final Long SYSTEM_USER_ID = 1L;

    static final String AUDIT_CAMPAIGN_DELIVERING_STARTED = "CAMPAIGN_DELIVERING_STARTED";
    static final String AUDIT_CAMPAIGN_COMPLETED = "CAMPAIGN_COMPLETED";

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdBannerDeliveryRepository bannerDeliveryRepository;
    private final AdFrequencyCapService frequencyCapService;
    private final AuditLogService auditLogService;

    /**
     * 5 分間隔 (Asia/Tokyo) で起動する状態遷移本体。
     */
    @Scheduled(cron = "${mannschaft.ad.state-transition.cron:0 */5 * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "adCampaignStateTransition", lockAtMostFor = "PT15M", lockAtLeastFor = "1m")
    @BatchEndpoint(name = "ad-campaign-state-transition",
            description = "予約(SCHEDULED)→配信中(DELIVERING)、配信中→完了(COMPLETED)のキャンペーン状態自動遷移を5分毎に実行する")
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
            fireAudit(AUDIT_CAMPAIGN_DELIVERING_STARTED, campaign);
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
            fireAudit(AUDIT_CAMPAIGN_COMPLETED, campaign);
        }
        return targets.size();
    }

    /**
     * F09.19.7 §10.5 / AC-7.5: スケジューラ自動遷移の監査ログを fire-and-forget で記録する。
     * actor はシステムユーザー（{@link #SYSTEM_USER_ID}）。metadata は {@code {"campaign_id":"<uuid>"}}。
     */
    private void fireAudit(String eventType, AdMessagingCampaign campaign) {
        Long teamId = campaign.getScopeType() == ScopeType.TEAM ? campaign.getScopeId() : null;
        Long orgId = campaign.getScopeType() == ScopeType.ORGANIZATION ? campaign.getScopeId() : null;
        String metadata = "{\"campaign_id\":\"" + campaign.getId() + "\"}";
        auditLogService.record(eventType, SYSTEM_USER_ID, null, teamId, orgId, null, null, null, metadata);
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
     * @return FreqCap 返却を試みた予約行数。ShedLock はプリミティブ戻り値のメソッドをロックできないため
     *         参照型 {@code Integer} を返す（issue #2724）。ロック未取得時は ShedLock が {@code null} を返す
     */
    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "adBannerReservationExpiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @BatchEndpoint(name = "ad-banner-reservation-expire-daily",
            description = "14日超過して未表示のまま残ったバナー広告予約を毎日02:15に期限切れ扱いにし、頻度キャップの枠を返却する")
    public Integer expireStaleReservations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RESERVATION_EXPIRY_DAYS);
        int totalStale = 0;
        int totalReleased = 0;
        UUID cursor = MIN_UUID;
        while (true) {
            List<AdBannerDelivery> page = bannerDeliveryRepository.findStaleUnservedReservationsPage(
                    cutoff, cursor, PageRequest.of(0, RESERVATION_EXPIRY_CHUNK_SIZE));
            if (page.isEmpty()) {
                break;
            }
            totalStale += page.size();
            totalReleased += expireReservationChunk(page);
            cursor = page.get(page.size() - 1).getId();
            if (page.size() < RESERVATION_EXPIRY_CHUNK_SIZE) {
                break;
            }
        }
        log.info("AD_BANNER_RESERVATION_EXPIRED staleCount={} released={}", totalStale, totalReleased);
        return totalReleased;
    }

    /**
     * 予約鮮度スキャンの 1 チャンク分を独立トランザクションで処理する。
     * チャンク内は campaign_id をまとめて一括取得し（N+1 回避）、行単位の失敗は握り潰さず記録して次に進める。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int expireReservationChunk(List<AdBannerDelivery> chunk) {
        List<UUID> campaignIds = chunk.stream()
                .map(AdBannerDelivery::getCampaignId)
                .distinct()
                .collect(Collectors.toList());
        Map<UUID, Long> advertiserAccountIdByCampaignId = new HashMap<>();
        for (AdMessagingCampaign campaign : campaignRepository.findAllById(campaignIds)) {
            advertiserAccountIdByCampaignId.put(campaign.getId(), campaign.getAdvertiserAccountId());
        }

        int released = 0;
        for (AdBannerDelivery delivery : chunk) {
            try {
                if (delivery.getUserId() == null || delivery.getCreatedAt() == null) {
                    continue;
                }
                Long advertiserAccountId = advertiserAccountIdByCampaignId.get(delivery.getCampaignId());
                if (advertiserAccountId == null) {
                    continue;
                }
                // 予約の消費週（created_at の週）を対象にする（現在週を DECR すると別週の生きたカウンタを誤減算するため）。
                LocalDate consumptionWeekStart = AdFrequencyCapService.weekStartOf(
                        delivery.getCreatedAt().toLocalDate());
                frequencyCapService.releaseSlot(delivery.getUserId(), advertiserAccountId, consumptionWeekStart);
                released++;
            } catch (RuntimeException ex) {
                log.error("AD_BANNER_RESERVATION_EXPIRE_FAIL deliveryId={} campaignId={}",
                        delivery.getId(), delivery.getCampaignId(), ex);
            }
        }
        return released;
    }
}
