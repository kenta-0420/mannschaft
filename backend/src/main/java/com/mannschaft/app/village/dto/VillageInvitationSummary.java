package com.mannschaft.app.village.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 村招待の一覧要素（骨格）。平文トークンを<b>含めてはならない</b>。
 *
 * <p>本クラスは試練（テスト先行）が参照するための最小スタブである。</p>
 */
public record VillageInvitationSummary(
        UUID id,
        Instant expiresAt,
        Integer maxUses,
        Integer usedCount,
        Instant revokedAt,
        Long targetUserId) {
}
