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
    GROUP_DM
}
