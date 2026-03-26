package com.mannschaft.app.tournament;

import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StandingsQueryService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandingsQueryService 単体テスト")
class StandingsQueryServiceTest {

    @Mock private TournamentStandingRepository standingRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentMatchRepository matchRepository;
    @Mock private TournamentMapper mapper;

    @InjectMocks
    private StandingsQueryService service;

    @Nested
    @DisplayName("getTeamHistory")
    class GetTeamHistory {

        @Test
        @DisplayName("正常系: チーム履歴が空リストで返却される（簡易実装）")
        void チーム履歴空リスト() {
            TeamTournamentHistoryResponse result = service.getTeamHistory(1L);

            assertThat(result.getTeamId()).isEqualTo(1L);
            assertThat(result.getHistory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getTeamStats")
    class GetTeamStats {

        @Test
        @DisplayName("正常系: チーム通算成績が返却される（簡易実装）")
        void チーム通算成績() {
            TeamTournamentStatsResponse result = service.getTeamStats(1L);

            assertThat(result.getTeamId()).isEqualTo(1L);
        }
    }
}
