package com.mannschaft.app.advertising;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 広告機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum AdvertisingErrorCode implements ErrorCode {

    /** アフィリエイト設定が見つからない */
    AD_001("AD_001", "指定されたアフィリエイト設定が見つかりません", Severity.WARN),

    /** 無効なプロバイダー */
    AD_002("AD_002", "無効なプロバイダーが指定されました", Severity.WARN),

    /** 無効な配置場所 */
    AD_003("AD_003", "無効な配置場所が指定されました", Severity.WARN),

    /** 有効期間の不整合 */
    AD_004("AD_004", "有効開始日時は有効終了日時より前に設定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
