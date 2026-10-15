package com.mannschaft.app.schedule.event;

import java.util.UUID;

/**
 * F03.10 スケジュール代理出席の通知配送要求イベント（Issue #2990 / L2 ROLLBACK_COUPLED 是正）。
 *
 * <p>{@code ScheduleDelegationService} の各業務トランザクション（代理指定・承認・拒否・取消・
 * 退会連動）の内側で publish し、{@code ScheduleDelegationNotifier} が {@code AFTER_COMMIT} で
 * 受け取る。</p>
 *
 * <p>イベントには<b>読み直せる ID と種別のみ</b>を載せる。委任者名・代理人名・スケジュール ID・
 * スコープはすべて業務データであるため積まず、配送側が {@code delegationId} から読み直す。
 * 特に日時型（{@code LocalDateTime}）を載せると {@code DateTimeAndZoneGuardTest} が弾く。</p>
 *
 * @param delegationId 委任 ID（本文・受信者の読み直しキー）
 * @param kind         通知の種別（どのトリガーで発火したか）
 */
public record ScheduleDelegationNotificationEvent(UUID delegationId, Kind kind) {

    /** F03.10 §8 の通知トリガー。 */
    public enum Kind {
        /** 代理依頼（PENDING）→ 代理人へ。 */
        REQUEST_PENDING,
        /** 自動承認（ACCEPTED で作成）→ 代理人へ。 */
        AUTO_ACCEPTED,
        /** 承認（PENDING → ACCEPTED）→ 委任者へ。 */
        ACCEPTED,
        /** 拒否（PENDING → REJECTED）→ 委任者へ。 */
        REJECTED,
        /** 取消（→ CANCELLED）→ 代理人へ。 */
        CANCELLED,
        /** 代理人のスコープ退会（§5.8）→ 委任者へ。 */
        DELEGATE_LEFT,
        /** 委任者のスコープ退会（§5.8）→ 代理人へ。 */
        DELEGATOR_LEFT
    }
}
