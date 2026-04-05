package com.mannschaft.app.contact;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 連絡先機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ContactErrorCode implements ErrorCode {

    /** ハンドル形式不正 */
    CONTACT_001("CONTACT_001", "ハンドルは小文字英数字・アンダースコア・ハイフンで3〜30文字にしてください", Severity.WARN),

    /** ハンドル重複 */
    CONTACT_002("CONTACT_002", "このハンドルは既に使用されています", Severity.WARN),

    /** ハンドル予約語 */
    CONTACT_003("CONTACT_003", "このハンドルは使用できません", Severity.WARN),

    /** 自分自身への申請 */
    CONTACT_004("CONTACT_004", "自分自身に申請することはできません", Severity.WARN),

    /** 既に連絡先 */
    CONTACT_005("CONTACT_005", "既に連絡先に登録されています", Severity.WARN),

    /** 申請が見つからない */
    CONTACT_006("CONTACT_006", "申請が見つかりません", Severity.WARN),

    /** 申請操作権限なし */
    CONTACT_007("CONTACT_007", "この申請を操作する権限がありません", Severity.WARN),

    /** REJECTED後72時間以内の再申請 */
    CONTACT_008("CONTACT_008", "拒否から72時間以内は再申請できません", Severity.WARN),

    /** 24時間以内の同一相手への申請 */
    CONTACT_009("CONTACT_009", "同じ相手への申請は24時間に1回までです", Severity.WARN),

    /** 申請事前拒否が見つからない */
    CONTACT_010("CONTACT_010", "事前拒否設定が見つかりません", Severity.WARN),

    /** 申請事前拒否重複 */
    CONTACT_011("CONTACT_011", "既に事前拒否リストに登録されています", Severity.WARN),

    /** トークンが見つからない・無効 */
    CONTACT_012("CONTACT_012", "招待リンクが無効または期限切れです", Severity.WARN),

    /** 自分が発行したトークンで自分が追加しようとした */
    CONTACT_013("CONTACT_013", "自分が発行した招待リンクは使用できません", Severity.WARN),

    /** トークンが見つからない（オーナーチェック用） */
    CONTACT_014("CONTACT_014", "招待トークンが見つかりません", Severity.WARN),

    /** 連絡先が見つからない */
    CONTACT_015("CONTACT_015", "連絡先が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
