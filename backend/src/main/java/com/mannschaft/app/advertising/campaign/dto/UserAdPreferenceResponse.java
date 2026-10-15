package com.mannschaft.app.advertising.campaign.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F09.17 Phase 11-a 受信者の広告受信設定レスポンス DTO。
 *
 * <p>設計書「Preferences 域」§4.1 GET /api/v1/me/ad-preferences レスポンス形に対応する。</p>
 *
 * @param id                          設定行 ID（UUIDv7）
 * @param acceptAnnouncementAds       お知らせ枠広告の受信許可
 * @param acceptEmailAds              メール広告の受信許可
 * @param acceptPushAds               プッシュ通知広告の受信許可
 * @param acceptBannerAds             バナー広告の受信許可
 * @param blockedAdvertiserAccountIds ブロック広告主アカウント ID 一覧（最大 100 件）
 * @param consentedAt                 初回同意日時（未同意なら null）
 * @param unsubscribeTokenVersion     unsubscribe JWT バージョン
 * @param updatedAt                   最終更新日時
 */
public record UserAdPreferenceResponse(
        UUID id,
        Boolean acceptAnnouncementAds,
        Boolean acceptEmailAds,
        Boolean acceptPushAds,
        Boolean acceptBannerAds,
        List<Long> blockedAdvertiserAccountIds,
        LocalDateTime consentedAt,
        Integer unsubscribeTokenVersion,
        LocalDateTime updatedAt) {
}
