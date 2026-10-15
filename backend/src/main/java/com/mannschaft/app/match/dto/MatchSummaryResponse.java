package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.entity.MatchEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 試合一覧（コレクション）行のサマリレスポンス DTO（02 §F・03 §C.2）。
 *
 * <p>FE 一覧ページ向けの軽量表現。詳細取得（{@link MatchResponse}）と異なり、
 * 編集権限フラグ（{@code canEditMeta}/{@code canRecordTimeline}）や {@code notes}・{@code createdBy} などの
 * 行レベルで不要なフィールドは持たない（一覧は閲覧前提・最小限・行ごとの per-entity 権限算出による N+1 を避ける）。</p>
 *
 * <p><b>所有列は露出しない</b>: {@code owning_team_id} 相当（{@code created_by}/{@code scorekeeper_user_id} 等の
 * 所有・権限列）は一覧に出さない（03 §C.2）。{@code teamId} は URL スコープと一致するため帰属の漏洩にはならない。</p>
 *
 * <p>{@code status} により進行中（{@link MatchStatus#IN_PROGRESS}）・終了（{@link MatchStatus#COMPLETED}）等を
 * FE 側で判定する（行に進行中判定用の専用フラグは持たせず status を正本とする）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1 / 02 §F / 03 §C.2</p>
 */
@Getter
@Builder
public class MatchSummaryResponse {

    private final UUID id;
    private final Sport sport;
    private final MatchKind kind;
    private final HomeAway homeAway;
    private final Long opponentTeamId;
    private final String opponentName;
    private final LocalDateTime kickoffAt;
    private final String venue;
    private final MatchStatus status;
    private final Integer homeScore;
    private final Integer awayScore;
    private final Integer homePenaltyScore;
    private final Integer awayPenaltyScore;
    private final Integer durationMinutes;

    /** Entity → サマリ DTO（所有/権限列は出さない・03 §C.2）。 */
    public static MatchSummaryResponse from(MatchEntity match) {
        return MatchSummaryResponse.builder()
                .id(match.getId())
                .sport(match.getSport())
                .kind(match.getKind())
                .homeAway(match.getHomeAway())
                .opponentTeamId(match.getOpponentTeamId())
                .opponentName(match.getOpponentName())
                .kickoffAt(match.getKickoffAt())
                .venue(match.getVenue())
                .status(match.getStatus())
                .homeScore(match.getHomeScore())
                .awayScore(match.getAwayScore())
                .homePenaltyScore(match.getHomePenaltyScore())
                .awayPenaltyScore(match.getAwayPenaltyScore())
                .durationMinutes(match.getDurationMinutes())
                .build();
    }
}
