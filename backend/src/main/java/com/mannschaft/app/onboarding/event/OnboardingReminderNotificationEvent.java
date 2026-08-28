package com.mannschaft.app.onboarding.event;

import java.util.List;

/**
 * オンボーディングリマインダーの通知発火イベント（Issue #2834 / CMP-056 第1群ロットA・第2群ロット2）。
 *
 * <p>手動一括リマインド（{@code OnboardingProgressService#sendReminders}）と、日次バッチ
 * （{@code OnboardingReminderBatchService} → {@code OnboardingReminderRunner}）の<b>両方</b>が
 * 本イベントを publish する。いずれも {@code notificationHelper.notify} を直接呼ばず、
 * <b>業務上の事実（ID）だけ</b>を積み、ロケール解決・件名/本文組み立ては
 * {@link OnboardingReminderNotificationListener}（{@code AFTER_COMMIT}）側で行う。</p>
 *
 * <h2>受信者ごとに 1 イベントを投げない理由</h2>
 * <p>対象者が多い場合に受信者数ぶんの {@code @Async} タスクを {@code event-pool} へ投入すると
 * キューを食い潰す。確定設計（Issue #2834 コメント）の方針どおり、<b>1 回の一括リマインドから
 * 通知要求一覧を 1 イベントとして発行し、配送リスナーが順次 Runner を呼ぶ</b>。受信者ごとの
 * 失敗隔離は配送リスナーのループ内 {@code try/catch} と Runner の {@code REQUIRES_NEW} が担う。
 * 日次バッチ側は進捗 1 件＝1 独立トランザクションで確定するため、受信者 1 名の単票イベントになる。</p>
 *
 * @param kind       リマインドの種別（通知種別と文言の選択に使う）
 * @param scopeType  スコープ種別（{@code "TEAM"} / {@code "ORGANIZATION"}）
 * @param scopeId    スコープID
 * @param recipients 受信者一覧（宛先ユーザーIDと進捗ID）
 */
public record OnboardingReminderNotificationEvent(
        Kind kind,
        String scopeType,
        Long scopeId,
        List<Recipient> recipients) {

    /**
     * 手動一括リマインド（{@link Kind#MANUAL}）用の簡易コンストラクタ。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param recipients 受信者一覧
     */
    public OnboardingReminderNotificationEvent(String scopeType, Long scopeId, List<Recipient> recipients) {
        this(Kind.MANUAL, scopeType, scopeId, recipients);
    }

    /**
     * リマインドの種別。
     *
     * <p>通知種別・文言・{@code deadlineAt} の読み直し要否がこれで決まる。日時のような業務データは
     * イベントに載せず（番人 {@code DateTimeAndZoneGuardTest} の方針および確定設計「イベントには
     * 読み直せる値を載せず ID だけ」）、配送リスナーが進捗を読み直して埋める。</p>
     */
    public enum Kind {
        /** 管理者が画面から明示的に送る一括リマインド（{@code ONBOARDING_REMINDER}・期限に言及しない）。 */
        MANUAL,
        /** 日次バッチの期限前リマインド（{@code ONBOARDING_REMINDER}・期限日を本文に埋める）。 */
        DEADLINE_APPROACHING,
        /** 日次バッチの期限超過通知（{@code ONBOARDING_OVERDUE}）。 */
        OVERDUE
    }

    /**
     * 受信者1件。
     *
     * @param userId     宛先ユーザーID
     * @param progressId 進捗ID（{@code sourceId} およびアクションURLに使う）
     */
    public record Recipient(Long userId, Long progressId) {
    }
}
