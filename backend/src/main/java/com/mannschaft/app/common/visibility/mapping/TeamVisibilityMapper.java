package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.team.entity.TeamEntity;

/**
 * {@link TeamEntity.Visibility} を {@link StandardVisibility} に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / Phase D-3。</p>
 *
 * <p>マッピング（ロールベース設計）:
 * <ul>
 *   <li>{@link TeamEntity.Visibility#PUBLIC} → {@link StandardVisibility#PUBLIC}
 *       （未認証ユーザーも閲覧可）</li>
 *   <li>{@link TeamEntity.Visibility#GUESTS_AND_ABOVE} → {@link StandardVisibility#SCOPE_AFFILIATED}
 *       （GUEST 以上の所属メンバーすべてが閲覧可。直接所属ユーザー＋サポーター含む）</li>
 *   <li>{@link TeamEntity.Visibility#SUPPORTERS_AND_ABOVE} → {@link StandardVisibility#SUPPORTERS_AND_ABOVE}
 *       （サポーター以上のロールを持つメンバーが閲覧可）</li>
 *   <li>{@link TeamEntity.Visibility#MEMBERS_AND_ABOVE} → {@link StandardVisibility#MEMBERS_AND_ABOVE}
 *       （正規メンバー以上のロールを持つメンバーのみ閲覧可。サポーター・ゲストは除外）</li>
 * </ul>
 * </p>
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
            case GUESTS_AND_ABOVE -> StandardVisibility.SCOPE_AFFILIATED;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            case MEMBERS_AND_ABOVE -> StandardVisibility.MEMBERS_AND_ABOVE;
        };
    }
}
