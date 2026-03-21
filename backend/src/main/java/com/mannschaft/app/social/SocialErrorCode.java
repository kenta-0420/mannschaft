package com.mannschaft.app.social;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.4 ソーシャルプロフィール・フォロー機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SocialErrorCode implements ErrorCode {

    /** プロフィールが見つからない */
    PROFILE_NOT_FOUND("SOCIAL_001", "プロフィールが見つかりません", Severity.WARN),

    /** ハンドルが既に使用されている */
    HANDLE_ALREADY_TAKEN("SOCIAL_002", "このハンドルは既に使用されています", Severity.WARN),

    /** プロフィールが既に存在する */
    PROFILE_ALREADY_EXISTS("SOCIAL_003", "ソーシャルプロフィールは既に作成済みです", Severity.WARN),

    /** プロフィールが無効 */
    PROFILE_INACTIVE("SOCIAL_004", "プロフィールが無効です", Severity.WARN),

    /** 自分自身をフォロー不可 */
    CANNOT_FOLLOW_SELF("SOCIAL_005", "自分自身をフォローすることはできません", Severity.WARN),

    /** フォロー重複 */
    FOLLOW_ALREADY_EXISTS("SOCIAL_006", "既にフォロー済みです", Severity.WARN),

    /** フォローが見つからない */
    FOLLOW_NOT_FOUND("SOCIAL_007", "フォローが見つかりません", Severity.WARN),

    /** フォロー対象が見つからない */
    FOLLOW_TARGET_NOT_FOUND("SOCIAL_008", "フォロー対象が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
