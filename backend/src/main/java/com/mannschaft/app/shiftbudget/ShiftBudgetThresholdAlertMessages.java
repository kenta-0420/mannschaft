package com.mannschaft.app.shiftbudget;

/**
 * F08.7 シフト予算 閾値超過警告の通知メッセージキー（Issue #2908 / #2990 L13）。
 *
 * <p>発火側（{@code ShiftBudgetThresholdAlertNotificationListener}）が
 * {@code shift_budget_failed_events.payload} へ保存するキーと、再送側
 * （{@code ShiftBudgetRetryExecutor} → {@code ShiftBudgetNotificationResendService}）が
 * 読み直すキーを<b>1 箇所に集約する</b>。両側に同じ switch を写すと、
 * 片方だけ書き換えられたときに「保存されたキーで引けない」形で静かに壊れるためである。</p>
 */
public final class ShiftBudgetThresholdAlertMessages {

    /** 件名の i18n キー（閾値によらず 1 本）。 */
    public static final String TITLE_KEY = "notification.shiftBudget.thresholdAlert.title";

    private ShiftBudgetThresholdAlertMessages() {
    }

    /**
     * 閾値ごとの本文 i18n キー。
     *
     * @param thresholdPercent 閾値（80 / 100 / 120、それ以外は汎用キー）
     * @return ロケールファイル参照用のキー
     */
    public static String bodyKey(int thresholdPercent) {
        return switch (thresholdPercent) {
            case 80 -> "notification.shiftBudget.thresholdAlert.body80";
            case 100 -> "notification.shiftBudget.thresholdAlert.body100";
            case 120 -> "notification.shiftBudget.thresholdAlert.body120";
            default -> "notification.shiftBudget.thresholdAlert.bodyOther";
        };
    }

    /**
     * 閾値ごとの本文の既定値（ロケールファイルにキーが無い場合のフォールバック）。
     *
     * @param thresholdPercent 閾値
     * @return 既定の本文
     */
    public static String defaultBody(int thresholdPercent) {
        return switch (thresholdPercent) {
            case 80 -> "予算 80% に到達しました";
            case 100 -> "予算を超過しました";
            case 120 -> "予算 120% を超過しました（重大）";
            default -> "シフト予算が閾値 " + thresholdPercent + "% に到達しました";
        };
    }

    /**
     * 閾値ごとの件名の既定値。
     *
     * @param thresholdPercent 閾値
     * @return 既定の件名
     */
    public static String defaultTitle(int thresholdPercent) {
        return "シフト予算 警告 (" + thresholdPercent + "%)";
    }
}
