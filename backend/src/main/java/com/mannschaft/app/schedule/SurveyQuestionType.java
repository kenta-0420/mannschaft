package com.mannschaft.app.schedule;

/**
 * アンケート設問の回答形式。
 */
public enum SurveyQuestionType {
    /** テキスト入力 */
    TEXT,
    /** はい・いいえ */
    BOOLEAN,
    /** 単一選択 */
    SELECT,
    /** 複数選択 */
    MULTI_SELECT
}
