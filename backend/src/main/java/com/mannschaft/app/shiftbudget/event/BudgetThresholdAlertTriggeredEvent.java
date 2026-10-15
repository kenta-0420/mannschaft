package com.mannschaft.app.shiftbudget.event;

import java.util.List;

/**
 * F08.7 シフト予算 閾値超過警告の発火イベント（Issue #2990 L13）。
 *
 * <p>{@code budget_threshold_alerts} の行が INSERT された業務トランザクションの内側で publish され、
 * {@code ShiftBudgetThresholdAlertNotificationListener} が {@code AFTER_COMMIT} で受け取って
 * 通知を配送する。</p>
 *
 * <p><b>載せるのは ID と種別だけである</b>。描画済みの件名・本文や JPA 管理エンティティは載せない
 * （受信者ごとの locale で本文を組み立てるのは配送側の責務であり、管理エンティティを
 * スレッド跨ぎで渡すと未コミットの値を別スレッドが読む形になるため）。</p>
 *
 * @param alertId           発火した {@code budget_threshold_alerts.id}
 * @param allocationId      対象の {@code shift_budget_allocations.id}
 * @param organizationId    組織 ID（通知スコープ・failed_events の記録キー）
 * @param thresholdPercent  発火した閾値（80 / 100 / 120）
 * @param recipientUserIds  業務TX内で解決済みの受信者ユーザーID（{@code notified_user_ids} と同値）
 */
public record BudgetThresholdAlertTriggeredEvent(
        Long alertId,
        Long allocationId,
        Long organizationId,
        int thresholdPercent,
        List<Long> recipientUserIds) {
}
