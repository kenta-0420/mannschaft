package com.mannschaft.app.chat;

/**
 * チャットチャンネルの種別。
 */
public enum ChannelType {

    /** チーム公開チャンネル */
    TEAM_PUBLIC,

    /** チーム非公開チャンネル */
    TEAM_PRIVATE,

    /** 組織公開チャンネル */
    ORG_PUBLIC,

    /** 組織非公開チャンネル */
    ORG_PRIVATE,

    /** ダイレクトメッセージ（1対1） */
    DM,

    /** グループダイレクトメッセージ（複数人） */
    GROUP_DM,

    /**
     * 村ロビー（F17.1 井戸端会議）。
     * villages.id を {@code chat_channels.village_id} に紐付けて一意に払い出される。
     * チーム/組織と独立した第三のスコープであり、{@code teamId}/{@code organizationId} は NULL。
     */
    VILLAGE_LOBBY,

    /**
     * イベント専用チャット。
     * {@code chat_channels.source_type="EVENT"}, {@code source_id=eventId} に紐付けて一意に払い出される。
     * イベント作成時に自動生成され、イベント完了・キャンセル時にアーカイブされる。
     */
    EVENT_CHAT,

    /**
     * 大会全体の連絡チャット（F08.7.1 連絡機能）。
     * {@code chat_channels.source_type="TOURNAMENT"}, {@code source_id=tournamentId} に紐付けて
     * 一意に払い出される。大会作成時に自動生成される。{@code team_id}/{@code organization_id} は NULL。
     * 桁: "TOURNAMENT_CHAT"=15字（隊0 で channel_type VARCHAR(30) 化済み）。
     */
    TOURNAMENT_CHAT,

    /**
     * 大会ディビジョンの連絡チャット（F08.7.1 連絡機能）。
     * {@code chat_channels.source_type="TOURNAMENT_DIVISION"}, {@code source_id=divisionId} に紐付けて
     * 一意に払い出される。ディビジョン作成時に自動生成される。{@code team_id}/{@code organization_id} は NULL。
     * 桁: "TOURNAMENT_DIVISION_CHAT"=24字（隊0 で channel_type VARCHAR(30) 化済み）。
     */
    TOURNAMENT_DIVISION_CHAT
}
