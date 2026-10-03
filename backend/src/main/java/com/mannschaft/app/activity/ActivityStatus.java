package com.mannschaft.app.activity;

/**
 * 活動記録のライフサイクル状態（F06.4 下書き対応）。
 *
 * <p>アンケート（{@code com.mannschaft.app.survey.SurveyStatus}）の DRAFT/PUBLISHED を金型とする。
 * DRAFT は作成者・SystemAdmin のみ閲覧可（スコープ一覧には非表示）、PUBLISHED は
 * visibility 評価に進む（F00 {@code ContentStatus} へ {@code ActivityStatusMapper} で正規化）。</p>
 */
public enum ActivityStatus {

    /** 作成中・未公開。作成者・SystemAdmin のみ可視。 */
    DRAFT,

    /** 公開済み。visibility 評価に進む（従来の活動記録はすべてこの状態）。 */
    PUBLISHED
}
