package com.mannschaft.app.tournament;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link StandingsQueryService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandingsQueryService 単体テスト")
class StandingsQueryServiceTest {

    @Mock private TournamentStandingRepository standingRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentFixtureRepository matchRepository;
    @Mock private TournamentMapper mapper;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private StandingsQueryService service;

    /**
     * 正常系テストは「閲覧者が当該大会を閲覧できる」前提なので、可視性チェックを true に固定する。
     * フィルタが効いて不可視大会が除外される挙動は {@code StandingsQueryServiceVisibilityTest}（B-2b 番人）で担保する。
     */
    private void allowAllTournaments() {
        when(contentVisibilityChecker.canView(eq(ReferenceType.TOURNAMENT), any(), any()))
                .thenReturn(true);
    }

    // ---- テスト用ヘルパー ----

    /** 参加エンティティをリフレクション経由で生成するヘルパー */
    private TournamentParticipantEntity buildParticipant(Long id, Long divisionId, Long teamId) {
        return TournamentParticipantEntity.builder()
                .divisionId(divisionId)
                .teamId(teamId)
                .build();
    }

    /** ディビジョンエンティティをビルダーで生成するヘルパー */
    private TournamentDivisionEntity buildDivision(Long tournamentId, String name) {
        return TournamentDivisionEntity.builder()
                .tournamentId(tournamentId)
                .name(name)
                .build();
    }

    /** 大会エンティティをビルダーで生成するヘルパー */
    private TournamentEntity buildTournament(Long orgId, String name, String season) {
        return TournamentEntity.builder()
                .organizationId(orgId)
                .name(name)
                .season(season)
                .format(TournamentFormat.LEAGUE)
                .createdBy(1L)
                .build();
    }

    /** 順位エンティティをビルダーで生成するヘルパー */
    private TournamentStandingEntity buildStanding(Long divisionId, Long participantId,
                                                   int rank, int played, int wins,
                                                   int draws, int losses, int points,
                                                   int scoreFor, int scoreAgainst) {
        return TournamentStandingEntity.builder()
                .divisionId(divisionId)
                .participantId(participantId)
                .rank(rank)
                .played(played)
                .wins(wins)
                .draws(draws)
                .losses(losses)
                .points(points)
                .scoreFor(scoreFor)
                .scoreAgainst(scoreAgainst)
                .build();
    }

    @Nested
    @DisplayName("getTeamHistory")
    class GetTeamHistory {

