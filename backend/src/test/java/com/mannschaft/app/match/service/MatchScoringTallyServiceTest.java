package com.mannschaft.app.match.service;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.dto.MatchScoringTally;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link MatchScoringTallyService} の単体テスト（F08.10 05 §H.2.2・得点/アシスト集計の正本化）。
 *
 * <p>得点 = {@code GOAL + PENALTY_GOAL}（PK 戦 {@code PENALTY_SHOOTOUT} と {@code PENALTY_MISS} は除外）、
 * アシスト = {@code ASSIST}。{@code MatchStatsAggregationService} の既存定義と一致することを保証する。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchScoringTallyServiceTest {

    @Mock
    private MatchEventRepository matchEventRepository;

    @InjectMocks
    private MatchScoringTallyService service;

    private static MatchEventEntity event(Long playerUserId, TeamSide side, MatchEventType type) {
        return MatchEventEntity.builder()
                .matchId(UUID.randomUUID())
                .playerUserId(playerUserId)
                .teamSide(side)
                .eventType(type)
                .period(com.mannschaft.app.match.domain.PeriodType.FIRST_HALF)
                .sortSeq(0)
                .build();
    }

    @Test
    @DisplayName("得点（GOAL/PENALTY_GOAL）とアシスト（ASSIST）を選手別に集計する")
    void tallies_goals_and_assists_per_player() {
        UUID matchId = UUID.randomUUID();
        given(matchEventRepository.findByMatchId(matchId)).willReturn(List.of(
                event(10L, TeamSide.HOME, MatchEventType.GOAL),
                event(10L, TeamSide.HOME, MatchEventType.PENALTY_GOAL),
                event(20L, TeamSide.HOME, MatchEventType.ASSIST),
                event(30L, TeamSide.AWAY, MatchEventType.GOAL)));

        List<MatchScoringTally> tallies = service.tallyScoringStatsForMatch(matchId);

        Map<Long, MatchScoringTally> byUser = tallies.stream()
                .collect(Collectors.toMap(MatchScoringTally::playerUserId, Function.identity()));
        assertThat(byUser.get(10L).goals()).isEqualTo(2);
        assertThat(byUser.get(10L).assists()).isEqualTo(0);
        assertThat(byUser.get(10L).teamSide()).isEqualTo(TeamSide.HOME);
        assertThat(byUser.get(20L).assists()).isEqualTo(1);
        assertThat(byUser.get(20L).goals()).isEqualTo(0);
        assertThat(byUser.get(30L).goals()).isEqualTo(1);
        assertThat(byUser.get(30L).teamSide()).isEqualTo(TeamSide.AWAY);
    }

    @Test
    @DisplayName("PK戦（PENALTY_SHOOTOUT）・PK失敗（PENALTY_MISS）・OWN_GOAL・カードは得点/アシストに数えない")
    void excludes_shootout_miss_owngoal_and_cards() {
        UUID matchId = UUID.randomUUID();
        given(matchEventRepository.findByMatchId(matchId)).willReturn(List.of(
                event(10L, TeamSide.HOME, MatchEventType.PENALTY_SHOOTOUT),
                event(10L, TeamSide.HOME, MatchEventType.PENALTY_MISS),
                event(10L, TeamSide.HOME, MatchEventType.OWN_GOAL),
                event(10L, TeamSide.HOME, MatchEventType.YELLOW_CARD)));

        List<MatchScoringTally> tallies = service.tallyScoringStatsForMatch(matchId);

        // 得点/アシスト 0 の選手は集計に含めない（snapshot に 0 行を量産しないため）
        assertThat(tallies).isEmpty();
    }

    @Test
    @DisplayName("player_user_id が null のイベント（未登録選手）は集計対象外")
    void skips_null_player() {
        UUID matchId = UUID.randomUUID();
        given(matchEventRepository.findByMatchId(matchId)).willReturn(List.of(
                event(null, TeamSide.HOME, MatchEventType.GOAL),
                event(10L, TeamSide.HOME, MatchEventType.GOAL)));

        List<MatchScoringTally> tallies = service.tallyScoringStatsForMatch(matchId);

        assertThat(tallies).hasSize(1);
        assertThat(tallies.get(0).playerUserId()).isEqualTo(10L);
        assertThat(tallies.get(0).goals()).isEqualTo(1);
    }

    @Test
    @DisplayName("イベントが無い試合は空リストを返す")
    void empty_events_returns_empty() {
        UUID matchId = UUID.randomUUID();
        given(matchEventRepository.findByMatchId(matchId)).willReturn(List.of());

        assertThat(service.tallyScoringStatsForMatch(matchId)).isEmpty();
    }

    @Test
    @DisplayName("同一選手が両 side に出る異常データでも side 別に分けて集計する")
    void separates_by_side_when_same_player_appears_on_both_sides() {
        UUID matchId = UUID.randomUUID();
        given(matchEventRepository.findByMatchId(matchId)).willReturn(List.of(
                event(10L, TeamSide.HOME, MatchEventType.GOAL),
                event(10L, TeamSide.AWAY, MatchEventType.GOAL)));

        List<MatchScoringTally> tallies = service.tallyScoringStatsForMatch(matchId);

        assertThat(tallies).hasSize(2);
        assertThat(tallies).extracting(MatchScoringTally::teamSide)
                .containsExactlyInAnyOrder(TeamSide.HOME, TeamSide.AWAY);
    }
}
