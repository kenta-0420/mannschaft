package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.gallery.AlbumVisibility;

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
            case ALL_MEMBERS -> StandardVisibility.MEMBERS_ONLY;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            case ADMIN_ONLY -> StandardVisibility.ADMINS_ONLY;
        };
    }
}
