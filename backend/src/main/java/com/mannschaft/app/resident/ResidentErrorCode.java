package com.mannschaft.app.resident;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.1 住民台帳のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ResidentErrorCode implements ErrorCode {

    /** 居室が見つからない */
    DWELLING_UNIT_NOT_FOUND("RESIDENT_001", "居室が見つかりません", Severity.WARN),

    /** 居室番号が重複 */
    DUPLICATE_UNIT_NUMBER("RESIDENT_002", "この居室番号は既に登録されています", Severity.WARN),

    /** 居住者が見つからない */
    RESIDENT_NOT_FOUND("RESIDENT_003", "居住者が見つかりません", Severity.WARN),

    /** 書類が見つからない */
    DOCUMENT_NOT_FOUND("RESIDENT_004", "書類が見つかりません", Severity.WARN),

    /** 物件掲示が見つからない */
    LISTING_NOT_FOUND("RESIDENT_005", "物件掲示が見つかりません", Severity.WARN),

    /** 物件問い合わせが重複 */
    DUPLICATE_INQUIRY("RESIDENT_006", "既にこの物件に問い合わせ済みです", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("RESIDENT_007", "この操作に必要な権限がありません", Severity.WARN),

    /** 既に退去処理済み */
    ALREADY_MOVED_OUT("RESIDENT_008", "既に退去処理済みです", Severity.WARN),

    /** 既に確認済み */
    ALREADY_VERIFIED("RESIDENT_009", "既に確認済みです", Severity.WARN),

    /** 自室情報が見つからない */
    MY_UNIT_NOT_FOUND("RESIDENT_010", "自室情報が見つかりません", Severity.WARN),

    /** 物件掲示が編集不可 */
    LISTING_NOT_EDITABLE("RESIDENT_011", "この物件掲示は編集できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
