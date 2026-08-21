package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.entity.AdPushDelivery;
import com.mannschaft.app.advertising.campaign.repository.AdPushDeliveryRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * F09.17 Phase 11-b ε-B プッシュ通知チャネル配信サービス。
 *
 * <p>処理の流れ:</p>
 * <ol>
 *   <li>{@link NotificationService#createNotification} で通知行を作成
 *       （sourceType=ADVERTISER_CAMPAIGN、scopeType=SYSTEM、priority=LOW）</li>
 *   <li>本文先頭に {@code 【広告】} プレフィックスを強制付与（景品表示法対応）</li>
 *   <li>{@link NotificationDispatchService#dispatch} で WebSocket / WebPush 配信
 *       （opt-out 設定確認・PushSubscription 取得は dispatch 内部で実施）</li>
 *   <li>{@code ad_push_deliveries} に履歴を 1 行追加</li>
 * </ol>
 *
 * <p>{@code NotificationService.createNotification} が visibility deny で {@code null}
 * を返した場合は配信スキップとして false を返す。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdPushChannelService {

    /** YYYY-MM (パーティショニング用 month_key) */
    private static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 通知 sourceType. F02.6 AnnouncementSourceType と同名だが NotificationSourceType 側は独立。 */
    static final String NOTIFICATION_SOURCE_TYPE = "ADVERTISER_CAMPAIGN";

    /** 通知種別。F04.3 NotificationType 文字列。 */
    static final String NOTIFICATION_TYPE = "ADVERTISER_AD";

    private final NotificationService notificationService;
    private final NotificationDispatchService dispatchService;
    private final AdPushDeliveryRepository deliveryRepository;
    private final MessageSource messageSource;

    /**
     * 1 ユーザーに 1 件のプッシュ通知を配信する。
     *
     * @param campaign キャンペーン本体
     * @param channel  PUSH チャネル設定（locale 解決済の単一行）
     * @param userId   受信者
     * @return 配信に成功したら true、visibility deny 等でスキップしたら false
     */
    @Transactional
    public boolean deliver(AdMessagingCampaign campaign,
                           AdMessagingCampaignChannel channel,
                           Long userId) {
        if (campaign == null || channel == null || userId == null) {
            throw new IllegalArgumentException("campaign, channel, userId は必須です");
        }

        // Issue #2715 CMP-055 ロットC-6: channel は locale 解決済の単一行のため、そのロケールで
        // フォールバック文言（subject 未設定時）と広告プレフィックスを組み立てる。
        Locale locale = Locale.forLanguageTag(channel.getLocale() != null ? channel.getLocale() : "ja");
        String defaultTitle = messageSource.getMessage(
                "notification.advertising.push.defaultTitle", null, "新しい広告", locale);
        String adBodyPrefix = messageSource.getMessage(
                "notification.advertising.push.bodyPrefix", null, "【広告】", locale);
        String title = channel.getSubject() != null ? channel.getSubject() : defaultTitle;
        String rawBody = channel.getBodyMarkdown() != null ? channel.getBodyMarkdown() : "";
        String body = rawBody.startsWith(adBodyPrefix) ? rawBody : adBodyPrefix + rawBody;

        // 通知行を作成（NotificationService 内部の visibility ガードは ADVERTISER_CAMPAIGN が
        // ReferenceType に解決できないため fail-soft で通過する設計）。
        // sourceId は ad_messaging_campaigns.id の MSB を BIGINT 化（既存 source_id は BIGINT のため）。
        long sourceIdSeed = campaign.getId().getLeastSignificantBits() & Long.MAX_VALUE;

        NotificationEntity entity = notificationService.createNotification(
                userId,
                NOTIFICATION_TYPE,
                NotificationPriority.LOW,
                title,
                body,
                NOTIFICATION_SOURCE_TYPE,
                sourceIdSeed,
                NotificationScopeType.SYSTEM,
                campaign.getAdvertiserAccountId(),
                /* actionUrl */ null,
                /* actorId   */ null,
                /* organizationId */ campaign.getScopeId());

        if (entity == null) {
            log.debug("AD_PUSH_SKIPPED reason=VISIBILITY_DENY userId={} campaignId={}",
                    userId, campaign.getId());
            return false;
        }

        // WebSocket / WebPush へ配信（非同期）
        dispatchService.dispatch(entity);

        // ad_push_deliveries に履歴
        LocalDateTime now = LocalDateTime.now();
        AdPushDelivery delivery = AdPushDelivery.builder()
                .campaignId(campaign.getId())
                .userId(userId)
                .notificationId(entity.getId())
                .deliveredAt(now)
                .monthKey(now.format(MONTH_KEY_FMT))
                .build();
        deliveryRepository.save(delivery);

        log.info("AD_PUSH_DELIVERED campaignId={} userId={} notificationId={}",
                campaign.getId(), userId, entity.getId());
        return true;
    }
}
