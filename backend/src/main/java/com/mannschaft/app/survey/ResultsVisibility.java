package com.mannschaft.app.survey;

/**
 * 結果公開設定。アンケート結果の閲覧可能タイミングを表す。
 */
public enum ResultsVisibility {
    AFTER_RESPONSE,
    AFTER_CLOSE,
    ADMINS_ONLY,
    VIEWERS_ONLY
}
