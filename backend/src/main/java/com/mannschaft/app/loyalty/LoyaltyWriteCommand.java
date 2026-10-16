package com.mannschaft.app.loyalty;

import java.util.UUID;

/** Facadeだけが検証済み本人・対象・操作値から作る内部書込コマンド。raw QRは保持しない。 */
public record LoyaltyWriteCommand(Long teamId, Long actorUserId, Long userId, UUID itemId,
        long expectedItemVersion, long expectedProgramVersion, RequestOperation operation,
        UUID requestKey, byte[] payloadHash, Long sourceLocationId, String locationName,
        PendingSnapshot pendingSnapshot) {
    /** 既存staff/承認経路と実Writer試練の呼出契約を保つ。SELFのFacadeは明示snapshotを渡す。 */
    public LoyaltyWriteCommand(Long teamId, Long actorUserId, Long userId, UUID itemId,
            long expectedItemVersion, long expectedProgramVersion, RequestOperation operation,
            UUID requestKey, byte[] payloadHash, Long sourceLocationId, String locationName) {
        this(teamId, actorUserId, userId, itemId, expectedItemVersion, expectedProgramVersion,
                operation, requestKey, payloadHash, sourceLocationId, locationName, null);
    }

    /** requestId=nullは照会時点のPENDING不在を明示する。外部状態はTX外Facadeの評価点。 */
    public record PendingSnapshot(UUID requestId, RequestReason externalFailure) { }
}
