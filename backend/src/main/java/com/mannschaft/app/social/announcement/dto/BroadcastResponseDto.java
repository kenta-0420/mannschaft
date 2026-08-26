package com.mannschaft.app.social.announcement.dto;

import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.BroadcastResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F02.8 告知ウィザード実行レスポンス DTO。
 *
 * <p>{@link BroadcastResult} をクライアントに返却する形式に変換した DTO。
 * {@link #from(BroadcastResult)} ファクトリメソッドを使って生成する。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastResponseDto {

    /** 作成されたお知らせフィード ID。 */
    private Long announcementFeedId;

    /** 使用されたチャネル種別。 */
    private AnnouncementChannel channel;

    /** 作成されたコンテンツ ID。 */
    private Long contentId;

    /** コンテンツの URL（フロントエンドでリンク生成に使用）。 */
    private String contentUrl;

    /** 告知対象ロール（MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC）。 */
    private String targetRole;

    /** 組織告知でのチーム絞り込み（null = 全チーム対象）。 */
    private List<Long> targetTeamIds;

    /** 優先度（NORMAL / IMPORTANT / URGENT）。 */
    private String priority;

    /** 作成日時。 */
    private LocalDateTime createdAt;

    /**
     * {@link BroadcastResult} からこの DTO を生成するファクトリメソッド。
     *
     * @param result サービス層が返す告知実行結果
     * @return レスポンス DTO
     */
    public static BroadcastResponseDto from(BroadcastResult result) {
        return BroadcastResponseDto.builder()
                .announcementFeedId(result.getAnnouncementFeedId())
                .channel(result.getChannel())
                .contentId(result.getContentId())
                .contentUrl(result.getContentUrl())
                .targetRole(result.getTargetRole())
                .targetTeamIds(result.getTargetTeamIds())
                .priority(result.getPriority())
                .createdAt(result.getCreatedAt())
                .build();
    }
}
