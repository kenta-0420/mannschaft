package com.mannschaft.app.loyalty;

import java.util.UUID;

/**
 * 初回の書込TXで発行し、監査側はAFTER_COMMITでのみ受け取る。
 * transactionId/requestIdは対象に応じてnull可。QR・照合コード・名称・その他PIIを含めない。
 */
public record LoyaltyCommittedEvent(Long teamId, Long actorUserId, Long targetUserId,
        RequestOperation operation, UUID transactionId, UUID requestId) {
    public static final String AUDIT_EVENT_TYPE = "TEAM_LOYALTY_COMMITTED";
}
