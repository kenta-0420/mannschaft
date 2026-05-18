package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * F09.17 Phase 11-b ε-B バナーチャネル配信サービス（スタブ実装）。
 *
 * <p>BANNER チャネルは F09.7 既存バナー抽選機構との統合が必要なため、ε-B では
 * 抽選器に「次の serve 機会で出すべき広告」をキューイングする責務だけを持つ予定。
 * 実装は後続フェーズ（ε-C 以降）に委ねる。</p>
 *
 * <p>現状は呼び出されたことをログに残すのみで {@code ad_banner_deliveries} 書き込みも行わない。
 * 真の impression / click は F09.7 が serve した瞬間に callback で記録する設計（設計書 §5）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdBannerChannelService {

    /**
     * 1 ユーザーへのバナー配信予約（スタブ）。
     *
     * <p>TODO(F09.17 ε-C): F09.7 {@code AdImpressionService.scheduleServe()} を呼び、
     * {@code serving_strategy='MESSAGING_CAMPAIGN'} で抽選プールに入れる。
     * 実 impression / click は F09.7 が記録し、本ドメインの {@code ad_banner_deliveries} は
     * F09.7 の dispatch event を購読する別 Listener で埋まる想定。</p>
     *
     * @param campaign キャンペーン本体
     * @param channel  BANNER チャネル設定（banner_creative_id を含む）
     * @param userId   受信者
     * @return 常に true（FreqCap は消費したことにする / 設計書 §5 末尾の方針）
     */
    public boolean deliver(AdMessagingCampaign campaign,
                           AdMessagingCampaignChannel channel,
                           Long userId) {
        if (campaign == null || channel == null || userId == null) {
            throw new IllegalArgumentException("campaign, channel, userId は必須です");
        }
        log.info("AD_BANNER_QUEUED (stub) campaignId={} userId={} creativeId={}",
                campaign.getId(), userId, channel.getBannerCreativeId());
        // TODO(F09.17 ε-C): F09.7 統合
        return true;
    }
}
