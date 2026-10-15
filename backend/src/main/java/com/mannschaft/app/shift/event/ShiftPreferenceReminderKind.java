package com.mannschaft.app.shift.event;

/**
 * シフト希望提出リマインドの種別（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>通知種別と文面の i18n キー・既定文を種別ごとに持つ。是正前は
 * {@code ShiftPreferenceReminderBatchService} が個別に引数で渡していたものを、配送リスナー側へ移すために集約した
 * （文面・通知種別は是正前と同一）。本文の第 0 引数はシフト表のタイトル。</p>
 */
public enum ShiftPreferenceReminderKind {

    /** 提出期限の 48 時間前（定期バッチ）。 */
    REMINDER_48H("SHIFT_REQUEST_REMINDER_48H",
            "notification.shift.reminder48h.title", "シフト希望の提出期限 48 時間前です",
            "notification.shift.reminder48h.body",
            "シフト「%s」の提出期限が 48 時間以内です。まだ提出していない場合はお早めに。"),

    /** 提出期限の 24 時間前（定期バッチ）。 */
    REMINDER_24H("SHIFT_REQUEST_REMINDER",
            "notification.shift.reminder24h.title", "シフト希望の提出期限が明日までです",
            "notification.shift.reminder24h.body",
            "シフト「%s」の提出期限は明日までです。まだ提出していない場合は今すぐご対応ください。"),

    /** 管理者の手動リマインド。 */
    MANUAL("SHIFT_REQUEST_REMINDER_MANUAL",
            "notification.shift.manualReminder.title", "シフト希望提出のリマインド",
            "notification.shift.manualReminder.body",
            "シフト「%s」の希望提出のお願いです。提出期限までにご対応ください。");

    private final String notificationType;
    private final String titleKey;
    private final String titleDefault;
    private final String bodyKey;
    private final String bodyDefaultFormat;

    ShiftPreferenceReminderKind(String notificationType, String titleKey, String titleDefault,
                                String bodyKey, String bodyDefaultFormat) {
        this.notificationType = notificationType;
        this.titleKey = titleKey;
        this.titleDefault = titleDefault;
        this.bodyKey = bodyKey;
        this.bodyDefaultFormat = bodyDefaultFormat;
    }

    public String notificationType() {
        return notificationType;
    }

    public String titleKey() {
        return titleKey;
    }

    public String titleDefault() {
        return titleDefault;
    }

    public String bodyKey() {
        return bodyKey;
    }

    /** メッセージ未定義時の本文既定値（シフト表タイトルを埋め込む）。 */
    public String bodyDefault(String scheduleTitle) {
        return bodyDefaultFormat.formatted(scheduleTitle);
    }
}
