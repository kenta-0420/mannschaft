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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * F09.17 Phase 11-b/c キャンペーン配信ディスパッチャ。
 *
 * <p>ユーザー 1 人ごとに以下を実行する:</p>
 * <ol>
 *   <li>{@link UserAdPreferenceService#getOrCreateEntityForUser} で受信設定を取得</li>
 *   <li>{@code blocked_advertiser_account_ids} 判定（命中ならスキップ）</li>
 *   <li>{@link AdFrequencyCapService#tryConsume} でフリークエンシーキャップ判定
 *       （Valkey を数えられない場合は fail-closed でスキップする）</li>
 *   <li>{@link AdCampaignDeliveryClaimService#tryClaim} で {@code (campaignId, userId, weekStart)} の
 *       DB claim を確保する（claim-then-act。確保できなければ FreqCap を返却してスキップ）</li>
 *   <li>登録された各 channel に応じて Channel Service へ委譲（ANNOUNCEMENT → EMAIL → PUSH → BANNER）</li>
 *   <li>各 channel のチャネル別 {@code accept_*_ads = false} ならそのチャネルをスキップ</li>
 *   <li>locale 選択: users.locale → "ja" フォールバック → 最初に登録された locale</li>
 *   <li>全 channel skip となった場合は FreqCap と DB claim の両方を返却する
 *       （本実装では「1 件以上配信できたら DELIVERED」として扱う。0 件なら両方ロールバック）</li>
 * </ol>
 *
 * <h3>週の定義（FreqCap と厳密一致）</h3>
 * <p>DB claim の {@code week_start} は {@link AdFrequencyCapService#currentWeekStart} と同一の
 * 定義（受信者 TZ の月曜 00:00）を使う。定義がずれると Valkey の消費枠と DB の claim が
 * 別の週を指してしまい、二重の守りが噛み合わなくなる。</p>
 *
 * <p>本クラスは {@code @Transactional(REQUIRES_NEW)} でユーザー 1 人ごとに独立した
 * トランザクション境界を持つ。これによりユーザー A の例外がユーザー B の配信を巻き戻さない。
 * DB claim（{@link AdCampaignDeliveryClaimService}）はさらにその内側で
 * {@code REQUIRES_NEW} の専用トランザクションを持つ（一意制約違反時にこの user 単位
 * トランザクション全体を巻き込まないため）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdCampaignDeliveryDispatcher {

    private final UserAdPreferenceService userAdPreferenceService;
    private final AdFrequencyCapService frequencyCapService;
    private final AdCampaignDeliveryClaimService claimService;
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
     * 例外はキャッチして {@link AdDeliveryOutcome#SKIPPED} を返し、
     * 上位ワーカーが次ユーザーに進めるようにする。</p>
     *
     * @param campaign 配信対象キャンペーン
     * @param userId   配信先ユーザー
     * @return 結果種別（{@link AdDeliveryOutcome}）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdDeliveryOutcome deliverForUser(AdMessagingCampaign campaign, Long userId) {
        if (campaign == null || userId == null) {
            log.warn("AD_DELIVERY_INVALID_ARGS campaign={} userId={}", campaign, userId);
            return AdDeliveryOutcome.SKIPPED;
        }

        // 1) 受信設定取得 + 広告主ブロック判定
        UserAdPreference pref;
        try {
            pref = userAdPreferenceService.getOrCreateEntityForUser(userId);
        } catch (RuntimeException ex) {
            log.warn("AD_DELIVERY_PREF_FETCH_FAIL userId={}", userId, ex);
            return AdDeliveryOutcome.SKIPPED;
        }
        List<Long> blocked = userAdPreferenceService.decodeBlockedAdvertiserIds(pref);
        if (blocked.contains(campaign.getAdvertiserAccountId())) {
            log.debug("AD_DELIVERY_SKIPPED reason=ADVERTISER_BLOCKED userId={} advertiserId={}",
                    userId, campaign.getAdvertiserAccountId());
            return AdDeliveryOutcome.SKIPPED;
        }

        // 2) FreqCap 判定（fail-closed: Valkey を数えられない場合は配信しない）
        boolean capOk;
        try {
            capOk = frequencyCapService.tryConsume(
                    userId, campaign.getAdvertiserAccountId(), campaign.getId());
        } catch (RuntimeException ex) {
            log.error("AD_DELIVERY_FREQCAP_ERROR fail-closed（配信しない） userId={} campaignId={}",
                    userId, campaign.getId(), ex);
            return AdDeliveryOutcome.SKIPPED_FREQ_CAP_UNAVAILABLE;
        }
        if (!capOk) {
            log.debug("AD_DELIVERY_SKIPPED reason=FREQ_CAP userId={} campaignId={}",
                    userId, campaign.getId());
            return AdDeliveryOutcome.SKIPPED;
        }

        // 2.5) DB claim 確保（claim-then-act）。FreqCap と同一の週定義を用いる。
        ZoneId userZone = frequencyCapService.resolveUserZone(userId);
        LocalDate weekStart = AdFrequencyCapService.currentWeekStart(userZone);
        boolean claimed = claimService.tryClaim(campaign.getId(), userId, weekStart);
        if (!claimed) {
            // 既に他の実行（並行 or 再試行）が確保済み → FreqCap を返却してスキップ
            frequencyCapService.releaseSlot(userId, campaign.getAdvertiserAccountId(), weekStart);
            log.info("AD_DELIVERY_SKIPPED reason=ALREADY_CLAIMED userId={} campaignId={} weekStart={}",
                    userId, campaign.getId(), weekStart);
            return AdDeliveryOutcome.SKIPPED_ALREADY_CLAIMED;
        }

        // 3) channel 一覧 + locale 解決
        List<AdMessagingCampaignChannel> allChannels =
                channelRepository.findByCampaignId(campaign.getId());
        if (allChannels.isEmpty()) {
            log.warn("AD_DELIVERY_NO_CHANNELS campaignId={}", campaign.getId());
            // FreqCap / claim を消費したが配信無し → ロールバック
            rollbackFreqCapAndClaim(userId, campaign, weekStart);
            return AdDeliveryOutcome.SKIPPED;
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
            // 全チャネル skip だった場合は FreqCap / claim を両方返す
            rollbackFreqCapAndClaim(userId, campaign, weekStart);
            return AdDeliveryOutcome.SKIPPED;
        }
        return AdDeliveryOutcome.DELIVERED;
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
     * FreqCap + DB claim ロールバック（F09.19.7 §10.4 / AC-7.4 + F09.17 Phase 11-c）。
     *
     * <p>全チャネル skip（0 件配信）となった場合に、直前の {@link AdFrequencyCapService#tryConsume}
     * で先取りした total / per-advertiser 両カウンタを {@link AdFrequencyCapService#releaseSlot} で、
     * 同時に確保した DB claim を {@link AdCampaignDeliveryClaimService#releaseClaim} で、
     * それぞれ返却する。{@code weekStart} は呼び出し元 {@link #deliverForUser} が
     * {@code tryConsume}/{@code tryClaim} と共通で使った値をそのまま渡すため、
     * 同一週のキー・行を確実に返却できる。{@code releaseSlot} は {@code decrementIfPositive} により
     * 0 未満へは下げないため冪等・安全。</p>
     */
    private void rollbackFreqCapAndClaim(Long userId, AdMessagingCampaign campaign, LocalDate weekStart) {
        frequencyCapService.releaseSlot(userId, campaign.getAdvertiserAccountId(), weekStart);
        claimService.releaseClaim(campaign.getId(), userId, weekStart);
        log.debug("AD_FREQ_CAP_AND_CLAIM_ROLLBACK userId={} campaignId={} weekStart={}",
                userId, campaign.getId(), weekStart);
    }
}
