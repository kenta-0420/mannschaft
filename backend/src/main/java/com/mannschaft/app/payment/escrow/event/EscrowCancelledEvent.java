package com.mannschaft.app.payment.escrow.event;

import java.util.UUID;

/**
 * escrow（謝礼与信）が capture 前に取り消されたことを表す業務イベント（Issue #2990 L7）。
 *
 * <p>{@code EscrowLifecycleService} の業務トランザクション（Stripe 与信取消 ＋ {@code escrow_transactions}
 * の CANCELLED 化）の内側で publish し、実際の通知配送は
 * {@link EscrowLifecycleNotificationListener}（{@code AFTER_COMMIT} ＋ {@code @Async("event-pool")}）が行う。</p>
 *
 * <p><b>イベントには ID と種別だけを載せる。</b>描画済みの件名・本文や日時は載せない
 * （番人 {@code DateTimeAndZoneGuardTest} の方針。文面はリスナーが受信者ごとの locale で組み立てる）。</p>
 *
 * @param escrowId 取消済み escrow の ID
 * @param reason   取消理由（本文 i18n キーの選択にのみ使う）
 */
public record EscrowCancelledEvent(UUID escrowId, Reason reason) {

    /**
     * 取消理由。是正前に {@code EscrowLifecycleService} が直接選んでいた本文 i18n キーと 1:1 に対応する
     * （文面は 1 文字も変えていない）。
     */
    public enum Reason {
        /** 札主が期限までに confirm しなかった（PENDING_CONFIRMATION 期限超過）。 */
        PENDING_CONFIRMATION_EXPIRED,
        /** 受取口座の onboarding 未完のまま hold が失効した（HELD 失効）。 */
        HELD_EXPIRED,
        /** 与信が失効間近で capture できなかった（AUTHORIZED 失効）。 */
        AUTHORIZATION_EXPIRED,
        /**
         * 募集の取下げに連動した取消。
         *
         * <p>本文は {@link #AUTHORIZATION_EXPIRED} と同一の i18n キーを使う（是正前の
         * {@code cancelForRecruitmentCancellation} がその本文を渡していたため、文面を変えない）。
         * 種別を分けているのは、ログと将来の文面差し替えのために取消の由来を失わないためである。
         */
        RECRUITMENT_CANCELLED
    }
}
