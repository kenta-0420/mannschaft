package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * F08.7 順位UI Wave0 検分フォロー（B-2b） — {@link StandingsQueryService} のチーム横断集計
 * （{@code getTeamHistory}/{@code getTeamStats}）における per-tournament 可視性フィルタ番人テスト。
 *
 * <p>これらの EP は大会単位の tId を path に持たないチーム横断集計のため、従来は閲覧者が見られない
 * 非公開大会の順位・成績もそのまま返してしまう漏洩経路だった。本テストは閲覧者が {@code canView} できない
 * 大会の成績が履歴・通算成績の双方から除外されることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandingsQueryService — チーム横断集計の可視性フィルタ番人（B-2b）")
class StandingsQueryServiceVisibilityTest {

    private static final Long TEAM_ID = 50L;
    private static final Long VIEWER = 9L;

    // 可視大会 / 非可視大会
    private static final Long T_VISIBLE = 100L;
    private static final Long T_HIDDEN = 200L;
    private static final Long DIV_VISIBLE = 1L;
    private static final Long DIV_HIDDEN = 2L;
    private static final Long PART_VISIBLE = 11L;
    private static final Long PART_HIDDEN = 22L;

    @Mock private TournamentStandingRepository standingRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentMatchRepository matchRepository;
    @Mock private TournamentMapper mapper;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private StandingsQueryService service;

    private TournamentParticipantEntity participant(Long id, Long divisionId) {
        TournamentParticipantEntity p = mock(TournamentParticipantEntity.class);
        lenient().when(p.getId()).thenReturn(id);
        lenient().when(p.getDivisionId()).thenReturn(divisionId);
        return p;
    }

    private TournamentDivisionEntity division(Long id, Long tournamentId) {
        TournamentDivisionEntity d = mock(TournamentDivisionEntity.class);
        lenient().when(d.getId()).thenReturn(id);
        lenient().when(d.getTournamentId()).thenReturn(tournamentId);
        lenient().when(d.getName()).thenReturn("Div " + id);
        return d;
    }

    private TournamentEntity tournament(Long id) {
        TournamentEntity t = mock(TournamentEntity.class);
        lenient().when(t.getId()).thenReturn(id);
        lenient().when(t.getName()).thenReturn("T " + id);
        lenient().when(t.getSeason()).thenReturn("2026");
        lenient().when(t.getOrganizationId()).thenReturn(1L);
        return t;
    }

    private TournamentStandingEntity standing() {
        TournamentStandingEntity s = mock(TournamentStandingEntity.class);
        lenient().when(s.getRank()).thenReturn(1);
        lenient().when(s.getPlayed()).thenReturn(10);
        lenient().when(s.getWins()).thenReturn(6);
        lenient().when(s.getDraws()).thenReturn(2);
        lenient().when(s.getLosses()).thenReturn(2);
        lenient().when(s.getPoints()).thenReturn(20);
        lenient().when(s.getScoreFor()).thenReturn(18);
        lenient().when(s.getScoreAgainst()).thenReturn(9);
        return s;
    }

    private void wireTwoTournaments() {
        // 先に全エンティティ mock を生成してから stub する
        // （when(...).thenReturn(<内部で when を呼ぶ式>) のネストは UnfinishedStubbingException になる）。
        TournamentParticipantEntity pVisible = participant(PART_VISIBLE, DIV_VISIBLE);
        TournamentParticipantEntity pHidden = participant(PART_HIDDEN, DIV_HIDDEN);
        TournamentDivisionEntity dVisible = division(DIV_VISIBLE, T_VISIBLE);
        TournamentDivisionEntity dHidden = division(DIV_HIDDEN, T_HIDDEN);
        TournamentEntity tVisible = tournament(T_VISIBLE);
        TournamentEntity tHidden = tournament(T_HIDDEN);
        TournamentStandingEntity sVisible = standing();
        TournamentStandingEntity sHidden = standing();

        when(participantRepository.findAllByTeamId(TEAM_ID)).thenReturn(List.of(pVisible, pHidden));

        lenient().when(divisionRepository.findById(DIV_VISIBLE)).thenReturn(Optional.of(dVisible));
        lenient().when(divisionRepository.findById(DIV_HIDDEN)).thenReturn(Optional.of(dHidden));

        lenient().when(tournamentRepository.findById(T_VISIBLE)).thenReturn(Optional.of(tVisible));
        lenient().when(tournamentRepository.findById(T_HIDDEN)).thenReturn(Optional.of(tHidden));

        lenient().when(standingRepository.findByDivisionIdAndParticipantId(DIV_VISIBLE, PART_VISIBLE))
                .thenReturn(Optional.of(sVisible));
        lenient().when(standingRepository.findByDivisionIdAndParticipantId(DIV_HIDDEN, PART_HIDDEN))
                .thenReturn(Optional.of(sHidden));

        // T_VISIBLE は閲覧可、T_HIDDEN は不可視
        when(contentVisibilityChecker.canView(eq(ReferenceType.TOURNAMENT), eq(T_VISIBLE), eq(VIEWER)))
                .thenReturn(true);
        when(contentVisibilityChecker.canView(eq(ReferenceType.TOURNAMENT), eq(T_HIDDEN), eq(VIEWER)))
                .thenReturn(false);
    }

    @Test
    @DisplayName("getTeamHistory は不可視大会のエントリを除外する")
    void getTeamHistory_excludes_hidden_tournament() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
            wireTwoTournaments();

            TeamTournamentHistoryResponse result = service.getTeamHistory(TEAM_ID);

            assertThat(result.getHistory())
                    .as("可視大会 1 件のみが残り、非公開大会は除外される")
                    .hasSize(1);
            assertThat(result.getHistory().get(0).getIdentifiers().tournamentId())
                    .isEqualTo(T_VISIBLE);
        }
    }

    @Test
    @DisplayName("getTeamStats は不可視大会の成績を通算集計から除外する")
    void getTeamStats_excludes_hidden_tournament() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
            wireTwoTournaments();

            TeamTournamentStatsResponse result = service.getTeamStats(TEAM_ID);

            // 可視大会 1 件分（played=10）のみが集計され、非公開大会分は加算されない
            assertThat(result.getTotalTournaments())
                    .as("可視大会 1 件のみカウントされる")
                    .isEqualTo(1);
            assertThat(result.getTotalPlayed())
                    .as("可視大会 1 件分（10 試合）のみ集計され、非公開大会の 10 試合は除外される")
                    .isEqualTo(10);
        }
    }
}
