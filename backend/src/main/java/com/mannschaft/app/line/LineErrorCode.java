package com.mannschaft.app.line;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * LINE/SNS機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum LineErrorCode implements ErrorCode {

    /** LINE BOT設定が見つからない */
    LINE_001("LINE_001", "LINE BOT設定が見つかりません", Severity.WARN),

    /** LINE BOT設定が既に存在する */
    LINE_002("LINE_002", "このスコープには既にLINE BOT設定が存在します", Severity.WARN),

    /** Webhook シークレット不一致 */
    LINE_003("LINE_003", "Webhook認証に失敗しました", Severity.WARN),

    /** テストメッセージ送信失敗 */
    LINE_004("LINE_004", "テストメッセージの送信に失敗しました", Severity.ERROR),

    /** ユーザーLINE連携が見つからない */
    LINE_005("LINE_005", "LINE連携情報が見つかりません", Severity.WARN),

    /** ユーザーLINE連携が既に存在する */
    LINE_006("LINE_006", "既にLINEアカウントが連携されています", Severity.WARN),

    /** SNSフィード設定が見つからない */
    LINE_007("LINE_007", "SNSフィード設定が見つかりません", Severity.WARN),

    /** SNSフィード設定の重複 */
    LINE_008("LINE_008", "同一プロバイダーのフィード設定が既に存在します", Severity.WARN),

    /** SNSフィードプレビュー取得失敗 */
    LINE_009("LINE_009", "SNSフィードの取得に失敗しました", Severity.ERROR),

    /** 無効なスコープ種別 */
    LINE_010("LINE_010", "無効なスコープ種別が指定されました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
