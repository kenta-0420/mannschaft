package com.mannschaft.app.gdpr;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F12.3 GDPR/個人情報管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum GdprErrorCode implements ErrorCode {

    /** データエクスポートは1日1回まで */
    GDPR_001("GDPR_001", "データエクスポートは1日1回まで", Severity.WARN),

    /** エクスポート処理中 */
    GDPR_002("GDPR_002", "エクスポート処理中です", Severity.WARN),

    /** エクスポートデータが見つかりません */
    GDPR_003("GDPR_003", "エクスポートデータが見つかりません", Severity.WARN),

    /** データエクスポートに失敗 */
    GDPR_004("GDPR_004", "データエクスポートに失敗しました", Severity.ERROR),

    /** パスワード認証が必要 */
    GDPR_005("GDPR_005", "パスワード認証が必要です", Severity.WARN),

    /** 管理者権限の移譲が必要 */
    GDPR_006("GDPR_006", "管理者権限の移譲が必要です", Severity.WARN),

    /** OTP認証がロック */
    GDPR_007("GDPR_007", "OTP認証がロックされました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
