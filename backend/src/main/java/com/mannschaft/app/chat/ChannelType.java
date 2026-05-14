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
    VILLAGE_LOBBY
}
