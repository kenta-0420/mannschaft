package com.mannschaft.app.event.event;

import com.mannschaft.app.event.EventScopeType;

import java.util.UUID;

/**
 * イベント代理出席が ACCEPTED に確定したことを表すドメインイベント（F03.10 §5.5）。
 *
 * <p>event ドメインの委任が自動承認（{@code is_proxy_auto_accept = TRUE}）または代理人承認で
 * ACCEPTED 確定した際に、その確定トランザクション内で発火する。
 * proxy_delegations はこの場では作らず、proxyvote ドメインの
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} リスナーが受信して連携処理を行う
 * （CLAUDE.md 原則5: クロスドメインはイベント駆動で分離）。</p>
 *
 * <p>{@code proxyVoteSessionId} が null の場合は投票代理連携は行われない（イベントは発火するが
 * 受信側でスキップされる）。</p>
 *
 * @param delegationId       event_delegations.id（UUIDv7）
 * @param eventId            対象イベント ID
 * @param delegatorId        委任者 user_id
 * @param delegateId         代理人 user_id
 * @param scopeType          イベントスコープ種別（TEAM / ORGANIZATION）
 * @param scopeId            スコープ ID（team_id または organization_id）
 * @param proxyVoteSessionId F08.3 投票セッション ID（任意連携。null の場合は連携なし）
 */
public record EventDelegationAcceptedEvent(
        UUID delegationId,
        Long eventId,
        Long delegatorId,
        Long delegateId,
        EventScopeType scopeType,
        Long scopeId,
        Long proxyVoteSessionId
) {

    public EventDelegationAcceptedEvent {
        if (delegationId == null) {
            throw new IllegalArgumentException("delegationId must not be null");
        }
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        if (delegatorId == null) {
            throw new IllegalArgumentException("delegatorId must not be null");
        }
        if (delegateId == null) {
            throw new IllegalArgumentException("delegateId must not be null");
        }
    }
}
