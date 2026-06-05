package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;

/**
 * F02.8 告知チャネルアダプターインターフェース。
 *
 * <p>各チャネル（掲示板・タイムライン・ブログ・TODO・スケジュール・アンケート）ごとに
 * 実装クラスを用意し、{@link AnnouncementChannelAdapterRegistry} で管理する。</p>
 *
 * <p>実装クラスはそれぞれ対応するドメインの Service を呼び出してコンテンツを作成し、
 * contentId を返す。全処理は呼び出し元の {@code @Transactional} に参加する。</p>
 */
public interface AnnouncementChannelAdapter {

    /**
     * このアダプターが担当するソース種別を返す。
     *
     * @return ソース種別
     */
    AnnouncementSourceType getSourceType();

    /**
     * コンテンツを作成し、作成したコンテンツの ID を返す。
     *
     * @param content    汎用コンテンツリクエスト
     * @param scopeType  スコープ種別文字列（TEAM / ORGANIZATION）
     * @param scopeId    スコープ ID
     * @param visibility コンテンツ visibility（MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC）
     * @param userId     作成者ユーザー ID
     * @return 作成されたコンテンツの ID
     */
    Long createContent(AnnouncementContentRequest content, String scopeType, Long scopeId,
                       String visibility, Long userId);

    /**
     * コンテンツの URL を生成する。
     *
     * @param scopeType スコープ種別文字列（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @param contentId コンテンツ ID
     * @return コンテンツ URL（フロントエンドのルートパス）
     */
    String buildContentUrl(String scopeType, Long scopeId, Long contentId);
}
