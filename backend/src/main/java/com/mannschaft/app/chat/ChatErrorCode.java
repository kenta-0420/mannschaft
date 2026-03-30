package com.mannschaft.app.chat;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.2 チャット機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

    /** チャンネルが見つからない */
    CHANNEL_NOT_FOUND("CHAT_001", "チャンネルが見つかりません", Severity.WARN),

    /** メッセージが見つからない */
    MESSAGE_NOT_FOUND("CHAT_002", "メッセージが見つかりません", Severity.WARN),

    /** チャンネルメンバーが見つからない */
    MEMBER_NOT_FOUND("CHAT_003", "チャンネルメンバーが見つかりません", Severity.WARN),

    /** 既にチャンネルメンバー */
    ALREADY_MEMBER("CHAT_004", "既にチャンネルに参加しています", Severity.WARN),

    /** チャンネルへのアクセス権なし */
    CHANNEL_ACCESS_DENIED("CHAT_005", "このチャンネルへのアクセス権がありません", Severity.WARN),

    /** メッセージ編集権限なし */
    MESSAGE_EDIT_DENIED("CHAT_006", "このメッセージを編集する権限がありません", Severity.WARN),

    /** メッセージ削除権限なし */
    MESSAGE_DELETE_DENIED("CHAT_007", "このメッセージを削除する権限がありません", Severity.WARN),

    /** チャンネル名重複 */
    CHANNEL_NAME_DUPLICATE("CHAT_008", "同名のチャンネルが既に存在します", Severity.WARN),

    /** アーカイブ済みチャンネルへの操作 */
    CHANNEL_ARCHIVED("CHAT_009", "アーカイブ済みのチャンネルには操作できません", Severity.WARN),

    /** ブックマーク重複 */
    BOOKMARK_ALREADY_EXISTS("CHAT_010", "既にブックマーク済みです", Severity.WARN),

    /** リアクション重複 */
    REACTION_ALREADY_EXISTS("CHAT_011", "既に同じリアクションを付けています", Severity.WARN),

    /** リアクションが見つからない */
    REACTION_NOT_FOUND("CHAT_012", "リアクションが見つかりません", Severity.WARN),

    /** チャンネルロール変更権限なし */
    ROLE_CHANGE_DENIED("CHAT_013", "ロール変更の権限がありません", Severity.WARN),

    /** オーナーは退出不可 */
    OWNER_CANNOT_LEAVE("CHAT_014", "チャンネルオーナーは退出できません。先にオーナーを移譲してください", Severity.WARN),

    /** 添付ファイルサイズ超過 */
    ATTACHMENT_SIZE_EXCEEDED("CHAT_015", "添付ファイルのサイズ上限を超えています", Severity.ERROR),

    /** DM以外のチャンネルはグループDMに変換できない */
    CHANNEL_NOT_DM("CHAT_016", "DMチャンネルのみグループDMに変換できます", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
