package com.mannschaft.app.webhook;

/**
 * Webhookイベント種別定数。
 * event_type カラムに格納される文字列値を定義する。
 */
public enum WebhookEventType {

    /** インシデント作成 */
    INCIDENT_CREATED,

    /** インシデントステータス変更 */
    INCIDENT_STATUS_CHANGED,

    /** スケジュール作成 */
    SCHEDULE_CREATED,

    /** ブログ記事公開 */
    BLOG_POST_PUBLISHED,

    /** 活動結果作成 */
    ACTIVITY_RESULT_CREATED,

    /** メンバー参加 */
    MEMBER_JOINED
}
