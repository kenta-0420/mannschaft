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
    AD_004("AD_004", "有効開始日時は有効終了日時より前に設定してください", Severity.WARN),

    /** 広告主アカウントが見つからない */
    AD_005("AD_005", "広告主アカウントが見つかりません", Severity.WARN),

    /** 組織が既に広告主として登録済み */
    AD_006("AD_006", "この組織は既に広告主として登録されています", Severity.WARN),

    /** ステータス不適合 */
    AD_007("AD_007", "アカウントのステータスが操作に適合しません", Severity.WARN),

    /** 料金テーブルが見つからない */
    AD_008("AD_008", "料金テーブルが見つかりません", Severity.WARN),

    /** 過去の料金テーブルは削除不可 */
    AD_009("AD_009", "過去の料金テーブルは削除できません", Severity.WARN),

    /** 停止アカウントの更新不可 */
    AD_010("AD_010", "アカウントが停止されているため更新できません", Severity.WARN),

    /** 条件に一致する料金が見つからない */
    AD_011("AD_011", "指定された条件に一致する料金が見つかりません", Severity.WARN),

    /** フィールド未指定 */
    AD_012("AD_012", "少なくとも1つのフィールドを指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
