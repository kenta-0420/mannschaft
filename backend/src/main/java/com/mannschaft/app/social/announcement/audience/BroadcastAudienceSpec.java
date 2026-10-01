package com.mannschaft.app.social.announcement.audience;

import java.util.List;
import java.util.UUID;

/**
 * 告知の宛先指定の入力（F01.2.1 §8.1）。broadcast と宛先プレビューで共通。
 *
 * @param targetTeamIds     「チームを選ぶ」のチーム ID（null・空は指定なし）
 * @param targetGroupIds    「チームグループで選ぶ」の個別チェック（null・空は指定なし）
 * @param targetGroupRange  「チームグループで選ぶ」の範囲（null は指定なし）
 * @param includeUnassigned 未分類のチームも含めるか（null は false）
 */
public record BroadcastAudienceSpec(
        List<Long> targetTeamIds,
        List<UUID> targetGroupIds,
        TargetGroupRange targetGroupRange,
        Boolean includeUnassigned) {

    /** 「チームを選ぶ」の指定があるか。 */
    public boolean hasTeamItems() {
        return targetTeamIds != null && !targetTeamIds.isEmpty();
    }

    /** 「チームグループで選ぶ」の項目（個別・範囲・未分類のいずれか）が指定されているか。 */
    public boolean hasGroupItems() {
        return (targetGroupIds != null && !targetGroupIds.isEmpty())
                || targetGroupRange != null
                || Boolean.TRUE.equals(includeUnassigned);
    }
}
