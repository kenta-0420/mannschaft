package com.mannschaft.app.tournament;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.7 大会・リーグ管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TournamentErrorCode implements ErrorCode {

    /** 大会が見つからない */
    TOURNAMENT_NOT_FOUND("TOUR_001", "大会が見つかりません", Severity.WARN),

    /** ディビジョンが見つからない */
    DIVISION_NOT_FOUND("TOUR_002", "ディビジョンが見つかりません", Severity.WARN),

    /** 試合が見つからない */
    MATCH_NOT_FOUND("TOUR_003", "試合が見つかりません", Severity.WARN),

    /** 大会ステータスが操作を許可しない */
    INVALID_TOURNAMENT_STATUS("TOUR_004", "大会のステータスではこの操作を実行できません", Severity.WARN),

    /** 対戦カード自動生成には2チーム以上必要 */
    INSUFFICIENT_PARTICIPANTS("TOUR_005", "対戦カード自動生成には2チーム以上必要です", Severity.WARN),

    /** 同一チームの重複登録 */
    DUPLICATE_PARTICIPANT("TOUR_006", "同一チームが既に登録されています", Severity.WARN),

    /** スコア入力値が不正 */
    INVALID_SCORE("TOUR_007", "スコア入力値が不正です", Severity.WARN),

    /** セット数が sets_to_win を超過 */
    SETS_EXCEEDED("TOUR_008", "セット数が上限を超えています", Severity.WARN),

    /** 全試合完了前に昇降格は実行不可 */
    MATCHES_NOT_COMPLETED("TOUR_009", "全試合が完了するまで昇降格は実行できません", Severity.WARN),

    /** 存在しない stat_key */
    INVALID_STAT_KEY("TOUR_010", "大会に定義されていない成績項目キーです", Severity.WARN),

    /** 既に昇降格が実行済み */
    PROMOTION_ALREADY_EXECUTED("TOUR_011", "このチームの昇降格は既に実行済みです", Severity.WARN),

    /** max_participants を超過 */
    MAX_PARTICIPANTS_EXCEEDED("TOUR_012", "ディビジョンの最大参加チーム数を超えています", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("TOUR_013", "テンプレートが見つかりません", Severity.WARN),

    /** プリセットが見つからない */
    PRESET_NOT_FOUND("TOUR_014", "プリセットが見つかりません", Severity.WARN),

    /** KNOCKOUT形式で引分けは不可 */
    KNOCKOUT_DRAW_NOT_ALLOWED("TOUR_015", "トーナメント形式では引分けは認められません", Severity.WARN),

    /** league_round_type が大会形式と不整合 */
    INVALID_LEAGUE_ROUND_TYPE("TOUR_016", "リーグラウンド設定が大会形式と整合しません", Severity.WARN),

    /** 参加チームが ACTIVE でない */
    PARTICIPANT_NOT_ACTIVE("TOUR_017", "参加チームのステータスがACTIVEでないため操作できません", Severity.WARN),

    /** 参加チームが見つからない */
    PARTICIPANT_NOT_FOUND("TOUR_018", "参加チームが見つかりません", Severity.WARN),

    /** エントリー表が見つからない */
    ENTRY_MEMBER_NOT_FOUND("TOUR_019", "エントリー表メンバーが見つかりません", Severity.WARN),

    /** エントリーが編集ロック中 */
    ENTRY_LOCKED("TOUR_020", "大会のステータスによりエントリーを編集できません", Severity.WARN),

    /** チームメンバーではないユーザーを指定した */
    USER_NOT_TEAM_MEMBER("TOUR_021", "指定されたユーザーはチームメンバーではありません", Severity.WARN),

    /** 最小エントリー数を満たしていない */
    MIN_ENTRY_COUNT_VIOLATION("TOUR_022", "最小エントリー人数を満たしていません", Severity.WARN),

    /** 最大エントリー数を超過している */
    MAX_ENTRY_COUNT_EXCEEDED("TOUR_023", "最大エントリー人数を超えています", Severity.WARN),

    /** エントリーテンプレートが見つからない */
    ENTRY_TEMPLATE_NOT_FOUND("TOUR_024", "エントリーテンプレートが見つかりません", Severity.WARN),

    /** テンプレート登録上限（5件）超過 */
    MAX_TEMPLATE_COUNT_EXCEEDED("TOUR_025", "エントリーテンプレートは最大5件まで登録できます", Severity.WARN),

    /** チームが組織に所属していない */
    TEAM_NOT_IN_ORGANIZATION("TOUR_026", "チームがこの組織に所属していません", Severity.WARN),

    /** 同一ユーザーが既にエントリー済み */
    DUPLICATE_ENTRY_MEMBER("TOUR_027", "このユーザーは既にエントリー済みです", Severity.WARN),

    /** テンプレートとチームが一致しない */
    TEMPLATE_TEAM_MISMATCH("TOUR_028", "テンプレートのチームと参加チームが一致しません", Severity.WARN),

    /** 連絡スペースが見つからない（F08.7.1・IDOR 対策で 404 に統一） */
    CONTACT_SPACE_NOT_FOUND("TOUR_029", "連絡スペースが見つかりません", Severity.WARN),

    /** 連絡スペースの閲覧権限がない（F08.7.1 §4.1） */
    CONTACT_SPACE_VIEW_FORBIDDEN("TOUR_030", "この連絡スペースを閲覧する権限がありません", Severity.WARN),

    /** 連絡スペースへの投稿権限がない（F08.7.1 §4.2） */
    CONTACT_SPACE_POST_FORBIDDEN("TOUR_031", "この連絡スペースへ投稿する権限がありません", Severity.WARN),

    /** 連絡スペースの公開設定を変更する権限がない（F08.7.1 §5・主催組織 ADMIN 限定） */
    CONTACT_SPACE_VISIBILITY_FORBIDDEN("TOUR_032", "連絡スペースの公開設定を変更する権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
