package com.mannschaft.app.village.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 村招待の発行応答（骨格）。
 *
 * <p>{@code token} は<b>発行時のこの応答でのみ</b>平文を返す。DB には SHA-256 ハッシュしか
 * 保存しないため、一覧・再取得では二度と平文を得られない（AC-3 / AC-18）。</p>
 *
 * <p>本クラスは試練（テスト先行）が参照するための最小スタブである。</p>
 */
public record VillageInvitationIssueResponse(
        UUID id,
        String token,
        Instant expiresAt,
        Integer maxUses,
        Long targetUserId) {
}
