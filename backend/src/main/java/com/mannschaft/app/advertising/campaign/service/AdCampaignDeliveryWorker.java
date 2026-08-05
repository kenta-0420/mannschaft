package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
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
import java.util.stream.Stream;

/**
 * F09.17 Phase 11-b ε-B キャンペーン配信ワーカー。
 *
 * <p>1 分間隔で {@code status='DELIVERING' AND starts_at &lt;= now AND ends_at &gt;= now} の
 * キャンペーンをスキャンし、{@link AdAudienceResolver#streamCandidateUserIds} で取得した
 * 各ユーザーに対して {@link AdCampaignDeliveryDispatcher#deliverForUser} を呼ぶ。</p>
 *
 * <h3>多重実行防止</h3>
 * <p>{@code @SchedulerLock} で複数ノード並列起動時の重複実行を防ぐ。
 * さらに {@code SELECT ... FOR UPDATE} で同一キャンペーンの並行配信も二重に防ぐ。</p>
 *
 * <h3>独立失敗</h3>
 * <p>キャンペーン単位はトランザクション無し（{@link AdCampaignDeliveryDispatcher} が
 * user 単位で {@code REQUIRES_NEW}）。1 キャンペーンの失敗は次のキャンペーンに進める。</p>
 *
 * <h3>cron 設定</h3>
 * <ul>
 *   <li>本番: {@code mannschaft.ad.delivery.cron=0 * * * * *}（毎分 0 秒）</li>
 *   <li>テスト: プロパティで上書き可</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdCampaignDeliveryWorker {

    /** 候補ユーザーチャンクサイズ（設計書 §8 行 1013）。 */
    static final int CHUNK_SIZE = 1000;

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdAudienceResolver audienceResolver;
    private final AdCampaignDeliveryDispatcher dispatcher;

    /**
     * @Scheduled で 1 分間隔起動。{@code @SchedulerLock} で多重実行防止。
     */
    @Scheduled(cron = "${mannschaft.ad.delivery.cron:0 * * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "adCampaignDelivery", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    @BatchEndpoint(name = "ad-campaign-delivery",
            description = "配信中(DELIVERING)のメッセージ型広告キャンペーンを毎分スキャンし、対象ユーザーへ配信する")
    public void runDelivery() {
        long startMs = System.currentTimeMillis();
        log.info("AdCampaignDeliveryWorker 開始");

        List<AdMessagingCampaign> targets = loadActiveCampaigns();
        log.info("AdCampaignDeliveryWorker 対象キャンペーン数={}", targets.size());

        int totalDelivered = 0;
        int totalUsers = 0;
        for (AdMessagingCampaign campaign : targets) {
            try {
                DeliveryResult r = processCampaign(campaign);
                totalDelivered += r.delivered();
                totalUsers += r.users();
            } catch (RuntimeException ex) {
                // 個別キャンペーン失敗は全体停止させず次に進める
                log.error("AdCampaignDeliveryWorker キャンペーン処理失敗 campaignId={}",
                        campaign.getId(), ex);
            }
        }

        log.info("AdCampaignDeliveryWorker 完了 所要={}ms 対象={} ユーザー数合計={} 配信成功={}",
                System.currentTimeMillis() - startMs, targets.size(), totalUsers, totalDelivered);
    }

    /**
     * 対象キャンペーンを {@code SELECT ... FOR UPDATE} で取得する。
     *
     * <p>{@code @Transactional} 境界はこのメソッド内で完結し、ロックはトランザクションコミット時に解放される
     * （ループ全体のロック保持を避ける）。</p>
     */
    @Transactional
    List<AdMessagingCampaign> loadActiveCampaigns() {
        return campaignRepository.findActiveDeliveringForUpdate(
                AdCampaignStatus.DELIVERING, LocalDateTime.now());
    }

    /**
     * 1 キャンペーンを処理する。トランザクションは持たず、{@link AdCampaignDeliveryDispatcher}
     * の REQUIRES_NEW に user 単位の境界を委ねる。
     */
    DeliveryResult processCampaign(AdMessagingCampaign campaign) {
        int delivered = 0;
        int users = 0;
        try (Stream<Long> stream = audienceResolver.streamCandidateUserIds(campaign.getId())) {
            // chunk 化はストリーム消費中の例外を局所化するためで、現状の AdAudienceResolver は
            // メモリ展開のため意味は薄いが将来 chunked-load 化したときのインタフェース安定のため。
            java.util.Iterator<Long> it = stream.iterator();
            while (it.hasNext()) {
                Long userId = it.next();
                users++;
                try {
                    if (dispatcher.deliverForUser(campaign, userId)) {
                        delivered++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("AdCampaignDeliveryWorker user 配信失敗 campaignId={} userId={}",
                            campaign.getId(), userId, ex);
                }
            }
        }
        log.info("AdCampaignDeliveryWorker キャンペーン完了 campaignId={} users={} delivered={}",
                campaign.getId(), users, delivered);
        return new DeliveryResult(users, delivered);
    }

    /** 1 キャンペーン処理の集計値。 */
    record DeliveryResult(int users, int delivered) {
    }
}
