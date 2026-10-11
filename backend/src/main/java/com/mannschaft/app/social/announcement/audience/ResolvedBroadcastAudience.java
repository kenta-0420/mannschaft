package com.mannschaft.app.social.announcement.audience;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 解決済みの告知の宛先（F01.2.1 §8.1〜§8.3）。{@link BroadcastAudienceResolver} だけが作る。
 *
 * <p>告知のトランザクションの外で解決し（組織・チーム・グループは別ドメインのため）、
 * {@code AnnouncementBroadcastService} はこの値を保存するだけにする。</p>
 *
 * @param mode              宛先の種類
 * @param targetTeamIds     「チームを選ぶ」の検証済みチーム ID（重複排除・指定順）。それ以外は空
 * @param groups            「チームグループで選ぶ」で範囲を展開した後のグループ（並び順）。それ以外は空
 * @param includeUnassigned 未分類のチームを含めるか
 * @param groupTeams        グループ ID → 送信時に ACTIVE で所属していたチーム（スナップショットの元。並び順）
 * @param resolvedTeamIds   宛先チーム（重複排除。ALL では空）
 * @param directMemberCount 直属メンバー数（送信者本人を除く組織メンバー。ALL では 0）
 * @param targetAudience    {@code target_audience} に残す記録（ALL では null）
 * @param warnings          警告（テンプレートのグループを除外したときの {@code TEMPLATE_GROUPS_REMOVED:N} など。無ければ空）
 */
public record ResolvedBroadcastAudience(
        Mode mode,
        List<Long> targetTeamIds,
        List<TargetAudience.GroupRef> groups,
        boolean includeUnassigned,
        Map<UUID, List<Long>> groupTeams,
        List<Long> resolvedTeamIds,
        int directMemberCount,
        TargetAudience targetAudience,
        List<String> warnings) {

    /** 宛先の種類。 */
    public enum Mode {
        /** すべてのチーム（絞り込みなし。従来どおり）。TEAM スコープの告知もこれ。 */
        ALL,
        /** チームを選ぶ。 */
        TEAMS,
        /** チームグループで選ぶ。 */
        GROUPS
    }

    /** 絞り込みなし。 */
    public static ResolvedBroadcastAudience unrestricted() {
        return new ResolvedBroadcastAudience(Mode.ALL, List.of(), List.of(), false, Map.of(), List.of(), 0, null, List.of());
    }

    /** 警告を差し替えた複製。 */
    public ResolvedBroadcastAudience withWarnings(List<String> newWarnings) {
        return new ResolvedBroadcastAudience(mode, targetTeamIds, groups, includeUnassigned, groupTeams,
                resolvedTeamIds, directMemberCount, targetAudience, List.copyOf(newWarnings));
    }

    /** グループ ID（並び順）。 */
    public List<UUID> groupIds() {
        return groups.stream().map(TargetAudience.GroupRef::id).toList();
    }
}
