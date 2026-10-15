package com.mannschaft.app.tournament;

/**
 * 連絡スペースのスコープ種別（F08.7.1 連絡機能）。
 *
 * <p>連絡単位＝大会全体（{@link #TOURNAMENT}）と各ディビジョン（{@link #TOURNAMENT_DIVISION}）の二段。
 * {@code tournament_contact_space.scope_type} に対応する。</p>
 */
public enum ContactSpaceScopeType {

    /** 大会全体。{@code scope_id} は {@code tournaments.id}。 */
    TOURNAMENT,

    /** ディビジョン。{@code scope_id} は {@code tournament_divisions.id}。 */
    TOURNAMENT_DIVISION
}
