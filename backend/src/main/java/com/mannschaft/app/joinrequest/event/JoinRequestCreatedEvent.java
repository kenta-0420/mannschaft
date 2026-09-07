package com.mannschaft.app.joinrequest.event;

import java.util.UUID;

/**
 * 参加申請が受理されたことの通知発火イベント（柱③-A・CMP-260901-1538）。
 *
 * <p>{@code JoinRequestService#createRequest} は業務トランザクションの内側で本イベントを publish
 * するだけに留める。業務上の事実（ID）だけを積み、通知の文面組み立て・実配送は
 * {@link JoinRequestNotificationListener}（{@code AFTER_COMMIT}）側で行う
 * （通知のトランザクション境界番人対応・金型: {@code AdminSuccessionForcedNotificationEvent}）。</p>
 *
 * @param requestId       参加申請 ID
 * @param scopeType       "TEAM" / "ORGANIZATION"
 * @param scopeId         対象スコープ ID
 * @param scopeName       対象スコープ表示名（通知本文用）
 * @param requesterUserId 申請者ユーザー ID
 */
public record JoinRequestCreatedEvent(
        UUID requestId, String scopeType, Long scopeId, String scopeName, Long requesterUserId) {
}
