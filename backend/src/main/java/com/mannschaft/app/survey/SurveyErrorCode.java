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
    INVALID_TIME_RANGE("SURVEY_011", "開始時刻は終了時刻より前である必要があります", Severity.WARN),

    /** 設問なしで公開不可 */
    NO_QUESTIONS("SURVEY_012", "設問が1つも登録されていないアンケートは公開できません", Severity.WARN),

    /** 回答者一覧の閲覧権限なし（F05.4 §7.2） */
    RESPONDENTS_ACCESS_DENIED("SURVEY_013", "未回答者一覧を閲覧する権限がありません", Severity.WARN),

    /** 督促操作の権限なし（F05.4 督促 API） */
    REMIND_PERMISSION_DENIED("SURVEY_014", "督促を送信する権限がありません", Severity.WARN),

    /** 督促のクールダウン未経過（F05.4 督促 API） */
    REMIND_COOLDOWN_NOT_ELAPSED("SURVEY_015", "前回の督促から24時間経過していません", Severity.WARN),

    /** 督促回数の上限到達（F05.4 督促 API） */
    REMIND_QUOTA_EXCEEDED("SURVEY_016", "督促の上限回数（3回）に達しました", Severity.WARN),

    /** 締切延長で短縮を試みた（F05.4 extend） */
    INVALID_NEW_DEADLINE("SURVEY_017", "延長後の締切は現在の締切より後である必要があります", Severity.WARN),

    /** 匿名アンケートの個別回答取得不可（F05.4 responses/{userId}） */
    ANONYMOUS_RESPONSE_FORBIDDEN("SURVEY_018", "匿名アンケートの個別回答は取得できません", Severity.WARN),

    /** 個別回答の閲覧権限なし（F05.4 responses/{userId}） */
    RESPONSE_ACCESS_DENIED("SURVEY_019", "個別回答を閲覧する権限がありません", Severity.WARN),

    /** 指定ユーザーが未回答（F05.4 responses/{userId}） */
    USER_RESPONSE_NOT_FOUND("SURVEY_020", "指定ユーザーの回答が見つかりません", Severity.WARN),

    /** シリーズに該当するアンケートが存在しない（F05.4 series/{id}/comparison） */
    SERIES_NOT_FOUND("SURVEY_021", "指定シリーズに該当するアンケートが見つかりません", Severity.WARN),

    /** 操作権限なし（F05.4 extend/duplicate 共通） */
    OPERATION_PERMISSION_DENIED("SURVEY_022", "この操作を実行する権限がありません", Severity.WARN),

    /**
     * 匿名アンケートとチーム別内訳トグルの併用禁止（F05.4 チーム別内訳・御裁可B）。
     * 匿名アンケートで回答者の所属チームを内訳に出すと匿名性が崩れるため、作成時に弾く（400）。
     */
    ANONYMOUS_TEAM_BREAKDOWN_CONFLICT("SURVEY_023",
            "匿名アンケートではチーム別内訳を有効化できません", Severity.WARN),

    /**
     * enum 文字列フィールドの値が不正（F05.4 作成・更新・設問作成）。
     * resultsVisibility / distributionMode / unrespondedVisibility / questionType 等の
     * クライアント入力 enum 文字列が定義済みの値に一致しない場合に投げる。
     * IllegalArgumentException を握りつぶさず 400（Severity.WARN）で正直に返す。
     */
    INVALID_ENUM_VALUE("SURVEY_024",
            "指定された値が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
