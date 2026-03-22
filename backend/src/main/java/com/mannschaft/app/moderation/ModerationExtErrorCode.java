package com.mannschaft.app.moderation;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F10.2 モデレーション拡張機能のエラーコード定義。
 * 既存 ModerationErrorCode との重複を回避するため MODERATION_EXT_ プレフィックスを使用する。
 */
@Getter
@RequiredArgsConstructor
public enum ModerationExtErrorCode implements ErrorCode {

    /** 違反が見つからない */
    VIOLATION_NOT_FOUND("MODERATION_EXT_001", "違反が見つかりません", Severity.WARN),

    /** 異議申立てが見つからない */
    APPEAL_NOT_FOUND("MODERATION_EXT_002", "異議申立てが見つかりません", Severity.WARN),

    /** 異議申立てトークンが無効 */
    APPEAL_TOKEN_INVALID("MODERATION_EXT_003", "異議申立てトークンが無効または期限切れです", Severity.WARN),

    /** 異議申立て済み */
    APPEAL_ALREADY_SUBMITTED("MODERATION_EXT_004", "既に異議申立て済みです", Severity.WARN),

    /** 異議申立て状態不正 */
    APPEAL_INVALID_STATUS("MODERATION_EXT_005", "この操作は現在の異議申立て状態では実行できません", Severity.WARN),

    /** 再レビューが見つからない */
    RE_REVIEW_NOT_FOUND("MODERATION_EXT_006", "再レビューが見つかりません", Severity.WARN),

    /** 再レビュー済み */
    RE_REVIEW_ALREADY_EXISTS("MODERATION_EXT_007", "このアクションに対する再レビューは既に存在します", Severity.WARN),

    /** 再レビュー状態不正 */
    RE_REVIEW_INVALID_STATUS("MODERATION_EXT_008", "この操作は現在の再レビュー状態では実行できません", Severity.WARN),

    /** 解除申請が見つからない */
    UNFLAG_REQUEST_NOT_FOUND("MODERATION_EXT_009", "解除申請が見つかりません", Severity.WARN),

    /** 解除申請不可（資格なし） */
    UNFLAG_NOT_ELIGIBLE("MODERATION_EXT_010", "現在、解除申請の資格がありません", Severity.WARN),

    /** 解除申請状態不正 */
    UNFLAG_INVALID_STATUS("MODERATION_EXT_011", "この操作は現在の解除申請状態では実行できません", Severity.WARN),

    /** 設定が見つからない */
    SETTING_NOT_FOUND("MODERATION_EXT_012", "モデレーション設定が見つかりません", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("MODERATION_EXT_013", "対応テンプレートが見つかりません", Severity.WARN),

    /** 自主修正期限超過 */
    SELF_CORRECT_EXPIRED("MODERATION_EXT_014", "自主修正期間を過ぎています", Severity.WARN),

    /** ヤバいやつではない */
    NOT_YABAI_USER("MODERATION_EXT_015", "このユーザーはヤバいやつ認定されていません", Severity.WARN),

    /** 保留中の申請あり */
    PENDING_REQUEST_EXISTS("MODERATION_EXT_016", "保留中の申請が既に存在します", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
