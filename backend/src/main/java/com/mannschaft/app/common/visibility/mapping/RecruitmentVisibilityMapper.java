package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.recruitment.RecruitmentVisibility;

/**
 * F03.11 募集型予約 — {@link RecruitmentVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>マスター裁可 C-2 (2026-05-04): SUPPORTERS_ONLY は GUEST 以外の全認証メンバーを包含する
 * {@link StandardVisibility#SUPPORTERS_AND_ABOVE} に正規化する。
 */
public final class RecruitmentVisibilityMapper {

    private RecruitmentVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(RecruitmentVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            case SCOPE_ONLY -> StandardVisibility.MEMBERS_ONLY;
            case SUPPORTERS_ONLY -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
        };
    }
}
