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

    /** フォロー一覧が非公開（閲覧権限なし） */
    FOLLOW_LIST_NOT_PUBLIC("SOCIAL_009", "このユーザーのフォロー一覧は公開されていません", Severity.WARN),

    /** ユーザーが見つからない */
    FOLLOW_USER_NOT_FOUND("SOCIAL_010", "ユーザーが見つかりません", Severity.WARN),

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
    FRIEND_FEATURE_DISABLED("SOCIAL_109", "フレンドチーム機能は現在無効化されています", Severity.WARN),

    // ─────────────────────────────────────────────
    // F01.5 フレンドフォルダ
    // ─────────────────────────────────────────────

    /** フレンドフォルダが見つからない（404） */
    FRIEND_FOLDER_NOT_FOUND("SOCIAL_110", "フレンドフォルダが見つかりません", Severity.WARN),

    /** フレンドフォルダ上限超過（409） — 1チーム最大20個 */
    FRIEND_FOLDER_LIMIT_EXCEEDED("SOCIAL_111",
            "フレンドフォルダは1チームあたり最大20個までです", Severity.WARN),

    /** フレンドフォルダメンバー重複（409） */
    FRIEND_FOLDER_MEMBER_ALREADY_EXISTS("SOCIAL_112",
            "このフレンドチームは既にフォルダに登録されています", Severity.WARN),

    /** フレンドフォルダメンバーが見つからない（404） */
    FRIEND_FOLDER_MEMBER_NOT_FOUND("SOCIAL_113",
            "指定されたフレンドチームはこのフォルダに登録されていません", Severity.WARN),

    // ─────────────────────────────────────────────
    // F01.5 フレンドコンテンツ転送
    // ─────────────────────────────────────────────

    /** 転送履歴が見つからない（404） */
    FRIEND_FORWARD_NOT_FOUND("SOCIAL_120", "転送履歴が見つかりません", Severity.WARN),

    /** 既に転送済み（409） — 冪等性違反 */
    FRIEND_FORWARD_ALREADY_EXISTS("SOCIAL_121", "この投稿は既に転送済みです", Severity.WARN),

    /** 転送元投稿が見つからない（404） */
    FRIEND_FORWARD_SOURCE_POST_NOT_FOUND("SOCIAL_122",
            "転送元の投稿が見つかりません", Severity.WARN),

    /** 転送元投稿がフレンド共有対象外（400） — share_with_friends=FALSE */
    FRIEND_FORWARD_NOT_SHARABLE("SOCIAL_123",
            "この投稿はフレンドチームへ共有されていません", Severity.WARN),

    /** フレンド関係が成立していない（404） */
    FRIEND_FORWARD_RELATION_NOT_FOUND("SOCIAL_124",
            "転送元チームとのフレンド関係が成立していません", Severity.WARN),

    /** Phase 1 で MEMBER_AND_SUPPORTER 指定は拒否（400） */
    FRIEND_FORWARD_SUPPORTER_NOT_ALLOWED("SOCIAL_125",
            "Phase 1 では SUPPORTER への転送配信は利用できません", Severity.WARN),

    // ─────────────────────────────────────────────
    // F01.5 Phase 2 フレンド通知
    // ─────────────────────────────────────────────

    /** 通知送信先が存在しない（400） */
    FRIEND_NOTIFICATION_NO_TARGETS("SOCIAL_130",
            "フレンド通知の送信先が存在しません", Severity.WARN),

    /** フォルダ指定時に target_folder_id が未指定（400） */
    FRIEND_NOTIFICATION_FOLDER_ID_REQUIRED("SOCIAL_131",
            "フォルダ指定時は target_folder_id が必須です", Severity.WARN),

    /** TEAMS 指定時に target_team_ids が未指定（400） */
    FRIEND_NOTIFICATION_TEAM_IDS_REQUIRED("SOCIAL_132",
            "TEAMS 指定時は target_team_ids が必須です", Severity.WARN),

    /** 不正な target_type（400） */
    FRIEND_NOTIFICATION_INVALID_TARGET_TYPE("SOCIAL_133",
            "target_type は FOLDER または TEAMS を指定してください", Severity.WARN),

    /** 送信先チームがフレンド関係にない（400） */
    FRIEND_NOTIFICATION_TARGET_NOT_FRIEND("SOCIAL_134",
            "指定されたチームはフレンド関係にありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
