package com.mannschaft.app.seal;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.3 電子印鑑のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SealErrorCode implements ErrorCode {

    /** 印鑑が見つからない */
    SEAL_NOT_FOUND("SEAL_001", "電子印鑑が見つかりません", Severity.WARN),

    /** 印鑑バリアントが重複 */
    DUPLICATE_VARIANT("SEAL_002", "同じバリアントの印鑑が既に存在します", Severity.WARN),

    /** スコープデフォルトが見つからない */
    SCOPE_DEFAULT_NOT_FOUND("SEAL_003", "スコープデフォルト設定が見つかりません", Severity.WARN),

    /** スコープデフォルトが重複 */
    DUPLICATE_SCOPE_DEFAULT("SEAL_004", "同じスコープのデフォルト設定が既に存在します", Severity.WARN),

    /** 押印ログが見つからない */
    STAMP_LOG_NOT_FOUND("SEAL_005", "押印ログが見つかりません", Severity.WARN),

    /** 既に取り消し済み */
    ALREADY_REVOKED("SEAL_006", "この押印は既に取り消されています", Severity.WARN),

    /** 印鑑ハッシュ不一致（改ざん検出） */
    HASH_MISMATCH("SEAL_007", "印鑑のハッシュが一致しません（改ざんの可能性があります）", Severity.ERROR),

    /** SVG生成エラー */
    SVG_GENERATION_FAILED("SEAL_008", "SVGの生成に失敗しました", Severity.ERROR),

    /** 印鑑が削除済み */
    SEAL_DELETED("SEAL_009", "この電子印鑑は削除されています", Severity.WARN),

    /** 不正な対象種別 */
    INVALID_TARGET_TYPE("SEAL_010", "不正な押印対象種別です", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
