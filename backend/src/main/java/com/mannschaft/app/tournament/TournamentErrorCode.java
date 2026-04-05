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
    PARTICIPANT_NOT_FOUND("TOUR_018", "参加チームが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
