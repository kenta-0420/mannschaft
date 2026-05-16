package com.mannschaft.app.advertising.campaign.dto;

import java.util.List;

/**
 * F09.17 Phase 11-a 受信者の広告受信設定 更新リクエスト DTO。
 *
 * <p>設計書「Preferences 域」§4.2 PUT /api/v1/me/ad-preferences リクエスト形に対応する。</p>
 *
 * <p>各フィールドは {@code null} を許容し、null の場合は現在値を保持する（部分更新）。</p>
 *
 * @param acceptAnnouncementAds       お知らせ枠広告の受信許可（null 不更新）
 * @param acceptEmailAds              メール広告の受信許可（null 不更新）
 * @param acceptPushAds               プッシュ通知広告の受信許可（null 不更新）
 * @param acceptBannerAds             バナー広告の受信許可（null 不更新）
 * @param blockedAdvertiserAccountIds ブロック広告主アカウント ID 一覧（null 不更新、最大 100 件）
 * @param rotateUnsubscribeTokens     true の場合 unsubscribe_token_version を +1 する
 */
public record UpdateUserAdPreferencesRequest(
        Boolean acceptAnnouncementAds,
        Boolean acceptEmailAds,
        Boolean acceptPushAds,
        Boolean acceptBannerAds,
        List<Long> blockedAdvertiserAccountIds,
        Boolean rotateUnsubscribeTokens) {
}
