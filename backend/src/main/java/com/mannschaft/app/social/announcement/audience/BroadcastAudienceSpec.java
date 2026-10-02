package com.mannschaft.app.social.announcement.audience;

import java.util.List;
import java.util.UUID;

/**
 * 告知の宛先指定の入力（F01.2.1 §8.1）。broadcast と宛先プレビューで共通。
 *
 * <p>{@code null} は「指定なし」、空配列 {@code []} は「明示的に何も選ばなかった」を表し、両者を区別する
 * （空配列を「すべてのチーム」に倒すと、何も選ばなかった告知が全チームへ出てしまうため。空配列は 400）。</p>
 *
 * @param targetTeamIds     「チームを選ぶ」のチーム ID（null は指定なし）
 * @param targetGroupIds    「チームグループで選ぶ」の個別チェック（null は指定なし）
 * @param targetGroupRange  「チームグループで選ぶ」の範囲（null は指定なし）
 * @param includeUnassigned 未分類のチームも含めるか（null は false）
 * @param templateId        範囲テンプレート ID（null は指定なし）
 * @param targetRole        告知対象ロール（直属メンバー数の数え方に使う。null は MEMBERS_AND_ABOVE 扱い）
 */
public record BroadcastAudienceSpec(
        List<Long> targetTeamIds,
        List<UUID> targetGroupIds,
        TargetGroupRange targetGroupRange,
        Boolean includeUnassigned,
        Long templateId,
        String targetRole) {

    /** 「チームを選ぶ」の項目が送られたか（空配列を含む）。 */
    public boolean hasTeamItems() {
        return targetTeamIds != null;
    }

    /** 「チームグループで選ぶ」の項目（個別・範囲・未分類のいずれか）が送られたか（個別の空配列を含む）。 */
    public boolean hasGroupItems() {
        return targetGroupIds != null
                || targetGroupRange != null
                || Boolean.TRUE.equals(includeUnassigned);
    }

    /** 宛先項目を1つも送らずにテンプレートだけを指定したか。 */
    public boolean isTemplateOnly() {
        return templateId != null && !hasTeamItems() && !hasGroupItems();
    }
}
