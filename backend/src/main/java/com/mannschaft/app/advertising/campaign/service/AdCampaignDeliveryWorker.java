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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /** 候補ユーザーチャンクサイズ（設計書 §8 行 1013）。1 回の実行で処理する上限件数。 */
    static final int CHUNK_SIZE = 1000;

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdAudienceResolver audienceResolver;
    private final AdCampaignDeliveryDispatcher dispatcher;

    /**
     * キャンペーンごとの「直近まで処理した user_id」カーソル（キーセットページング用）。
     *
     * <p>1 回の実行で候補ユーザー全員を処理しようとすると {@code lockAtMostFor} を超過しうるため、
     * {@link #CHUNK_SIZE} 件ずつ user_id 昇順で処理し、続きは次回起動時にこのカーソルから再開する。
     * 全件処理し終えたら先頭へ巻き戻り、周回し続ける。</p>
     *
     * <p>このカーソルはノード内メモリのみで保持する（bean 単位のベストエフォート）。再起動時は
     * 先頭から再開するため、頻繁な再起動が続く環境では後方の候補が相対的に遅れて処理される
     * リスクが残る。</p>
     */
    private final Map<UUID, Long> deliveryCursorByCampaignId = new ConcurrentHashMap<>();

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
     *
     * <p>候補ユーザーを user_id 昇順に並べ、前回カーソルより後ろから {@link #CHUNK_SIZE} 件のみ処理する
     * （有界化）。末尾まで到達したら次回はカーソルを先頭へ巻き戻す。</p>
     */
    DeliveryResult processCampaign(AdMessagingCampaign campaign) {
        UUID campaignId = campaign.getId();
        long cursor = deliveryCursorByCampaignId.getOrDefault(campaignId, Long.MIN_VALUE);

        List<Long> candidates;
        try (Stream<Long> stream = audienceResolver.streamCandidateUserIds(campaignId)) {
            candidates = stream.sorted().toList();
        }

        List<Long> targetUsers = candidates.stream()
                .filter(id -> id > cursor)
                .limit(CHUNK_SIZE)
                .toList();
        boolean reachedEnd = targetUsers.size() < CHUNK_SIZE;
        if (targetUsers.isEmpty() && !candidates.isEmpty()) {
            // カーソルが末尾を超えている（全件処理済み）→ 先頭から巻き戻して 1 チャンク処理する
            targetUsers = candidates.stream().limit(CHUNK_SIZE).toList();
            reachedEnd = targetUsers.size() < CHUNK_SIZE;
        }

        int delivered = 0;
        int users = 0;
        for (Long userId : targetUsers) {
            users++;
            try {
                if (dispatcher.deliverForUser(campaign, userId)) {
                    delivered++;
                }
            } catch (RuntimeException ex) {
                log.warn("AdCampaignDeliveryWorker user 配信失敗 campaignId={} userId={}",
                        campaignId, userId, ex);
            }
        }

        if (!targetUsers.isEmpty()) {
            long lastProcessed = targetUsers.get(targetUsers.size() - 1);
            if (reachedEnd) {
                deliveryCursorByCampaignId.remove(campaignId);
            } else {
                deliveryCursorByCampaignId.put(campaignId, lastProcessed);
            }
        }

        log.info("AdCampaignDeliveryWorker キャンペーン完了 campaignId={} 候補数={} users={} delivered={}",
                campaignId, candidates.size(), users, delivered);
        return new DeliveryResult(users, delivered);
    }

    /** 1 キャンペーン処理の集計値。 */
    record DeliveryResult(int users, int delivered) {
    }
}
