package com.mannschaft.app.social.announcement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F02.8 告知ウィザード実行リクエストの Service 向け内部 DTO。
 *
 * <p>コントローラー層がリクエストボディ・パスパラメーター・認証情報を集約し、
 * {@link AnnouncementBroadcastService#broadcast(BroadcastRequest)} に渡す。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastRequest {

    /** 告知チャネル種別。 */
    private AnnouncementChannel channel;

    /** 告知対象ロール（MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC）。 */
    private String targetRole;

    /** 組織告知でのチーム絞り込み。null = 全チーム対象。 */
    private List<Long> targetTeamIds;

    /** 範囲テンプレート ID（null 可）。 */
    private Long templateId;

    /** 優先度（NORMAL / IMPORTANT / URGENT）。デフォルト NORMAL。 */
    @Builder.Default
    private String priority = "NORMAL";

    /** 表示期限（null = 期限なし）。 */
    private LocalDateTime expiresAt;

    /** 告知コンテンツ情報。 */
    private AnnouncementContentRequest content;

    /** 告知実行ユーザー ID。 */
    private Long callerUserId;

    /** スコープ種別文字列（TEAM / ORGANIZATION）。 */
    private String scopeType;

    /** スコープ ID（チーム ID または組織 ID）。 */
    private Long scopeId;
}
