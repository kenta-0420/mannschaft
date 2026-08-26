package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.common.visibility.StandardVisibility;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@link com.mannschaft.app.activity.ActivityVisibility} を {@link StandardVisibility}
 * に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 */
public final class ActivityVisibilityMapper {

    private ActivityVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link ActivityVisibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(ActivityVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            // 内輪判定（W5）: 設計書 F06.4 §「権限と役割」で SUPPORTER は「公開記録の閲覧のみ」と
            // 明記され、MEMBERS_ONLY 記録は応援者に見せない内輪。よって応援者除外の
            // MEMBERS_AND_ABOVE (= hasRoleOrAbove MEMBER) へ締める（挙動変更: SUPPORTER は
            // MEMBERS_ONLY の活動記録を閲覧できなくなる）。
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_AND_ABOVE;
        };
    }

    /**
     * CMP-028 Phase B: {@link #toStandard(ActivityVisibility)} の<strong>逆写像</strong>。
     * F00 が返した可視 {@link StandardVisibility} 集合を、SQL の {@code visibility IN (...)}
     * に渡せる機能固有 enum 集合へ変換する。
     *
     * <p><strong>単射性の確認</strong>: {@code PUBLIC → PUBLIC}・{@code MEMBERS_ONLY →
     * MEMBERS_AND_ABOVE} は 2 対 2 の一対一写像であり、{@link #toStandard(ActivityVisibility)}
     * は単射（injective）である。よって逆写像は曖昧さなく安全に定義できる。</p>
     *
     * @param standardLevels {@code resolveVisibleLevels} が返した可視 {@link StandardVisibility} 集合
     * @return 対応する {@link ActivityVisibility} 集合（該当なしは空集合）
     */
    public static Set<ActivityVisibility> toFunctional(Set<StandardVisibility> standardLevels) {
        if (standardLevels == null || standardLevels.isEmpty()) {
            return Set.of();
        }
        EnumSet<ActivityVisibility> result = EnumSet.noneOf(ActivityVisibility.class);
        if (standardLevels.contains(StandardVisibility.PUBLIC)) {
            result.add(ActivityVisibility.PUBLIC);
        }
        if (standardLevels.contains(StandardVisibility.MEMBERS_AND_ABOVE)) {
            result.add(ActivityVisibility.MEMBERS_ONLY);
        }
        return Set.copyOf(result);
    }
}
