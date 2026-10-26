package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.gallery.AlbumVisibility;

import java.util.EnumSet;
import java.util.Set;

/**
 * F09.x ギャラリー — {@link AlbumVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 */
public final class AlbumVisibilityMapper {

    private AlbumVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(AlbumVisibility v) {
        return switch (v) {
            // 挙動不変・名称正準化（W3）: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
            case ALL_MEMBERS -> StandardVisibility.SCOPE_AFFILIATED;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            // 挙動不変・名称正準化（W4）: ADMINS_AND_ABOVE = hasRoleOrAbove("ADMIN") = 旧 ADMINS_ONLY と同一判定。
            case ADMIN_ONLY -> StandardVisibility.ADMINS_AND_ABOVE;
        };
    }

    /**
     * CMP-028 Phase B: {@link #toStandard(AlbumVisibility)} の<strong>逆写像</strong>。
     * F00 が返した可視 {@link StandardVisibility} 集合を、SQL の {@code visibility IN (...)}
     * に渡せる機能固有 enum 集合へ変換する。
     *
     * <p><strong>単射性の確認</strong>: {@code ALL_MEMBERS → SCOPE_AFFILIATED}・
     * {@code SUPPORTERS_AND_ABOVE → SUPPORTERS_AND_ABOVE}・{@code ADMIN_ONLY →
     * ADMINS_AND_ABOVE} は 3 対 3 の一対一写像であり、{@link #toStandard(AlbumVisibility)}
     * は単射（injective）である。よって逆写像は曖昧さなく安全に定義できる。</p>
     *
     * <p><strong>注意</strong>: {@link AlbumVisibility} には {@code PUBLIC} に対応する値が
     * <strong>存在しない</strong>ため、{@code standardLevels} が {@code PUBLIC} のみ（非所属・未認証）
     * の場合、本メソッドは<strong>空集合</strong>を返す。呼び出し元は空集合を「SQL を発行せず
     * 空ページを返す」契機として扱うこと（{@code IN ()} は不正 SQL になるため）。</p>
     *
     * @param standardLevels {@code resolveVisibleLevels} が返した可視 {@link StandardVisibility} 集合
     * @return 対応する {@link AlbumVisibility} 集合（該当なしは空集合）
     */
    public static Set<AlbumVisibility> toFunctional(Set<StandardVisibility> standardLevels) {
        if (standardLevels == null || standardLevels.isEmpty()) {
            return Set.of();
        }
        EnumSet<AlbumVisibility> result = EnumSet.noneOf(AlbumVisibility.class);
        if (standardLevels.contains(StandardVisibility.SCOPE_AFFILIATED)) {
            result.add(AlbumVisibility.ALL_MEMBERS);
        }
        if (standardLevels.contains(StandardVisibility.SUPPORTERS_AND_ABOVE)) {
            result.add(AlbumVisibility.SUPPORTERS_AND_ABOVE);
        }
        if (standardLevels.contains(StandardVisibility.ADMINS_AND_ABOVE)) {
            result.add(AlbumVisibility.ADMIN_ONLY);
        }
        return Set.copyOf(result);
    }
}
