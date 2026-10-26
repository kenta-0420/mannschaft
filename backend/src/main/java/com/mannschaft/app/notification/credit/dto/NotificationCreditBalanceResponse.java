package com.mannschaft.app.notification.credit.dto;

import java.time.LocalDateTime;

/**
 * 組織の通知クレジット残高レスポンス。
 *
 * @param freeUsedThisMonth 今月の無料枠使用通数
 * @param freeQuota         月間無料枠（固定10,000通）
 * @param creditBalance     クレジット残高（マイナスあり）
 * @param inGracePeriod     猶予期間中フラグ
 * @param gracePeriodEndsAt 猶予期間終了日時（猶予開始から72時間後）
 * @param gracePeriodDebt   猶予期間中の累積負債通数
 */
public record NotificationCreditBalanceResponse(
        long freeUsedThisMonth,
        long freeQuota,
        long creditBalance,
        boolean inGracePeriod,
        LocalDateTime gracePeriodEndsAt,
        long gracePeriodDebt
) {
    /** 月間無料枠の固定値 */
    public static final long FREE_QUOTA = 10_000L;
}
