package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.survey.ResultsVisibility;

/**
 * F05.4 アンケート結果 — {@link ResultsVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>AFTER_RESPONSE / AFTER_CLOSE は時間軸の条件、VIEWERS_ONLY は限定リスト判定が必要なため、
 * StandardVisibility では表現できず CUSTOM 行きとする。
 */
public final class SurveyResultsVisibilityMapper {

    private SurveyResultsVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(ResultsVisibility v) {
        return switch (v) {
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (時間軸条件 — 回答後のみ閲覧可)
            case AFTER_RESPONSE -> StandardVisibility.CUSTOM;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (時間軸条件 — 締切後のみ閲覧可)
            case AFTER_CLOSE -> StandardVisibility.CUSTOM;
            // 挙動不変・名称正準化（W4）: ADMINS_AND_ABOVE = hasRoleOrAbove("ADMIN") = 旧 ADMINS_ONLY と同一判定。
            case ADMINS_ONLY -> StandardVisibility.ADMINS_AND_ABOVE;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (限定リスト — survey_result_viewers のみ閲覧可)
            case VIEWERS_ONLY -> StandardVisibility.CUSTOM;
            // ALWAYS は時間条件を持たない「スコープ所属者なら常時可視」であり、
            // 標準ラダーの所属軸 SCOPE_AFFILIATED でそのまま表現できるため CUSTOM に流さない
            // （§5.1.4「新規に初手から CUSTOM を選ばない」）。SUPPORTER を含む所属軸を採るのは
            // 配信母集団が includeSupporters で応援者まで広がりうるためで、
            // 「配信対象スコープの会員全員が中間集計を見られる」という設計意図と一致する。
            case ALWAYS -> StandardVisibility.SCOPE_AFFILIATED;
        };
    }
}
