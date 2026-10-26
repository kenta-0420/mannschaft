package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.dto.TeamMatchStatsResponse;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.match.repository.PlayerAppearanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * {@link MatchStatsAggregationService#aggregateTeamStats} の recentForm 純 UT。
 *
 * <p>直近 N 件への絞り込みと並び順（古い→新しい）を実アサートで検証する。
 * Docker/DB 不要（Mockito のみ）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchStatsAggregationServiceTest {

    private static final long ORG = 1L;
    private static final long TEAM = 10L;
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchEventRepository matchEventRepository;
    @Mock
    private PlayerAppearanceRepository appearanceRepository;
    @Mock
    private MatchService matchService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MatchStatsAggregationService service;

    /**
     * 試合が RECENT_FORM_SIZE(=5) 件より多い場合、recentForm が末尾 5 件（最新 5 試合）に絞られる。
     * 並び順は古い→新しい（kickoffAt ASC 順末尾）であること。
     */
    @Test
    @DisplayName("7試合分の結果がある場合、recentForm は末尾5件に絞られ古い→新しい順になる")
    void recentForm_trimmedToRecentFive_whenMoreThanFiveMatches() {
        // kickoffAt ASC 順で 7 試合を準備（findForTeamStats の返り値を模倣）。
        // 試合結果: W,W,L,D,W,L,W（古→新）。期待 recentForm = [L,D,W,L,W]（末尾5件）
        List<MatchEntity> matches = new ArrayList<>();
        int[][] scores = {
                {2, 0}, // W
                {3, 1}, // W
                {0, 1}, // L
                {1, 1}, // D
                {2, 1}, // W
                {0, 2}, // L
                {1, 0}  // W
        };
        for (int i = 0; i < scores.length; i++) {
            MatchEntity m = buildHomeMatch(TEAM, BASE.plusDays(i), scores[i][0], scores[i][1]);
            matches.add(m);
        }

        when(matchRepository.findForTeamStats(eq(ORG), eq(TEAM), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(matches);

        TeamMatchStatsResponse result = service.aggregateTeamStats(
                ORG, TEAM, null, null, null, null, false, 5);

        assertThat(result.getRecentForm())
                .as("recentForm は直近 5 試合（末尾5件）に絞られること")
                .hasSize(5)
                .as("並び順は古い→新しい順（先頭=3試合目=L・末尾=7試合目=W）")
                .containsExactly("L", "D", "W", "L", "W");
    }

    /**
     * 試合が RECENT_FORM_SIZE(=5) 件以下の場合、全件がそのまま返る。
     */
    @Test
    @DisplayName("3試合のみの場合、recentForm は全3件が古い→新しい順で返る")
    void recentForm_allReturned_whenFewerThanFiveMatches() {
        // W, D, L の 3 試合（古→新）
        List<MatchEntity> matches = List.of(
                buildHomeMatch(TEAM, BASE,            2, 0),
                buildHomeMatch(TEAM, BASE.plusDays(1), 1, 1),
                buildHomeMatch(TEAM, BASE.plusDays(2), 0, 3)
        );

        when(matchRepository.findForTeamStats(eq(ORG), eq(TEAM), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(matches);

        TeamMatchStatsResponse result = service.aggregateTeamStats(
                ORG, TEAM, null, null, null, null, false, 5);

        assertThat(result.getRecentForm())
                .as("5件未満のときは全件が返ること")
                .containsExactly("W", "D", "L");
    }

    // ── ヘルパー ──

    /**
     * HOME 試合（主体チーム=TEAM）のテスト用 MatchEntity を生成する。
     * home_score / away_score で W/D/L を制御する。
     */
    private MatchEntity buildHomeMatch(Long teamId, LocalDateTime kickoffAt,
                                       int homeScore, int awayScore) {
        MatchEntity m = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(teamId)
                .sport(Sport.SOCCER)
                .kind(MatchKind.FRIENDLY)
                .homeAway(HomeAway.HOME)
                .status(MatchStatus.COMPLETED)
                .kickoffAt(kickoffAt)
                .homeScore(homeScore)
                .awayScore(awayScore)
                .hasScorekeeper(false)
                .createdBy(99L)
                .build();
        m.setId(UUID.randomUUID());
        return m;
    }
}
