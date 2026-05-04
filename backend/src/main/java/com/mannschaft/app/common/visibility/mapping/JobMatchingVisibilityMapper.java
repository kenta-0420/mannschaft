package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;

/**
 * F13.1 求人投稿 — {@link VisibilityScope} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>マスター裁可 C-2 (2026-05-04): TEAM_MEMBERS_SUPPORTERS は GUEST 以外の全認証メンバーを包含する
 * {@link StandardVisibility#SUPPORTERS_AND_ABOVE} に正規化する。
 */
public final class JobMatchingVisibilityMapper {

    private JobMatchingVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(VisibilityScope v) {
        return switch (v) {
            case TEAM_MEMBERS -> StandardVisibility.MEMBERS_ONLY;
            case TEAM_MEMBERS_SUPPORTERS -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (JOBBER ロール限定の閲覧制御 — §5.2 備考)
            case JOBBER_INTERNAL -> StandardVisibility.CUSTOM;
            case JOBBER_PUBLIC_BOARD -> StandardVisibility.PUBLIC;
            case ORGANIZATION_SCOPE -> StandardVisibility.ORGANIZATION_WIDE;
            case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
        };
    }
}
