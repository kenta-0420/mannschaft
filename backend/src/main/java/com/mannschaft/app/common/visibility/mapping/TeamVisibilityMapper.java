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
 *   <li>{@link TeamEntity.Visibility#PRIVATE} → {@link StandardVisibility#SCOPE_AFFILIATED}
 *       （招待制・非公開チーム。{@code TeamEntity} に {@code created_by} が存在しないため
 *       {@link StandardVisibility#PRIVATE}（作者本人のみ）へのマッピングは実質 fail-closed
 *       となり誰も閲覧できなくなる。チームの PRIVATE の意図は「メンバーだけ見える」であるため
 *       {@link StandardVisibility#SCOPE_AFFILIATED}（直接所属）を使用する）</li>
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
            // 挙動不変・名称正準化（W3）: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
            case PRIVATE -> StandardVisibility.SCOPE_AFFILIATED;
        };
    }
}
