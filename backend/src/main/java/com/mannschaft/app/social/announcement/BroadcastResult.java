package com.mannschaft.app.social.announcement;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F02.8 告知ウィザード実行結果 DTO。
 *
 * <p>{@link AnnouncementBroadcastService#broadcast(BroadcastRequest)} の返却値。
 * コントローラー層が API レスポンス DTO に変換して返す。</p>
 */
@Getter
@Builder
public class BroadcastResult {

    /** 作成されたお知らせフィード ID。 */
    private final Long announcementFeedId;

    /** 使用されたチャネル種別。 */
    private final AnnouncementChannel channel;

    /** 作成されたコンテンツ ID。 */
    private final Long contentId;

    /** コンテンツの URL（フロントエンドでリンク生成に使用）。 */
    private final String contentUrl;

    /** 告知対象ロール。 */
    private final String targetRole;

    /** 組織告知でのチーム絞り込み（null = 全チーム対象）。 */
    private final List<Long> targetTeamIds;

    /** 優先度。 */
    private final String priority;

    /** 作成日時。 */
    private final LocalDateTime createdAt;
}
