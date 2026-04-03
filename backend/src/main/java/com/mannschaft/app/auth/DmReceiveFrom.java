package com.mannschaft.app.auth;

/**
 * DM受信制限設定。誰からのDMを受け取るかを制御する。
 */
public enum DmReceiveFrom {
    /** 誰からでもDMを受け取る（デフォルト） */
    ANYONE,
    /** 同じチームに所属するメンバーからのみ受け取る */
    TEAM_MEMBERS_ONLY,
    /** 連絡先に登録しているユーザーからのみ受け取る */
    CONTACTS_ONLY
}
