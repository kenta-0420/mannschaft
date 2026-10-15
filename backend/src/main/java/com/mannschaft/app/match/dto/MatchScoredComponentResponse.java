package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.ScoredApparatus;
import com.mannschaft.app.match.domain.ScoredComponentType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchScoredComponentEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 採点競技（フィギュアスケート/体操）の<b>採点内訳 1 明細</b>レスポンス DTO
 * （sports/07_scored.md §4B / 01 §B.1.2）。
 *
 * <p><b>Schema 命名</b>: tournament ドメインの {@code Match*} と OpenAPI スキーマ名が衝突しないよう
 * {@code MatchRecordScoredComponentResponse} を明示する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B</p>
 */
@Schema(name = "MatchRecordScoredComponentResponse")
@Getter
@Builder
public class MatchScoredComponentResponse {

    private final UUID id;
    private final UUID matchId;
    private final TeamSide competitorSide;
    private final ScoredApparatus apparatus;
    private final String judgeLabel;
    private final ScoredComponentType componentType;
    /** 当該項目の点数（整数スケール×1000・小数は表示で復元・§4.1）。 */
    private final Integer pointsScaled;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public static MatchScoredComponentResponse from(MatchScoredComponentEntity entity) {
        return MatchScoredComponentResponse.builder()
                .id(entity.getId())
                .matchId(entity.getMatchId())
                .competitorSide(entity.getCompetitorSide())
                .apparatus(entity.getApparatus())
                .judgeLabel(entity.getJudgeLabel())
                .componentType(entity.getComponentType())
                .pointsScaled(entity.getPointsScaled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
