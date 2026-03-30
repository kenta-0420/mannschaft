package com.mannschaft.app.role;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.2 ロール・権限管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum RoleErrorCode implements ErrorCode {

    /** ロールが見つかりません */
    ROLE_001("ROLE_001", "ロールが見つかりません", Severity.WARN),

    /** 招待トークンが無効または期限切れです */
    ROLE_002("ROLE_002", "招待トークンが無効または期限切れです", Severity.WARN),

    /** 招待トークンの使用回数上限に達しています */
    ROLE_003("ROLE_003", "招待トークンの使用回数上限に達しています", Severity.WARN),

    /** 最後の管理者を除名・変更できません */
    ROLE_004("ROLE_004", "最後の管理者を除名・変更できません", Severity.WARN),

    /** 上位ロールのユーザーをブロックできません */
    ROLE_005("ROLE_005", "上位ロールのユーザーをブロックできません", Severity.WARN),

    /** 権限グループが見つかりません */
    ROLE_006("ROLE_006", "権限グループが見つかりません", Severity.WARN),

    /** パーミッションが見つかりません */
    ROLE_007("ROLE_007", "パーミッションが見つかりません", Severity.WARN),

    /** QRコードサイズが範囲外です（64〜1024px） */
    ROLE_008("ROLE_008", "QRコードサイズは64〜1024の範囲で指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
