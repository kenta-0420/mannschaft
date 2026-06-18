package com.mannschaft.app.event.entity;

/**
 * イベント公開範囲を表す列挙型。
 *
 * <p>可視性ラダー統一（#1341）で {@link com.mannschaft.app.common.visibility.StandardVisibility}
 * は旧 {@code MEMBERS_ONLY/ADMINS_ONLY} を新ラダー
 * （{@code PUBLIC > SUPPORTERS_AND_ABOVE > MEMBERS_AND_ABOVE > ADMINS_AND_ABOVE}）＋直接所属軸
 * {@code SCOPE_AFFILIATED} に統一した。FE のイベント可視性 UI は正準ラダーの値名
 * （例: {@code MEMBERS_AND_ABOVE}）をそのまま送るため、cms（{@link com.mannschaft.app.cms.Visibility}）・
 * tournament（{@link com.mannschaft.app.tournament.TournamentVisibility}）に倣って event enum へも
 * 新ラダー値名を追加し、{@code EventVisibility.valueOf(...)} で受理できるようにする。
 * StandardVisibility への写像は
 * {@link com.mannschaft.app.common.visibility.mapping.EventVisibilityMapper} に一元化する。</p>
 *
 * <p>旧 {@link #MEMBERS_ONLY} は既存データ・既存呼び出しとの互換のため残置し、
 * Mapper で {@code StandardVisibility.MEMBERS_AND_ABOVE} へ写像する（新 {@link #MEMBERS_AND_ABOVE}
 * と同一の可視範囲）。</p>
 */
public enum EventVisibility {
    /** 外部（一般）公開 */
    PUBLIC,
    /** サポーター以上に公開 */
    SUPPORTERS_AND_ABOVE,
    /** メンバーのみ（互換残置）。{@code StandardVisibility.MEMBERS_AND_ABOVE} へ写像。 */
    MEMBERS_ONLY,
    /** メンバー以上が閲覧可能（応援者除外・新ラダー）。{@code StandardVisibility.MEMBERS_AND_ABOVE}。 */
    MEMBERS_AND_ABOVE,
    /** 管理者以上のみ閲覧可能（新ラダー）。{@code StandardVisibility.ADMINS_AND_ABOVE}。 */
    ADMINS_AND_ABOVE,
    /**
     * スコープへの直接所属者のみ閲覧可能（応援者・ゲスト含む直接所属軸・旧 MEMBERS_ONLY 相当の正準値）。
     * {@code StandardVisibility.SCOPE_AFFILIATED}。
     */
    SCOPE_AFFILIATED
}