        @Test
        @DisplayName("正常系: チームが2大会に参加しており、それぞれの履歴が返る")
        void チーム2大会の履歴() {
            Long teamId = 10L;
            allowAllTournaments();

            // participant1: divisionId=1, participant2: divisionId=2
            TournamentParticipantEntity p1 = buildParticipant(1L, 1L, teamId);
            TournamentParticipantEntity p2 = buildParticipant(2L, 2L, teamId);
            when(participantRepository.findAllByTeamId(teamId)).thenReturn(List.of(p1, p2));

            TournamentDivisionEntity div1 = buildDivision(100L, "1部");
            TournamentDivisionEntity div2 = buildDivision(200L, "A組");
            when(divisionRepository.findById(1L)).thenReturn(Optional.of(div1));
            when(divisionRepository.findById(2L)).thenReturn(Optional.of(div2));

            TournamentEntity t1 = buildTournament(50L, "春季大会", "2025");
            TournamentEntity t2 = buildTournament(50L, "秋季大会", "2024");
            when(tournamentRepository.findById(100L)).thenReturn(Optional.of(t1));
            when(tournamentRepository.findById(200L)).thenReturn(Optional.of(t2));

            TournamentStandingEntity s1 = buildStanding(1L, p1.getId(), 1, 10, 7, 2, 1, 23, 18, 5);
            TournamentStandingEntity s2 = buildStanding(2L, p2.getId(), 3, 8, 5, 1, 2, 16, 14, 8);
            when(standingRepository.findByDivisionIdAndParticipantId(1L, p1.getId()))
                    .thenReturn(Optional.of(s1));
            when(standingRepository.findByDivisionIdAndParticipantId(2L, p2.getId()))
                    .thenReturn(Optional.of(s2));

            TeamTournamentHistoryResponse result = service.getTeamHistory(teamId);

            assertThat(result.getTeamId()).isEqualTo(teamId);
            assertThat(result.getHistory()).hasSize(2);

            TeamTournamentHistoryResponse.TournamentHistoryEntry entry1 = result.getHistory().get(0);
            assertThat(entry1.getMeta().tournamentName()).isEqualTo("春季大会");
            assertThat(entry1.getMeta().season()).isEqualTo("2025");
            assertThat(entry1.getMeta().divisionName()).isEqualTo("1部");
            assertThat(entry1.getMeta().finalRank()).isEqualTo(1);
            assertThat(entry1.getRecord().played()).isEqualTo(10);
            assertThat(entry1.getRecord().wins()).isEqualTo(7);
            assertThat(entry1.getRecord().draws()).isEqualTo(2);
            assertThat(entry1.getRecord().losses()).isEqualTo(1);
            assertThat(entry1.getRecord().points()).isEqualTo(23);
            assertThat(entry1.getOrganizationId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("正常系: 順位表がまだ存在しない場合は finalRank=null・played=0 で返る")
        void 順位表なしの場合ゼロ値() {
            Long teamId = 20L;
            allowAllTournaments();

            TournamentParticipantEntity p = buildParticipant(1L, 1L, teamId);
            when(participantRepository.findAllByTeamId(teamId)).thenReturn(List.of(p));

            TournamentDivisionEntity div = buildDivision(100L, "B組");
            when(divisionRepository.findById(1L)).thenReturn(Optional.of(div));

            TournamentEntity t = buildTournament(50L, "春季大会", "2025");
            when(tournamentRepository.findById(100L)).thenReturn(Optional.of(t));

            // 順位表は空（p.getId() は null なので null を渡す）
            when(standingRepository.findByDivisionIdAndParticipantId(1L, null))
                    .thenReturn(Optional.empty());

            TeamTournamentHistoryResponse result = service.getTeamHistory(teamId);

            assertThat(result.getHistory()).hasSize(1);
            TeamTournamentHistoryResponse.TournamentHistoryEntry entry = result.getHistory().get(0);
            assertThat(entry.getMeta().finalRank()).isNull();
            assertThat(entry.getRecord().played()).isEqualTo(0);
            assertThat(entry.getRecord().wins()).isEqualTo(0);
            assertThat(entry.getRecord().draws()).isEqualTo(0);
            assertThat(entry.getRecord().losses()).isEqualTo(0);
            assertThat(entry.getRecord().points()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: 参加大会なしの場合は空リストが返る")
        void 参加大会なし() {
            Long teamId = 30L;
            when(participantRepository.findAllByTeamId(teamId)).thenReturn(List.of());

            TeamTournamentHistoryResponse result = service.getTeamHistory(teamId);

            assertThat(result.getTeamId()).isEqualTo(teamId);
            assertThat(result.getHistory()).isEmpty();
        }

        @Test
        @DisplayName("正常系: ディビジョンが存在しない参加エントリーはスキップされる")
        void ディビジョン不在スキップ() {
            Long teamId = 40L;

            TournamentParticipantEntity p = buildParticipant(1L, 999L, teamId);
            when(participantRepository.findAllByTeamId(teamId)).thenReturn(List.of(p));
            when(divisionRepository.findById(999L)).thenReturn(Optional.empty());

            TeamTournamentHistoryResponse result = service.getTeamHistory(teamId);

            assertThat(result.getHistory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getTeamStats")
    class GetTeamStats {

        @Test
        @DisplayName("正常系: 複数大会の合計成績が正しく集計される")
        void 複数大会合計集計() {
            Long teamId = 10L;
            allowAllTournaments();

            TournamentParticipantEntity p1 = buildParticipant(1L, 1L, teamId);
            TournamentParticipantEntity p2 = buildParticipant(2L, 2L, teamId);
            when(participantRepository.findAllByTeamId(teamId)).thenReturn(List.of(p1, p2));

            TournamentStandingEntity s1 = buildStanding(1L, p1.getId(), 1, 10, 7, 2, 1, 23, 18, 5);
            TournamentStandingEntity s2 = buildStanding(2L, p2.getId(), 3, 8, 5, 1, 2, 16, 14, 8);
            when(standingRepository.findByDivisionIdAndParticipantId(1L, p1.getId()))
                    .thenReturn(Optional.of(s1));
            when(standingRepository.findByDivisionIdAndParticipantId(2L, p2.getId()))
                    .thenReturn(Optional.of(s2));

            TournamentDivisionEntity div1 = buildDivision(100L, "1部");
            TournamentDivisionEntity div2 = buildDivision(200L, "A組");
            when(divisionRepository.findById(1L)).thenReturn(Optional.of(div1));
            when(divisionRepository.findById(2L)).thenReturn(Optional.of(div2));

            TeamTournamentStatsResponse result = service.getTeamStats(teamId);

            assertThat(result.getTeamId()).isEqualTo(teamId);
            assertThat(result.getTotalTournaments()).isEqualTo(2);   // 異なる大会ID
            assertThat(result.getTotalPlayed()).isEqualTo(18);       // 10 + 8
            assertThat(result.getTotalWins()).isEqualTo(12);         // 7 + 5
            assertThat(result.getTotalDraws()).isEqualTo(3);         // 2 + 1
            assertThat(result.getTotalLosses()).isEqualTo(3);        // 1 + 2
            assertThat(result.getTotalScoreFor()).isEqualTo(32);     // 18 + 14
            assertThat(result.getTotalScoreAgainst()).isEqualTo(13); // 5 + 8
            assertThat(result.getBestRank()).isEqualTo(1);           // min(1, 3) = 1
        }

        @Test
        @DisplayName("正常系: 参加大会なしの場合はゼロ集計で返る")
        void 参加大会なし() {
            Long teamId = 20L;
            when(participantRepository.findAllByTeamId(teamId)).thenReturn(List.of());

            TeamTournamentStatsResponse result = service.getTeamStats(teamId);

            assertThat(result.getTeamId()).isEqualTo(teamId);
            assertThat(result.getTotalTournaments()).isEqualTo(0);
            assertThat(result.getTotalPlayed()).isEqualTo(0);
            assertThat(result.getBestRank()).isNull();
        }

        @Test
        @DisplayName("正常系: 同一大会の複数ディビジョンに参加しても大会数は1カウント")
        void 同一大会複数ディビジョンは1カウント() {
            Long teamId = 30L;
            allowAllTournaments();

            // 同一 tournamentId=100 に2ディビジョン参加
            TournamentParticipantEntity p1 = buildParticipant(1L, 1L, teamId);
            TournamentParticipantEntity p2 = buildParticipant(2L, 2L, teamId);
            when(participantRepository.findAllByTeamId(teamId)).thenReturn(List.of(p1, p2));

            TournamentStandingEntity s1 = buildStanding(1L, p1.getId(), 2, 5, 3, 1, 1, 10, 8, 4);
            TournamentStandingEntity s2 = buildStanding(2L, p2.getId(), 1, 4, 4, 0, 0, 12, 9, 2);
            when(standingRepository.findByDivisionIdAndParticipantId(1L, p1.getId()))
                    .thenReturn(Optional.of(s1));
            when(standingRepository.findByDivisionIdAndParticipantId(2L, p2.getId()))
                    .thenReturn(Optional.of(s2));

            // 両ディビジョンとも同一大会ID=100
            TournamentDivisionEntity div1 = buildDivision(100L, "1部");
            TournamentDivisionEntity div2 = buildDivision(100L, "2部");
            when(divisionRepository.findById(1L)).thenReturn(Optional.of(div1));
            when(divisionRepository.findById(2L)).thenReturn(Optional.of(div2));

            TeamTournamentStatsResponse result = service.getTeamStats(teamId);

            assertThat(result.getTotalTournaments()).isEqualTo(1); // 重複排除
        }
    }
}
