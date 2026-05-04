package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;

/**
 * F04.9 Phase D 確認通知の未確認者一覧 — {@link UnconfirmedVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>CREATOR_AND_ADMIN は「送信者本人または ADMIN/DEPUTY_ADMIN」という個別条件のため CUSTOM 行きとする。
 */
public final class ConfirmableUnconfirmedVisibilityMapper {

    private ConfirmableUnconfirmedVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(UnconfirmedVisibility v) {
        return switch (v) {
            case HIDDEN -> StandardVisibility.PRIVATE;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (送信者本人 + ADMIN/DEPUTY_ADMIN の OR 条件)
            case CREATOR_AND_ADMIN -> StandardVisibility.CUSTOM;
            case ALL_MEMBERS -> StandardVisibility.MEMBERS_ONLY;
        };
    }
}
