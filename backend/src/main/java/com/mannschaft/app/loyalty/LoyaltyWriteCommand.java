package com.mannschaft.app.loyalty;

import java.util.UUID;

/** Facadeだけが検証済み本人・対象・操作値から作る内部書込コマンド。raw QRは保持しない。 */
public record LoyaltyWriteCommand(Long teamId, Long actorUserId, Long userId, UUID itemId,
                                  long expectedItemVersion, long expectedProgramVersion,
                                  RequestOperation operation, UUID requestKey, byte[] payloadHash,
                                  Long sourceLocationId, String locationName) {
}
