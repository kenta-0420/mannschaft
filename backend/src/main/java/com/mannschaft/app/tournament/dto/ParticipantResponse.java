package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 参加チームレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ParticipantResponse {

    private final Long id;
    private final Long divisionId;
    private final Long teamId;
    private final Integer seed;
    private final String displayName;
    private final String status;
    private final LocalDateTime joinedAt;
    /** チーム名（team ドメインから NameResolverService 経由で一括解決。解決不能なら null）。 */
    private final String teamName;

    /** チーム名を付与した新しいレスポンスを返す（本 DTO は不変。MapStruct が fluent setter と誤認しないよう static）。 */
    public static ParticipantResponse withTeamName(ParticipantResponse base, String teamName) {
        return new ParticipantResponse(base.id, base.divisionId, base.teamId, base.seed, base.displayName,
                base.status, base.joinedAt, teamName);
    }
}
