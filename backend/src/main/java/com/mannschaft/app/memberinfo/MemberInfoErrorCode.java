package com.mannschaft.app.memberinfo;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MemberInfoErrorCode implements ErrorCode {
    FIELD_NOT_FOUND("MEMBER_INFO_001", "フィールドが見つかりません", Severity.WARN),
    FIELD_BELONGS_TO_OTHER_TEAM("MEMBER_INFO_002", "このフィールドへのアクセス権限がありません", Severity.WARN),
    REQUIRED_FIELD_MISSING("MEMBER_INFO_003", "必須フィールドが未入力です", Severity.WARN),
    INVALID_FIELD_TYPE_VALUE("MEMBER_INFO_004", "フィールドの値の形式が正しくありません", Severity.WARN),
    FIELD_LIMIT_EXCEEDED("MEMBER_INFO_005", "フィールドの上限（20件）に達しています", Severity.WARN),
    INACTIVE_FIELD_UPDATE("MEMBER_INFO_006", "無効なフィールドへの回答は受け付けられません", Severity.WARN),
    INVALID_INTERVAL_VALUE("MEMBER_INFO_007", "更新間隔は12・36・60（ヶ月）のいずれかを指定してください", Severity.WARN),
    REMIND_TOO_SOON("MEMBER_INFO_008", "24時間以内にリマインドを送信済みです", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
