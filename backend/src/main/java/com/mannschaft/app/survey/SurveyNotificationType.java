package com.mannschaft.app.survey;

/**
 * アンケート機能の通知タイプ定数。
 *
 * <p>設計書 F05.4 §1391-1399（通知タイプ表）に対応する。</p>
 *
 * <p>{@link com.mannschaft.app.notification.service.NotificationHelper} の API は通知タイプを
 * {@code String} で受け取るため、利用側では {@code .name()} で文字列化して渡すこと。</p>
 */
public enum SurveyNotificationType {

    /**
     * アンケート公開通知。新しくアンケートが公開された際、配信対象者に送信する。
     *
     * <p>※ 現時点では未実装。将来 SurveyService.publishSurvey() で送信予定。</p>
     */
    SURVEY_CREATED,

    /**
     * 未回答督促通知。未回答メンバーへ手動リマインドを送信する際に使用する。
     */
    SURVEY_RESPONSE_REMINDER,

    /**
     * 結果公開通知。アンケート締め切り後、結果が閲覧可能となった旨を通知する。
     *
     * <p>※ 現時点では未実装。将来 SurveyService.closeSurvey() / 結果集計バッチで送信予定。</p>
     */
    SURVEY_RESULTS_AVAILABLE
}
