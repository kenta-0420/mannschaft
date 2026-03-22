package com.mannschaft.app.admin;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F10.1 お知らせ・目安箱・テンプレート機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum AdminFeedbackErrorCode implements ErrorCode {

    /** お知らせが見つからない */
    ANNOUNCEMENT_NOT_FOUND("ADMIN_FB_001", "お知らせが見つかりません", Severity.WARN),

    /** お知らせは既に公開済み */
    ANNOUNCEMENT_ALREADY_PUBLISHED("ADMIN_FB_002", "このお知らせは既に公開されています", Severity.WARN),

    /** フィードバックが見つからない */
    FEEDBACK_NOT_FOUND("ADMIN_FB_003", "フィードバックが見つかりません", Severity.WARN),

    /** フィードバックは既に回答済み */
    FEEDBACK_ALREADY_RESPONDED("ADMIN_FB_004", "このフィードバックは既に回答されています", Severity.WARN),

    /** 既に投票済み */
    FEEDBACK_ALREADY_VOTED("ADMIN_FB_005", "既にこのフィードバックに投票しています", Severity.WARN),

    /** 投票が見つからない */
    FEEDBACK_VOTE_NOT_FOUND("ADMIN_FB_006", "投票が見つかりません", Severity.WARN),

    /** アクションテンプレートが見つからない */
    ACTION_TEMPLATE_NOT_FOUND("ADMIN_FB_007", "アクションテンプレートが見つかりません", Severity.WARN),

    /** 無効なフィードバックステータス */
    INVALID_FEEDBACK_STATUS("ADMIN_FB_008", "無効なフィードバックステータスです", Severity.WARN),

    /** 権限グループが見つからない */
    PERMISSION_GROUP_NOT_FOUND("ADMIN_FB_009", "権限グループが見つかりません", Severity.WARN),

    /** 権限グループ名が重複 */
    PERMISSION_GROUP_NAME_DUPLICATE("ADMIN_FB_010", "同名の権限グループが既に存在します", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
