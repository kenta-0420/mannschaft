package com.mannschaft.app.survey;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.4 アンケート・投票のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SurveyErrorCode implements ErrorCode {

    /** アンケートが見つからない */
    SURVEY_NOT_FOUND("SURVEY_001", "アンケートが見つかりません", Severity.WARN),

    /** 設問が見つからない */
    QUESTION_NOT_FOUND("SURVEY_002", "設問が見つかりません", Severity.WARN),

    /** 選択肢が見つからない */
    OPTION_NOT_FOUND("SURVEY_003", "選択肢が見つかりません", Severity.WARN),

    /** アンケートステータス不正 */
    INVALID_SURVEY_STATUS("SURVEY_004", "この操作は現在のアンケートステータスでは実行できません", Severity.WARN),

    /** アンケート期限切れ */
    SURVEY_EXPIRED("SURVEY_005", "このアンケートは回答期限を過ぎています", Severity.WARN),

    /** 回答重複 */
    DUPLICATE_RESPONSE("SURVEY_006", "このアンケートには既に回答済みです", Severity.WARN),

    /** 配信対象外 */
    NOT_TARGET_USER("SURVEY_007", "このアンケートの回答対象ではありません", Severity.WARN),

    /** 必須設問未回答 */
    REQUIRED_QUESTION_MISSING("SURVEY_008", "必須設問に回答してください", Severity.WARN),

    /** 選択数超過 */
    MAX_SELECTIONS_EXCEEDED("SURVEY_009", "選択可能数を超えています", Severity.WARN),

    /** 結果閲覧権限なし */
    RESULT_ACCESS_DENIED("SURVEY_010", "アンケート結果を閲覧する権限がありません", Severity.WARN),

    /** 開始時刻と終了時刻の整合性エラー */
    INVALID_TIME_RANGE("SURVEY_011", "開始時刻は終了時刻より前である必要があります", Severity.ERROR),

    /** 設問なしで公開不可 */
    NO_QUESTIONS("SURVEY_012", "設問が1つも登録されていないアンケートは公開できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
