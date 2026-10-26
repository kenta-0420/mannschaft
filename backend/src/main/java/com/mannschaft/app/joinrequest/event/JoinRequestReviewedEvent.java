package com.mannschaft.app.joinrequest.event;

import java.util.UUID;

/**
 * 参加申請が承認/却下されたことの通知発火イベント（柱③-A・CMP-260901-1538）。
 *
 * <p>業務トランザクション内では本イベントの publish のみを行い、通知本文の組み立て・実配送は
 * {@link JoinRequestNotificationListener}（{@code AFTER_COMMIT}）側に委ねる。</p>
 *
 * @param requestId       参加申請 ID
 * @param scopeType       "TEAM" / "ORGANIZATION"
 * @param scopeId         対象スコープ ID
 * @param scopeName       対象スコープ表示名（通知本文用）
 * @param requesterUserId 申請者ユーザー ID（通知の宛先）
 * @param approved        承認なら true、却下なら false
 * @param reviewComment   審査コメント（任意）
 */
public record JoinRequestReviewedEvent(
        UUID requestId, String scopeType, Long scopeId, String scopeName,
        Long requesterUserId, boolean approved, String reviewComment) {
}
