package com.mannschaft.app.role.dto;

import java.time.Instant;

/**
 * 招待行をロックして読んだ最新値の射影（Entity を介さない。外側トランザクションのスナップショットや
 * 一次キャッシュに左右されない）。時刻は {@code dbNow} と同じ基準で読んだ値であり、互いに比較してよい。
 */
public record InviteTokenSnapshot(
        Long id,
        Long teamId,
        Long organizationId,
        Long roleId,
        Instant expiresAt,
        Integer maxUses,
        int usedCount,
        Instant revokedAt,
        long scopeClosureGeneration,
        Long createdBy,
        Long targetUserId,
        Instant dbNow) {

    public String scopeType() {
        return teamId != null ? "TEAM" : "ORGANIZATION";
    }

    public Long scopeId() {
        return teamId != null ? teamId : organizationId;
    }
}
