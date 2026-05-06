package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.team.entity.TeamEntity;

/**
 * {@link TeamEntity.Visibility} を {@link StandardVisibility} に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / Phase D-3。</p>
 *
 * <p>マッピング:
 * <ul>
 *   <li>{@link TeamEntity.Visibility#PUBLIC} → {@link StandardVisibility#PUBLIC}
 *       （未認証ユーザーも閲覧可）</li>
 *   <li>{@link TeamEntity.Visibility#ORGANIZATION_ONLY} → {@link StandardVisibility#ORGANIZATION_WIDE}
 *       （スコープの親 ORG 所属メンバーまで公開。親 ORG 連鎖ガードは §11.6 参照）</li>
 *   <li>{@link TeamEntity.Visibility#PRIVATE} → {@link StandardVisibility#PRIVATE}
 *       （作成者本人のみ。チームに作成者概念がないため実質 fail-closed）</li>
 * </ul>
 */
public final class TeamVisibilityMapper {

    private TeamVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link TeamEntity.Visibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(TeamEntity.Visibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            case ORGANIZATION_ONLY -> StandardVisibility.ORGANIZATION_WIDE;
            case PRIVATE -> StandardVisibility.PRIVATE;
        };
    }
}
