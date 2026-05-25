package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * 村ロビー在席インジケーターのレスポンス DTO（F17.1 Phase 2）。
 *
 * <p>STOMP ブロードキャストおよび REST GET の両方で使用する。</p>
 */
public record LobbyPresenceResponse(
        List<PresenceMember> members,
        int activeCount
) {

    public record PresenceMember(
            Long userId,
            String nickname
    ) {}

    public static LobbyPresenceResponse of(List<PresenceMember> members) {
        return new LobbyPresenceResponse(members, members.size());
    }
}
