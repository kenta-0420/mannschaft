package com.mannschaft.app.equipment;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F07.3 備品管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum EquipmentErrorCode implements ErrorCode {

    /** 備品が見つからない */
    ITEM_NOT_FOUND("EQUIPMENT_001", "備品が見つかりません", Severity.WARN),

    /** 貸出記録が見つからない */
    ASSIGNMENT_NOT_FOUND("EQUIPMENT_002", "貸出記録が見つかりません", Severity.WARN),

    /** 在庫不足 */
    INSUFFICIENT_STOCK("EQUIPMENT_003", "在庫が不足しています", Severity.WARN),

    /** 既に返却済み */
    ALREADY_RETURNED("EQUIPMENT_004", "既に返却済みです", Severity.WARN),

    /** 消耗品ではない備品に対する消費操作 */
    NOT_CONSUMABLE("EQUIPMENT_005", "この備品は消耗品ではありません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("EQUIPMENT_006", "この操作に必要な権限がありません", Severity.WARN),

    /** ユーザーがスコープに所属していない */
    USER_NOT_IN_SCOPE("EQUIPMENT_007", "指定されたユーザーはこのスコープに所属していません", Severity.WARN),

    /** 貸出中の備品は削除不可 */
    HAS_ACTIVE_ASSIGNMENTS("EQUIPMENT_008", "貸出中の備品は削除できません。先にすべて返却してください", Severity.WARN),

    /** 備品のスコープ不一致 */
    SCOPE_MISMATCH("EQUIPMENT_009", "備品のスコープが一致しません", Severity.WARN),

    /** 不正なコンテンツタイプ */
    INVALID_CONTENT_TYPE("EQUIPMENT_010", "画像形式は JPEG, PNG, WebP のみ対応しています", Severity.WARN),

    /** ファイルサイズ超過 */
    FILE_SIZE_EXCEEDED("EQUIPMENT_011", "ファイルサイズが上限（5MB）を超えています", Severity.WARN),

    /** 一括操作の件数超過 */
    BULK_LIMIT_EXCEEDED("EQUIPMENT_012", "一括操作は最大20件までです", Severity.WARN),

    /** 一括返却で異なる備品が混在 */
    MIXED_EQUIPMENT_IN_BULK("EQUIPMENT_013", "一括返却は同一備品の貸出レコードのみ指定できます", Severity.WARN),

    /** 備品がメンテナンス中または廃棄済み */
    ITEM_NOT_AVAILABLE("EQUIPMENT_014", "この備品は現在貸出できません", Severity.WARN),

    /** 自分の貸出分ではない（MEMBER返却制限） */
    NOT_OWN_ASSIGNMENT("EQUIPMENT_015", "自分の貸出分のみ返却できます", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
