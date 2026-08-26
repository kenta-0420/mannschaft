package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.entity.MatchEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 試合本体のレスポンス DTO（02 §F・03）。
 *
 * <p><b>所有列は露出しない</b>: {@code owning_team_id} 等の DB の所有はユーザーに不可視（03 §C.2）。
 * 「自分が編集できるか」は {@code canEditMeta} フラグで露出する（UI には編集可否のみ）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1 / 03 §C.2</p>
 */
@Schema(name = "MatchDetailResponse")
@Getter
@Builder
public class MatchResponse {

    private final UUID id;
    private final Long teamId;
    private final Sport sport;
    private final MatchKind kind;
    private final Long tournamentFixtureId;
    private final Long scheduleId;
    private final HomeAway homeAway;
    private final Long opponentTeamId;
    private final String opponentName;
    private final LocalDateTime kickoffAt;
    private final String venue;
    private final Integer durationMinutes;
    private final String periodFormat;
    private final Integer homeScore;
    private final Integer awayScore;
    private final Integer homePenaltyScore;
    private final Integer awayPenaltyScore;
    /** 総手数（ターン制のみ・球技は null・01 §B.1）。 */
    private final Integer totalMoves;
    /** 勝ち方（ターン制のみ・ShogiWinMethod/GoWinMethod の enum 名・球技/引分は null・01 §D.7）。 */
    private final String winMethod;
    /** 団体戦の親 match（個人戦/親は null・子ボードのみ設定・01 §B.6）。 */
    private final UUID parentMatchId;
    /** ボード順（団体戦の子のみ・1=大将/主将 等・01 §B.6）。 */
    private final Integer boardNumber;
    private final MatchStatus status;
    private final boolean hasScorekeeper;
    private final Long scorekeeperUserId;
    private final String notes;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /** 閲覧者がこの試合のメタを編集できるか（UI 露出・所有列そのものは隠す・03 §C.2）。 */
    private final boolean canEditMeta;

    /** 閲覧者がこの試合にタイムライン記録できるか（UI 露出・03 §C.2）。 */
    private final boolean canRecordTimeline;

    /**
     * Entity ＋ 閲覧者の権限フラグから DTO を構築する。
     *
     * @param match            対象試合
     * @param canEditMeta      閲覧者のメタ編集可否（{@code MatchAccessService.canEditMeta}）
     * @param canRecordTimeline 閲覧者のタイムライン記録可否（{@code MatchAccessService.canRecordTimeline}）
     */
    public static MatchResponse from(MatchEntity match, boolean canEditMeta, boolean canRecordTimeline) {
        return MatchResponse.builder()
                .id(match.getId())
                .teamId(match.getTeamId())
                .sport(match.getSport())
                .kind(match.getKind())
                .tournamentFixtureId(match.getTournamentFixtureId())
                .scheduleId(match.getScheduleId())
                .homeAway(match.getHomeAway())
                .opponentTeamId(match.getOpponentTeamId())
                .opponentName(match.getOpponentName())
                .kickoffAt(match.getKickoffAt())
                .venue(match.getVenue())
                .durationMinutes(match.getDurationMinutes())
                .periodFormat(match.getPeriodFormat())
                .homeScore(match.getHomeScore())
                .awayScore(match.getAwayScore())
                .homePenaltyScore(match.getHomePenaltyScore())
                .awayPenaltyScore(match.getAwayPenaltyScore())
                .totalMoves(match.getTotalMoves())
                .winMethod(match.getWinMethod())
                .parentMatchId(match.getParentMatchId())
                .boardNumber(match.getBoardNumber())
                .status(match.getStatus())
                .hasScorekeeper(match.isHasScorekeeper())
                .scorekeeperUserId(match.getScorekeeperUserId())
                .notes(match.getNotes())
                .createdBy(match.getCreatedBy())
                .createdAt(match.getCreatedAt())
                .updatedAt(match.getUpdatedAt())
                .canEditMeta(canEditMeta)
                .canRecordTimeline(canRecordTimeline)
                .build();
    }
}
