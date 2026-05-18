package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * F09.17 Phase 11-b ε-B キャンペーン配信ディスパッチャ。
 *
 * <p>ユーザー 1 人ごとに以下を実行する:</p>
 * <ol>
 *   <li>{@link UserAdPreferenceService#getOrCreateEntityForUser} で受信設定を取得</li>
 *   <li>{@code blocked_advertiser_account_ids} 判定（命中ならスキップ）</li>
 *   <li>{@link AdFrequencyCapService#tryConsume} でフリークエンシーキャップ判定</li>
 *   <li>登録された各 channel に応じて Channel Service へ委譲（ANNOUNCEMENT → EMAIL → PUSH → BANNER）</li>
 *   <li>各 channel のチャネル別 {@code accept_*_ads = false} ならそのチャネルをスキップ</li>
 *   <li>locale 選択: users.locale → "ja" フォールバック → 最初に登録された locale</li>
 *   <li>全 channel skip となった場合は FreqCap を消費しないようロールバック対象とする
 *       （本実装では「1 件以上配信できたら true」として扱う。0 件なら DECR）</li>
 * </ol>
 *
 * <p>本クラスは {@code @Transactional(REQUIRES_NEW)} でユーザー 1 人ごとに独立した
 * トランザクション境界を持つ。これによりユーザー A の例外がユーザー B の配信を巻き戻さない。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdCampaignDeliveryDispatcher {

    private final UserAdPreferenceService userAdPreferenceService;
    private final AdFrequencyCapService frequencyCapService;
    private final AdMessagingCampaignChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final AdAnnouncementChannelService announcementChannelService;
    private final AdEmailChannelService emailChannelService;
    private final AdPushChannelService pushChannelService;
    private final AdBannerChannelService bannerChannelService;

    /**
     * 1 ユーザーへの配信処理を実行する。
     *
     * <p>REQUIRES_NEW なので呼び出し元のトランザクションから独立。
     * 例外はキャッチして false を返し、上位ワーカーが次ユーザーに進めるようにする。</p>
     *
     * @param campaign 配信対象キャンペーン
     * @param userId   配信先ユーザー
     * @return 1 件でも実配信した場合 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliverForUser(AdMessagingCampaign campaign, Long userId) {
        if (campaign == null || userId == null) {
            log.warn("AD_DELIVERY_INVALID_ARGS campaign={} userId={}", campaign, userId);
            return false;
        }

        // 1) 受信設定取得 + 広告主ブロック判定
        UserAdPreference pref;
        try {
            pref = userAdPreferenceService.getOrCreateEntityForUser(userId);
        } catch (RuntimeException ex) {
            log.warn("AD_DELIVERY_PREF_FETCH_FAIL userId={}", userId, ex);
            return false;
        }
        List<Long> blocked = userAdPreferenceService.decodeBlockedAdvertiserIds(pref);
        if (blocked.contains(campaign.getAdvertiserAccountId())) {
            log.debug("AD_DELIVERY_SKIPPED reason=ADVERTISER_BLOCKED userId={} advertiserId={}",
                    userId, campaign.getAdvertiserAccountId());
            return false;
        }

        // 2) FreqCap 判定
        boolean capOk = frequencyCapService.tryConsume(
                userId, campaign.getAdvertiserAccountId(), campaign.getId());
        if (!capOk) {
            log.debug("AD_DELIVERY_SKIPPED reason=FREQ_CAP userId={} campaignId={}",
                    userId, campaign.getId());
            return false;
        }

        // 3) channel 一覧 + locale 解決
        List<AdMessagingCampaignChannel> allChannels =
                channelRepository.findByCampaignId(campaign.getId());
        if (allChannels.isEmpty()) {
            log.warn("AD_DELIVERY_NO_CHANNELS campaignId={}", campaign.getId());
            // FreqCap を消費したが配信無し → ロールバック
            rollbackFreqCap(userId, campaign);
            return false;
        }
        String userLocale = resolveUserLocale(userId);

        Map<AdChannelType, AdMessagingCampaignChannel> byType =
                pickLocaleChannelByType(allChannels, userLocale);

        // 4) 各 channel に委譲（順序: ANNOUNCEMENT → EMAIL → PUSH → BANNER）
        int delivered = 0;
        delivered += deliverChannel(AdChannelType.ANNOUNCEMENT, byType, pref,
                pref.getAcceptAnnouncementAds(), campaign, userId);
        delivered += deliverChannel(AdChannelType.EMAIL, byType, pref,
                pref.getAcceptEmailAds(), campaign, userId);
        delivered += deliverChannel(AdChannelType.PUSH, byType, pref,
                pref.getAcceptPushAds(), campaign, userId);
        delivered += deliverChannel(AdChannelType.BANNER, byType, pref,
                pref.getAcceptBannerAds(), campaign, userId);

        if (delivered == 0) {
            // 全チャネル skip だった場合は FreqCap を返す
            rollbackFreqCap(userId, campaign);
            return false;
        }
        return true;
    }

    // ----------------------------------------------------------------
    // 内部ヘルパー
    // ----------------------------------------------------------------

    /**
     * 単一チャネル委譲。channel 未登録 or accept フラグ false ならスキップ。
     *
     * @return 配信に成功したら 1、スキップ・失敗時は 0
     */
    private int deliverChannel(AdChannelType type,
                               Map<AdChannelType, AdMessagingCampaignChannel> byType,
                               UserAdPreference pref,
                               Boolean acceptFlag,
                               AdMessagingCampaign campaign,
                               Long userId) {
        AdMessagingCampaignChannel channel = byType.get(type);
        if (channel == null) {
            return 0;
        }
        if (!Boolean.TRUE.equals(acceptFlag)) {
            log.debug("AD_CHANNEL_OPT_OUT type={} userId={}", type, userId);
            return 0;
        }
        try {
            return switch (type) {
                case ANNOUNCEMENT -> {
                    announcementChannelService.deliver(campaign, channel, userId);
                    yield 1;
                }
                case EMAIL ->
                    emailChannelService.deliver(campaign, channel, userId) ? 1 : 0;
                case PUSH ->
                    pushChannelService.deliver(campaign, channel, userId) ? 1 : 0;
                case BANNER ->
                    bannerChannelService.deliver(campaign, channel, userId) ? 1 : 0;
            };
        } catch (RuntimeException ex) {
            // 個別チャネル失敗は全体を巻き戻さず次チャネルに進める
            log.warn("AD_CHANNEL_DELIVER_FAIL type={} userId={} campaignId={}",
                    type, userId, campaign.getId(), ex);
            return 0;
        }
    }

    /**
     * (channel_type → 該当 locale の channel) マップを作成する。
     *
     * <p>locale 選択ロジック (設計書 §5):</p>
     * <ol>
     *   <li>users.locale と一致する channel を採用</li>
     *   <li>無ければ locale="ja" を採用</li>
     *   <li>それも無ければ最初に登録された locale を採用</li>
     * </ol>
     */
    Map<AdChannelType, AdMessagingCampaignChannel> pickLocaleChannelByType(
            List<AdMessagingCampaignChannel> all, String userLocale) {
        Map<AdChannelType, AdMessagingCampaignChannel> result = new EnumMap<>(AdChannelType.class);
        for (AdMessagingCampaignChannel ch : all) {
            AdMessagingCampaignChannel current = result.get(ch.getChannelType());
            if (current == null) {
                result.put(ch.getChannelType(), ch);
                continue;
            }
            int currentRank = localeRank(current.getLocale(), userLocale);
            int candidateRank = localeRank(ch.getLocale(), userLocale);
            if (candidateRank < currentRank) {
                result.put(ch.getChannelType(), ch);
            }
        }
        return result;
    }

    /**
     * locale 優先順位を数値化（小さいほど優先）。
     * <ul>
     *   <li>ユーザー locale 一致: 0</li>
     *   <li>"ja" 一致: 1</li>
     *   <li>その他: 2</li>
     * </ul>
     */
    private static int localeRank(String channelLocale, String userLocale) {
        if (channelLocale == null) {
            return 3;
        }
        if (userLocale != null && channelLocale.equalsIgnoreCase(userLocale)) {
            return 0;
        }
        if ("ja".equalsIgnoreCase(channelLocale)) {
            return 1;
        }
        return 2;
    }

    /**
     * 受信者の locale を取得する（取得失敗時は "ja" フォールバック）。
     */
    private String resolveUserLocale(Long userId) {
        try {
            return userRepository.findLocaleById(userId)
                    .filter(s -> !s.isBlank())
                    .orElse("ja");
        } catch (RuntimeException ex) {
            log.debug("locale 取得失敗 userId={} fallback=ja", userId, ex);
            return "ja";
        }
    }

    /**
     * FreqCap ロールバック。{@code AdFrequencyCapService} は外向きに DECR API を公開して
     * いないため、現状は best-effort のログ出力にとどめる。
     *
     * <p>TODO(F09.17 ε-C): {@code AdFrequencyCapService.releaseSlot} を新設してロールバックを完遂する。
     * 現状の挙動: FreqCap は「先取りした分」が残るため、ユーザーは本日の枠を 1 件分損する。
     * 0 件配信ケースは「全チャネル opt-out + channel 未登録」など稀少ケースのため、
     * v1 では運用上許容範囲と判断（設計書 §5 末尾「途中失敗時のロールバックを成立させるため」
     * の精緻な実装は ε-C 検討事項）。</p>
     */
    private void rollbackFreqCap(Long userId, AdMessagingCampaign campaign) {
        log.warn("AD_FREQ_CAP_ROLLBACK_PENDING userId={} campaignId={}"
                        + " (TODO: AdFrequencyCapService.releaseSlot を ε-C で実装予定)",
                userId, campaign.getId());
    }
}
