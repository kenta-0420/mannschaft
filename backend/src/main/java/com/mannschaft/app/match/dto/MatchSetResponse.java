package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchSetEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * セットスコアのレスポンス DTO（バレーボール・01 §B.5 / sports/04 §4）。
 *
 * <p><b>Schema 命名</b>: tournament ドメインに既存の {@code MatchSetResponse} があるため、
 * OpenAPI スキーマ名衝突を避けて {@code MatchRecordSetResponse} を明示する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.5 / sports/04 §4</p>
 */
@Schema(name = "MatchRecordSetResponse")
@Getter
@Builder
public class MatchSetResponse {

    private final UUID id;
    private final UUID matchId;
    private final Integer setNumber;
    private final Integer homePoints;
    private final Integer awayPoints;
    /** セット勝者（未決着は null）。 */
    private final TeamSide winnerSide;
    /** 最終第 5 セット（15 点制）か。 */
    private final boolean finalSet;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public static MatchSetResponse from(MatchSetEntity set) {
        return MatchSetResponse.builder()
                .id(set.getId())
                .matchId(set.getMatchId())
                .setNumber(set.getSetNumber())
                .homePoints(set.getHomePoints())
                .awayPoints(set.getAwayPoints())
                .winnerSide(set.getWinnerSide())
                .finalSet(set.isFinalSet())
                .createdAt(set.getCreatedAt())
                .updatedAt(set.getUpdatedAt())
                .build();
    }
}
