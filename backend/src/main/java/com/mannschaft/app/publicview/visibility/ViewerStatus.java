package com.mannschaft.app.publicview.visibility;

/**
 * 公開ページ閲覧者の立場区分。
 *
 * <p>F19.1 §3 用語定義 / §4.6.1 開示マトリクスで用いる、投稿者識別段階開示の判定軸。
 * 値は対象スコープ（チーム / 組織）に対する viewer の関係性を表す。</p>
 *
 * <ul>
 *   <li>{@link #ANONYMOUS}: 未ログインの閲覧者。{@code Authentication} が anonymous または認証情報なし</li>
 *   <li>{@link #NON_MEMBER}: ログイン済みだが対象スコープに所属していない閲覧者</li>
 *   <li>{@link #SUPPORTER}: 対象スコープに {@code role_kind = 'SUPPORTER'} で所属する閲覧者</li>
 *   <li>{@link #MEMBER}: 対象スコープに {@code role_kind = 'MEMBER'} (ADMIN / DEPUTY_ADMIN を含む) で所属する閲覧者</li>
 *   <li>{@link #SELF}: 投稿の {@code author_id} と一致する本人閲覧者</li>
 *   <li>{@link #SYSTEM_ADMIN}: プラットフォーム管理者（システム横断ロール）</li>
 * </ul>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §3 / §4.6 / §7.1</p>
 */
public enum ViewerStatus {

    /** 未ログイン閲覧者。 */
    ANONYMOUS,

    /** ログイン済み・非メンバー（別スコープへの所属を含む）。 */
    NON_MEMBER,

    /** 対象スコープの SUPPORTER。 */
    SUPPORTER,

    /** 対象スコープの MEMBER（ADMIN / DEPUTY_ADMIN を含む）。 */
    MEMBER,

    /** 投稿の本人。 */
    SELF,

    /** システム管理者。 */
    SYSTEM_ADMIN
}
