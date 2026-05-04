package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.survey.UnrespondedVisibility;

/**
 * F05.4 アンケート未回答者一覧 — {@link UnrespondedVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>CREATOR_AND_ADMIN は「作成者または ADMIN」という個別条件のため CUSTOM 行きとする。
 */
public final class SurveyUnrespondedVisibilityMapper {

    private SurveyUnrespondedVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(UnrespondedVisibility v) {
        return switch (v) {
            case HIDDEN -> StandardVisibility.PRIVATE;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (作成者または ADMIN の OR 条件、survey_result_viewers も含む)
            case CREATOR_AND_ADMIN -> StandardVisibility.CUSTOM;
            case ALL_MEMBERS -> StandardVisibility.MEMBERS_ONLY;
        };
    }
}
