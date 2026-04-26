package com.mannschaft.app.family;

/**
 * ケア対象者イベント通知の種別。F03.12 / Phase3 通知ログでも使用する。
 *
 * <p>Phase8 で EVENT_LATE_ARRIVAL_NOTICE・EVENT_ABSENCE_NOTICE を追加（§15 事前遅刻・欠席連絡）。</p>
 */
public enum EventCareNotificationType {
    RSVP_CONFIRMED,
    CHECKIN,
    CHECKOUT,
    NO_CONTACT_CHECK,
    ABSENT_ALERT,
    DISMISSAL,
    /** §15 主催者向け遅刻連絡通知。メンバーが「N分遅刻予定」を申告した際に主催者へ送信する。 */
    EVENT_LATE_ARRIVAL_NOTICE,
    /** §15 主催者向け欠席連絡通知。メンバーが事前欠席を申告した際に主催者へ送信する。 */
    EVENT_ABSENCE_NOTICE
}
