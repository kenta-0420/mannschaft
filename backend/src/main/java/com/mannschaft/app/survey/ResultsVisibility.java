package com.mannschaft.app.survey;

/**
 * 結果公開設定。アンケート結果の閲覧可能タイミングを表す。
 *
 * <p>設計書: {@code docs/features/F05.4_survey_vote.md} §「結果公開設定」。</p>
 */
public enum ResultsVisibility {

    /** 回答済みユーザーのみ結果を閲覧可（時間軸ではなく回答有無で判定）。 */
    AFTER_RESPONSE,

    /** 締切後（CLOSED もしくは expiresAt 経過後）のみ、配信対象スコープの所属者が閲覧可。 */
    AFTER_CLOSE,

    /** ADMIN ロール以上のみ閲覧可。 */
    ADMINS_ONLY,

    /** {@code survey_result_viewers} 名簿に登録されたユーザーのみ閲覧可（ロール閾値とは直交）。 */
    VIEWERS_ONLY,

    /**
     * 公開（{@link SurveyStatus#PUBLISHED}）時点から締切前も含め、
     * 配信対象スコープの所属者が中間集計を常時閲覧可。
     *
     * <p>{@link #AFTER_CLOSE} から時間制約のみを外した値である。
     * 「誰でも」ではなく<b>スコープ所属者のみ</b>であり、スコープ外・他テナントには不可視。
     * また {@link SurveyStatus#DRAFT}（未公開）では status 軸で弾かれる。</p>
     */
    ALWAYS
}
