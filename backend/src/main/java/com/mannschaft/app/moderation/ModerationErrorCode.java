package com.mannschaft.app.moderation;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.5 通報・モデレーション機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ModerationErrorCode implements ErrorCode {

    /** 通報が見つからない */
    REPORT_NOT_FOUND("MODERATION_001", "通報が見つかりません", Severity.WARN),

    /** 重複通報 */
    REPORT_ALREADY_EXISTS("MODERATION_002", "同一コンテンツへの通報は既に送信済みです", Severity.WARN),

    /** 自分のコンテンツは通報不可 */
    CANNOT_REPORT_OWN_CONTENT("MODERATION_003", "自分のコンテンツを通報することはできません", Severity.WARN),

    /** 通報状態不正 */
    INVALID_REPORT_STATUS("MODERATION_004", "この操作は現在の通報状態では実行できません", Severity.WARN),

    /** 通報対象が見つからない */
    REPORT_TARGET_NOT_FOUND("MODERATION_005", "通報対象が見つかりません", Severity.WARN),

    /**
     * 通報の宛先スコープを対象から導出できない種別（入力制約 → 既定の 400）。
     *
     * <p>USER / SOCIAL_PROFILE はチーム・組織に属さず宛先スコープを導出できないため受け付けない
     * （CMP-260917-1135）。</p>
     */
    REPORT_TARGET_TYPE_NOT_SUPPORTED("MODERATION_006", "この種別のコンテンツは通報できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
