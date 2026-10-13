package com.mannschaft.app.social.announcement;

import com.mannschaft.app.social.announcement.audience.TargetAudience;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

    /** グループ宛て: 範囲を展開したチームグループ ID（並び順。グループ宛てでなければ null）。 */
    private final List<UUID> targetGroupIds;

    /** グループ宛て: 未分類のチームを含めたか。 */
    private final boolean includeUnassigned;

    /** 送信時の宛先指定の記録（絞り込みなしなら null。F01.2.1 AC-H17）。 */
    private final TargetAudience targetAudience;

    /** 警告（テンプレートのグループを除外したときの {@code TEMPLATE_GROUPS_REMOVED:N} など。無ければ空）。 */
    @Builder.Default
    private final List<String> warnings = List.of();

    /** 優先度。 */
    private final String priority;

    /** 作成日時。 */
    private final LocalDateTime createdAt;
}
