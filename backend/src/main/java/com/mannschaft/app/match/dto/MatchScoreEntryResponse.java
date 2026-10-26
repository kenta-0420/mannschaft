package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.entity.MatchScoreEntryEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 採点競技（フィギュアスケート/体操）の<b>多人数順位制の出場者エントリ 1 明細</b>レスポンス DTO
 * （sports/07_scored.md §5B / 01 §B.1.2）。
 *
 * <p>合計点（{@code totalScaled}・整数スケール×1000・小数は表示で復元）と、サーバーが算出した
 * 順位（{@code rankPosition}・合計点降順・同点同順位）を返す。</p>
 *
 * <p><b>Schema 命名</b>: tournament ドメインの {@code Match*} と OpenAPI スキーマ名が衝突しないよう
 * {@code MatchRecordScoreEntryResponse} を明示する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §5B</p>
 */
@Schema(name = "MatchRecordScoreEntryResponse")
@Getter
@Builder
public class MatchScoreEntryResponse {

    private final UUID id;
    private final UUID matchId;
    private final Long competitorUserId;
    private final String competitorName;
    private final Long competitorTeamId;
    /** 合計点（整数スケール×1000・小数は表示で復元・§4.1）。 */
    private final Integer totalScaled;
    /** 順位（合計点降順・同点同順位 1,2,2,4・サーバー算出・§5B.2 / §6）。 */
    private final Integer rankPosition;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public static MatchScoreEntryResponse from(MatchScoreEntryEntity entity) {
        return MatchScoreEntryResponse.builder()
                .id(entity.getId())
                .matchId(entity.getMatchId())
                .competitorUserId(entity.getCompetitorUserId())
                .competitorName(entity.getCompetitorName())
                .competitorTeamId(entity.getCompetitorTeamId())
                .totalScaled(entity.getTotalScaled())
                .rankPosition(entity.getRankPosition())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
