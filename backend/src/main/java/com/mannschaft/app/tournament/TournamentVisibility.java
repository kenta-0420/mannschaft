package com.mannschaft.app.tournament;

/**
 * 大会（順位・結果）の公開範囲設定。
 *
 * <p>F08.7 順位UI Wave0（マスター裁可済）で 2 値（旧 {@code PUBLIC / MEMBERS_ONLY}）から
 * 6 値へ拡張した。5 つは F00 正準 {@link com.mannschaft.app.common.visibility.StandardVisibility}
 * と同名にして {@link com.mannschaft.app.common.visibility.mapping.TournamentVisibilityMapper}
 * を恒等写像に近づけ、大会専用軸 {@link #PARTICIPANTS_ONLY} のみ正準に対応値が無いため
 * {@code StandardVisibility.CUSTOM} に写像し
 * {@link com.mannschaft.app.tournament.visibility.TournamentVisibilityResolver#evaluateCustom}
 * で個別判定する。</p>
 *
 * <p>新規大会の既定は {@link #PUBLIC}。</p>
 *
 * <p><strong>旧 {@code MEMBERS_ONLY} について</strong>: 既存データとの整合のため Flyway
 * （{@code V9.*__alter_tournaments_visibility_six_levels.sql}）で既存行を
 * {@link #SCOPE_AFFILIATED}（StandardVisibility doc の「旧 MEMBERS_ONLY 相当の正準値」）へ
 * 移行し、enum からは削除した。コード上の互換は Mapper 側で扱わない（DB に値が残らないため）。</p>
 */
public enum TournamentVisibility {

    /** 誰でも閲覧可能（未ログイン含む）。{@code StandardVisibility.PUBLIC}。 */
    PUBLIC,

    /** 応援者以上が閲覧可能。{@code StandardVisibility.SUPPORTERS_AND_ABOVE}。 */
    SUPPORTERS_AND_ABOVE,

    /** メンバー以上が閲覧可能（応援者除外）。{@code StandardVisibility.MEMBERS_AND_ABOVE}。 */
    MEMBERS_AND_ABOVE,

    /** 管理者のみ閲覧可能。{@code StandardVisibility.ADMINS_AND_ABOVE}。 */
    ADMINS_AND_ABOVE,

    /**
     * 主催組織に直接所属する全員が閲覧可能（応援者・ゲスト含む直接所属軸）。
     * {@code StandardVisibility.SCOPE_AFFILIATED}（旧 MEMBERS_ONLY 相当の正準値）。
     */
    SCOPE_AFFILIATED,

    /**
     * 参加チーム関係者のみ閲覧可能（大会専用軸）。
     *
     * <p>閲覧者が当該大会の参加チーム（{@code tournament_participants.team_id}）の
     * いずれかにアクティブメンバーとして所属しているかで判定する。正準に対応値が無いため
     * {@code StandardVisibility.CUSTOM} に写像し、Resolver の {@code evaluateCustom} で判定する。
     * 未認証は不可視（fail-closed）。</p>
     */
    PARTICIPANTS_ONLY
}
