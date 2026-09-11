package com.mannschaft.app.shiftbudget;

/**
 * F08.7 「時給未設定のため消化記録できなかった」警告通知のメッセージキー（CMP-260910-1555）。
 *
 * <p>{@link ShiftBudgetThresholdAlertMessages} と同じ理由で 1 箇所に集約する。
 * 発火側（{@code ShiftBudgetHourlyRateMissingNotifier}）が
 * {@code shift_budget_failed_events.payload} へ保存するキーと、再送側
 * （{@code ShiftBudgetRetryExecutor} → {@code ShiftBudgetNotificationResendService}）が
 * 読み直すキーを同じ定数から採る。</p>
 *
 * <p>本メッセージはプレースホルダを持たない（再送経路が単一引数
 * {@code threshold_percent} しか渡せないため、引数に依存しない文面にしてある）。</p>
 */
public final class ShiftBudgetHourlyRateMissingMessages {

    /** 件名の i18n キー。 */
    public static final String TITLE_KEY = "notification.shiftBudget.hourlyRateMissing.title";

    /** 本文の i18n キー。 */
    public static final String BODY_KEY = "notification.shiftBudget.hourlyRateMissing.body";

    /** ロケールファイルにキーが無い場合の件名。 */
    public static final String DEFAULT_TITLE = "シフト予算: 時給未設定のメンバーがいます";

    /** ロケールファイルにキーが無い場合の本文。 */
    public static final String DEFAULT_BODY =
            "時給が未設定のメンバーがいるため、そのメンバーのシフトは予算の消化額に反映されていません。"
                    + "チーム設定の「時給設定」から時給を登録してください。";

    private ShiftBudgetHourlyRateMissingMessages() {
    }
}
