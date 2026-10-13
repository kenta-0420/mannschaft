package com.mannschaft.app.social.announcement.dto;

import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.audience.BroadcastAudienceSpec;
import com.mannschaft.app.social.announcement.audience.TargetGroupRange;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 宛先プレビューのリクエスト（F01.2.1 §10.9）。broadcast と同じ宛先項目と、push の判定に使う channel・targetRole。
 *
 * <p>broadcast の本文（content・priority など）を丸ごと送ってもよい（未知の項目は無視される）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AudiencePreviewRequestDto {

    /** 告知チャネル（push を出すかの判定に使う。省略時は push なし扱い）。 */
    private AnnouncementChannel channel;

    /** 告知対象ロール（省略可）。 */
    @Size(max = 30)
    private String targetRole;

    /** 「チームを選ぶ」のチーム ID。 */
    private List<Long> targetTeamIds;

    /** 「チームグループで選ぶ」の個別チェック。 */
    private List<UUID> targetGroupIds;

    /** 「チームグループで選ぶ」の範囲。 */
    private TargetGroupRange targetGroupRange;

    /** 未分類のチームも含めるか。 */
    private Boolean includeUnassigned;

    /** 範囲テンプレート ID（テンプレートの解決は F01.2.1 §8.6。省略可）。 */
    private Long templateId;

    /** 宛先項目だけを取り出す。 */
    public BroadcastAudienceSpec toSpec() {
        return new BroadcastAudienceSpec(
                targetTeamIds, targetGroupIds, targetGroupRange, includeUnassigned, templateId, targetRole);
    }
}
