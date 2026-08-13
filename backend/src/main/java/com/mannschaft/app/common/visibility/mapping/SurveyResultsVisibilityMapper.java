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
            // ALWAYS は時間条件を持たない「配信対象スコープの所属者なら常時可視」であり、
            // 標準ラダーの所属軸で表現できるため CUSTOM に流さない（§5.1.4「初手から CUSTOM を選ばない」）。
            //
            // ここで返す SCOPE_AFFILIATED は <b>TEAM スコープの基準値</b>である。
            // TEAM × ALL 配信の母集団は user_roles の当該スコープ行すべて
            // （SurveyResultService#resolveUniverseUserIds → UserRoleRepository#findUserIdsByScope）であり、
            // ロール閾値を持たない直接所属軸 = SCOPE_AFFILIATED と一致する。
            //
            // ORGANIZATION スコープでは配信母集団が「組織直属 ∪ 配下 ACTIVE チームのメンバー」まで
            // 再帰展開される（設計書 F05.4 の distribution_mode 備考）ため、SCOPE_AFFILIATED（直接所属のみ）
            // では配下チームのみ所属のユーザーに「アンケートは届くのに結果だけ 403」が生じる。
            // その是正は scope を持つ SurveyVisibilityResolver#adjustLevel が
            // ORGANIZATION_AND_DESCENDANTS（下向き再帰・既存軸）へ昇格させることで行う
            // （機能側 enum は scope を持たないため、本 Mapper では表現できない）。
            case ALWAYS -> StandardVisibility.SCOPE_AFFILIATED;
        };
    }
}
