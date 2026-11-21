package com.mannschaft.app.match;

import com.mannschaft.app.match.domain.MatchStatus;

import java.util.UUID;

/**
 * 記録開始による状態遷移の監査スナップショット。Entityや遅延参照を持たない。
 * actorと帰属は認可済みの記録経路で確定し、観戦配信の最小ビューには載せない。
 */
public record MatchRecordingStartedEvent(UUID matchId, Long teamId, Long organizationId,
                                         Long actorUserId, MatchStatus before, MatchStatus after) {
}
