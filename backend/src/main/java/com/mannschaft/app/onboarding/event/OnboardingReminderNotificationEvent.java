package com.mannschaft.app.onboarding.event;

import java.util.List;

/**
 * オンボーディング手動一括リマインダーの通知発火イベント（Issue #2834 / CMP-056 第1群ロットA）。
 *
 * <p>{@code OnboardingProgressService#sendReminders} は業務トランザクションの内側で本イベントを
 * publish するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、ロケール解決・件名/本文組み立ては
 * {@link OnboardingReminderNotificationListener}（{@code AFTER_COMMIT}）側で行う。</p>
 *
 * <h2>受信者ごとに 1 イベントを投げない理由</h2>
 * <p>対象者が多い場合に受信者数ぶんの {@code @Async} タスクを {@code event-pool} へ投入すると
 * キューを食い潰す。確定設計（Issue #2834 コメント）の方針どおり、<b>1 回の一括リマインドから
 * 通知要求一覧を 1 イベントとして発行し、配送リスナーが順次 Runner を呼ぶ</b>。受信者ごとの
 * 失敗隔離は配送リスナーのループ内 {@code try/catch} と Runner の {@code REQUIRES_NEW} が担う。</p>
 *
 * @param scopeType  スコープ種別（{@code "TEAM"} / {@code "ORGANIZATION"}）
 * @param scopeId    スコープID
 * @param recipients 受信者一覧（宛先ユーザーIDと進捗ID）
 */
public record OnboardingReminderNotificationEvent(
        String scopeType,
        Long scopeId,
        List<Recipient> recipients) {

    /**
     * 受信者1件。
     *
     * @param userId     宛先ユーザーID
     * @param progressId 進捗ID（{@code sourceId} およびアクションURLに使う）
     */
    public record Recipient(Long userId, Long progressId) {
    }
}
