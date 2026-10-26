package com.mannschaft.app.tournament;

/**
 * 連絡スペースの種別（F08.7.1 連絡機能）。
 *
 * <p>各スコープに掲示板（{@link #BULLETIN}）とチャット（{@link #CHAT}）の両方を払い出す。
 * {@code tournament_contact_space.space_kind} に対応する。</p>
 */
public enum ContactSpaceKind {

    /** 掲示板スペース。{@code ref_id} は {@code bulletin_categories.id}（代表カテゴリ）。 */
    BULLETIN,

    /** チャットスペース。{@code ref_id} は {@code chat_channels.id}。 */
    CHAT
}
