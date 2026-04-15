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
    FOLLOW_TARGET_NOT_FOUND("SOCIAL_008", "フォロー対象が見つかりません", Severity.WARN),

    // ─────────────────────────────────────────────
    // F01.5 フレンドチーム関係
    // ─────────────────────────────────────────────

    /** 自チームを自分自身でフォローしようとした（400） */
    FRIEND_CANNOT_SELF_FOLLOW("SOCIAL_101", "自チームをフォローすることはできません", Severity.WARN),

    /** 既にフォロー済み（409） */
    FRIEND_ALREADY_FOLLOWING("SOCIAL_102", "既にこのチームをフォローしています", Severity.WARN),

    /** フォロー関係が存在しない（404） */
    FRIEND_FOLLOW_NOT_FOUND("SOCIAL_103", "フォロー関係が見つかりません", Severity.WARN),

    /** フォロー対象チームが存在しない（404） */
    FRIEND_TARGET_TEAM_NOT_FOUND("SOCIAL_104", "フォロー対象のチームが存在しません", Severity.WARN),

    /** MANAGE_FRIEND_TEAMS 権限が不足（403） */
    FRIEND_INSUFFICIENT_PERMISSION("SOCIAL_105", "フレンドチーム管理の権限が必要です", Severity.WARN),

    /** フレンド関係が見つからない（404） */
    FRIEND_RELATION_NOT_FOUND("SOCIAL_106", "フレンド関係が見つかりません", Severity.WARN),

    /** 公開設定変更は ADMIN のみ（403） */
    FRIEND_VISIBILITY_ADMIN_ONLY("SOCIAL_107", "公開設定の変更には ADMIN 権限が必要です", Severity.WARN),

    /** フレンド関係の競合状態（NOWAIT タイムアウト） — 再試行要求（202） */
    FRIEND_NOWAIT_TIMEOUT("SOCIAL_108", "フレンド関係の処理が競合しました。再試行してください", Severity.WARN),

    /** プラットフォーム機能無効化中（403） */
    FRIEND_FEATURE_DISABLED("SOCIAL_109", "フレンドチーム機能は現在無効化されています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
