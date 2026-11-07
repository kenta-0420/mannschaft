package com.mannschaft.app.auth;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 本人出生情報の入力と確認の安全な拒否理由。原情報をメッセージへ含めない。 */
@Getter @RequiredArgsConstructor
public enum BirthProfileErrorCode implements ErrorCode {
    /** HTTP 400。 */
    INVALID_INPUT("BIRTHPROFILE_001", "出生プロフィールの入力内容に不備があります", Severity.WARN),

    /** HTTP 409。 */
    PROFILE_INCOMPLETE("BIRTHPROFILE_002", "本人の氏名・読み・生年月日を補完してください", Severity.WARN),

    /** HTTP 404。 */
    NOT_FOUND("BIRTHPROFILE_003", "本人確認が見つかりません", Severity.WARN),

    /** HTTP 409。 */
    CONFIRMATION_STALE("BIRTHPROFILE_004", "本人情報を確認し直してください", Severity.WARN),

    /** HTTP 409。 */
    VERSION_CONFLICT("BIRTHPROFILE_005", "本人情報の版が変更されています", Severity.WARN),

    /** HTTP 409。 */
    COMMAND_CONFLICT("BIRTHPROFILE_006", "同じ命令キーを別の操作に使用できません", Severity.WARN),

    /** HTTP 503。 */
    UNAVAILABLE("BIRTHPROFILE_007", "出生情報の利用は準備中です", Severity.WARN),

    /** HTTP 409。既知RAW_PROFILE_PUTのHMAC鍵交代だけに使用する。 */
    COMMAND_KEY_ROTATED("BIRTHPROFILE_008", "本人情報を読み直し、新しい命令キーで再送してください", Severity.WARN),

    /** HTTP 409。出生PUTの新しい未成年DOB保存時だけ既存同意を要求する。 */
    PARENTAL_CONSENT_REQUIRED("BIRTHPROFILE_009", "保護者の同意を確認してから本人情報を更新してください", Severity.WARN);
    private final String code;
    private final String message;
    private final Severity severity;
}
