package com.mannschaft.app.social.announcement.dto;

import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.audience.BroadcastAudienceSpec;
import com.mannschaft.app.social.announcement.audience.TargetGroupRange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F02.8 告知ウィザード実行リクエスト DTO。
 *
 * <p>コントローラー層で受け取るリクエストボディを表す。
 * バリデーション後に {@link com.mannschaft.app.social.announcement.BroadcastRequest} へ変換し
 * {@link com.mannschaft.app.social.announcement.AnnouncementBroadcastService} に渡す。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastRequestDto {

    /** 告知チャネル種別（必須）。 */
    @NotNull
    private AnnouncementChannel channel;

    /**
     * 告知対象ロール（必須、最大30文字）。
     * 値: MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC
     */
    @NotNull
    @Size(max = 30)
    private String targetRole;

    /**
     * 組織告知でのチーム絞り込み対象 ID リスト。
     * null = 全チーム対象。
     */
    private List<Long> targetTeamIds;

    /**
     * 「チームグループで選ぶ」の個別チェック（F01.2.1 §8.1。組織告知のみ。targetTeamIds とは併用不可）。
     */
    private List<UUID> targetGroupIds;

    /**
     * 「チームグループで選ぶ」の範囲（並び順で開始〜終了。F01.2.1 §8.1）。
     */
    private TargetGroupRange targetGroupRange;

    /**
     * 未分類のチームも宛先に含めるか（F01.2.1 §8.1）。
     */
    private Boolean includeUnassigned;

    /**
     * 範囲テンプレート ID（省略可）。
     * 指定した場合、スコープに紐づくテンプレートであることを検証する。
     */
    private Long templateId;

    /**
     * 優先度（NORMAL / IMPORTANT / URGENT）。
     * デフォルト NORMAL。MEMBER ロールが NORMAL 以外を指定した場合はエラー。
     */
    @Builder.Default
    private String priority = "NORMAL";

    /**
     * 表示期限（null = 期限なし）。
     */
    private LocalDateTime expiresAt;

    /** 告知コンテンツ情報（必須）。 */
    @NotNull
    @Valid
    private AnnouncementContentRequest content;

    /** 宛先項目だけを取り出す。 */
    public BroadcastAudienceSpec toAudienceSpec() {
        return new BroadcastAudienceSpec(
                targetTeamIds, targetGroupIds, targetGroupRange, includeUnassigned, templateId, targetRole);
    }
}
