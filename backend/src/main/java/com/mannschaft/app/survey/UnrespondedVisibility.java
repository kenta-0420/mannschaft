package com.mannschaft.app.survey;

/**
 * 未回答者一覧の公開範囲。
 * F05.4 §7.2 「未回答者一覧の可視化」で導入。
 *
 * <ul>
 *   <li>{@link #HIDDEN} — メンバーには表示しない（管理者・作成者のみ閲覧可）</li>
 *   <li>{@link #CREATOR_AND_ADMIN} — 作成者・ADMIN/DEPUTY_ADMIN・survey_result_viewers のみ（既存挙動と同等のデフォルト）</li>
 *   <li>{@link #ALL_MEMBERS} — スコープ内の対象メンバー全員に未回答者リストを公開</li>
 * </ul>
 */
public enum UnrespondedVisibility {

    /** 管理者・作成者のみ。メンバーには非公開。 */
    HIDDEN,

    /** 作成者・ADMIN+/survey_result_viewers のみ（デフォルト・既存挙動）。 */
    CREATOR_AND_ADMIN,

    /** スコープ内の対象メンバー全員に公開。 */
    ALL_MEMBERS
}
