package com.mannschaft.app.corkboard;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.8 コルクボードのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum CorkboardErrorCode implements ErrorCode {

    /** ボードが見つからない */
    BOARD_NOT_FOUND("CORKBOARD_001", "コルクボードが見つかりません", Severity.WARN),

    /** カードが見つからない */
    CARD_NOT_FOUND("CORKBOARD_002", "カードが見つかりません", Severity.WARN),

    /** セクションが見つからない */
    GROUP_NOT_FOUND("CORKBOARD_003", "セクションが見つかりません", Severity.WARN),

    /** ボード数上限超過 */
    BOARD_LIMIT_EXCEEDED("CORKBOARD_004", "ボード数の上限に達しています", Severity.WARN),

    /** カード数上限超過 */
    CARD_LIMIT_EXCEEDED("CORKBOARD_005", "カード数の上限に達しています", Severity.WARN),

    /** カードが既にセクションに所属 */
    CARD_ALREADY_IN_GROUP("CORKBOARD_006", "カードは既にこのセクションに追加されています", Severity.WARN),

    /** カードがセクションに未所属 */
    CARD_NOT_IN_GROUP("CORKBOARD_007", "カードはこのセクションに含まれていません", Severity.WARN),

    /** 下書き以外は編集不可 */
    NOT_EDITABLE("CORKBOARD_008", "このボードは編集できません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("CORKBOARD_009", "この操作に必要な権限がありません", Severity.WARN),

    /** 共有ボードでのピン操作拒否（個人スコープのみ許可） */
    PIN_PERSONAL_ONLY("CORKBOARD_011", "個人ボードのカードのみピン止めできます", Severity.WARN),

    /** アーカイブ済みカードへのピン操作拒否 */
    PIN_ARCHIVED_NOT_ALLOWED("CORKBOARD_012", "アーカイブ済みカードはピン止めできません", Severity.WARN),

    /** ピン止めカード上限超過 */
    PIN_LIMIT_EXCEEDED("CORKBOARD_013", "ピン止めカードの上限（50枚）に達しています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
