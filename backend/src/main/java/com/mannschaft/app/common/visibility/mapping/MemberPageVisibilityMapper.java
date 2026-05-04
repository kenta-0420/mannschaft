package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.member.PageVisibility;

/**
 * {@link com.mannschaft.app.member.PageVisibility} を {@link StandardVisibility}
 * に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 */
public final class MemberPageVisibilityMapper {

    private MemberPageVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link PageVisibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(PageVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_ONLY;
        };
    }
}
