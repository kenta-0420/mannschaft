package com.mannschaft.app.promotion;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.2 プロモーション配信のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum PromotionErrorCode implements ErrorCode {

    /** プロモーションが見つからない */
    PROMOTION_NOT_FOUND("PROMOTION_001", "プロモーションが見つかりません", Severity.WARN),

    /** プロモーションが編集不可 */
    PROMOTION_NOT_EDITABLE("PROMOTION_002", "このプロモーションは編集できません", Severity.WARN),

    /** プロモーションが配信不可 */
    PROMOTION_NOT_PUBLISHABLE("PROMOTION_003", "このプロモーションは配信できません", Severity.WARN),

    /** プロモーションがキャンセル不可 */
    PROMOTION_NOT_CANCELLABLE("PROMOTION_004", "このプロモーションはキャンセルできません", Severity.WARN),

    /** クーポンが見つからない */
    COUPON_NOT_FOUND("PROMOTION_005", "クーポンが見つかりません", Severity.WARN),

    /** クーポン発行上限超過 */
    COUPON_ISSUE_LIMIT_EXCEEDED("PROMOTION_006", "クーポンの発行上限に達しています", Severity.WARN),

    /** クーポン配布が見つからない */
    DISTRIBUTION_NOT_FOUND("PROMOTION_007", "クーポン配布が見つかりません", Severity.WARN),

    /** クーポンが利用不可 */
    COUPON_NOT_REDEEMABLE("PROMOTION_008", "このクーポンは利用できません", Severity.WARN),

    /** クーポン利用上限超過 */
    COUPON_USE_LIMIT_EXCEEDED("PROMOTION_009", "このクーポンの利用上限に達しています", Severity.WARN),

    /** 配信が見つからない */
    DELIVERY_NOT_FOUND("PROMOTION_010", "配信が見つかりません", Severity.WARN),

    /** セグメントプリセットが見つからない */
    PRESET_NOT_FOUND("PROMOTION_011", "セグメントプリセットが見つかりません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("PROMOTION_012", "この操作に必要な権限がありません", Severity.WARN),

    /** プロモーションが承認不可 */
    PROMOTION_NOT_APPROVABLE("PROMOTION_013", "このプロモーションは承認できません", Severity.WARN),

    /** クーポン有効期限切れ */
    COUPON_EXPIRED("PROMOTION_014", "このクーポンは有効期限切れです", Severity.WARN),

    /** 課金記録が見つからない */
    BILLING_RECORD_NOT_FOUND("PROMOTION_015", "課金記録が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
