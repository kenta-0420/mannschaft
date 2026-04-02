package com.mannschaft.app.gdpr;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F12.3 GDPRデータエクスポート機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum GdprErrorCode implements ErrorCode {

    /** 24時間以内にエクスポートを完了済み */
    GDPR_001("GDPR_001", "データエクスポートは24時間に1回のみリクエスト可能です", Severity.WARN),

    /** エクスポート処理中（重複リクエスト） */
    GDPR_002("GDPR_002", "データエクスポートが処理中です。完了をお待ちください", Severity.WARN),

    /** エクスポートデータが存在しないまたは期限切れ */
    GDPR_003("GDPR_003", "エクスポートデータが存在しないか期限切れです", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
