package com.mannschaft.app.shiftbudget.event;

import java.util.List;

/**
 * F08.7 「時給未設定により消化記録をスキップした」ことを表すイベント（CMP-260910-1555）。
 *
 * <p>消化記録 hook（{@code ShiftBudgetConsumptionRecordListener}）が記録処理を終えたあとに publish し、
 * {@code ShiftBudgetHourlyRateMissingNotificationListener} が {@code AFTER_COMMIT} 境界の後に
 * 受け取って予算管理者へ配送する（CMP-056 / Issue #2990 の正規形）。</p>
 *
 * <p><b>載せるのは ID と種別だけである</b>。描画済みの件名・本文や JPA 管理エンティティは載せない
 * （受信者ごとの locale で本文を組み立てるのは配送側の責務であり、管理エンティティを
 * スレッド跨ぎで渡すと未コミットの値を別スレッドが読む形になるため）。</p>
 *
 * @param organizationId 組織 ID（通知スコープ・failed_events の記録キー）
 * @param teamId         チーム ID（ログ・監査用）
 * @param teamSlug       チームの slug（通知のアクション URL 用。{@code null} なら URL 無し）
 * @param scheduleId     対象の {@code shift_schedules.id}
 * @param missingUserIds 時給が未設定だったユーザー ID（重複なし・昇順）
 */
public record ShiftBudgetHourlyRateMissingEvent(
        Long organizationId,
        Long teamId,
        String teamSlug,
        Long scheduleId,
        List<Long> missingUserIds) {
}
