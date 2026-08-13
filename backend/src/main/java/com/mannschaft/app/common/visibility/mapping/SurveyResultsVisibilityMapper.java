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
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // （配信母集団条件 — 公開後は「配信された者」が中間集計を閲覧可）。
            //
            // ALWAYS の可視範囲は「配信母集団と同一」であり、これは StandardVisibility の
            // どのラダー段・所属軸でも表現できない:
            //   - TARGETED は survey_targets 名簿そのものが母集団
            //   - ORGANIZATION × ALL は「組織直属 ∪ 配下 ACTIVE チーム」の再帰母集団かつ
            //     include_supporters トグルで応援者の要否が変わる
            // 所属軸（SCOPE_AFFILIATED / ORGANIZATION_AND_DESCENDANTS）で近似すると、
            // 配信されていない者に見えたり（漏洩）、配信された者が 403 になったり（機能不全）する。
            // よって配信母集団の述語そのものを Resolver で評価する。
            case ALWAYS -> StandardVisibility.CUSTOM;
        };
    }
}
