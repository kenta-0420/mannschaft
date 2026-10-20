package com.mannschaft.app.loyalty;

import java.util.UUID;

/** 新規申請操作の認可・外部状態snapshotを渡す内部型。 */
public record LoyaltyRequestActionCommand(Long teamId, Long actorUserId, UUID requestId,
        long expectedRequestVersion, String verificationCode, RequestOperation operation,
        UUID requestKey, byte[] payloadHash, RequestReason externalFailure) {
}
