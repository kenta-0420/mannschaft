package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.7.1 slug 修正: {@link StandingsController} のチーム横断成績 EP（{@code tournament-stats} /
 * {@code tournament-history}）が path 変数に slug を受理する契約テスト。
 *
 * <p>ダッシュボードの「自チーム成績」「順位表」ウィジェットは URL に slug（例 {@code team-000017}）を
 * 渡す。コントローラは {@code @PathVariable String teamId} を受け、{@link TeamService#resolveTeamId(String)}
 * で内部 BIGINT に解決してからサービスへ渡す。旧実装は {@code @PathVariable Long} で slug を受けると
 * Spring の型変換に失敗して 400 になり、ウィジェットが（{@code captureQuiet}+空配列で握り潰され）空表示に
 * なっていた。survey の {@code resolveScopeId} の流儀に整合させる。</p>
 *
 * <p>Spring の WebMvc フルコンテキスト依存を避け、{@code standaloneSetup} ＋
 * {@link GlobalExceptionHandler} で契約のみ検証する。可視性ガードは org 単位の
 * tournament path を持つ EP のみに掛かり、チーム横断 EP は {@code StandingsQueryService} 側で
 * per-tournament フィルタを掛けるため、本テストはコントローラ→サービスの slug 解決のみを対象とする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandingsController — チーム成績 EP の slug 受理契約")
class StandingsControllerTeamSlugContractTest {

    private static final String TEAM_SLUG = "team-000017";
    private static final long RESOLVED_TEAM_ID = 42L;

    @Mock
    private StandingsQueryService standingsQueryService;
    @Mock
    private StandingsCalculationService standingsCalculationService;
    @Mock
    private RankingsCalculationService rankingsCalculationService;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;
    @Mock
    private TeamService teamService;

    @InjectMocks
    private StandingsController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @Test
    @DisplayName("GET tournament-stats は slug を解決して 200 を返す")
    void teamStats_slug_resolved_200() throws Exception {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(RESOLVED_TEAM_ID);
        given(standingsQueryService.getTeamStats(RESOLVED_TEAM_ID))
                .willReturn(new TeamTournamentStatsResponse(
                        RESOLVED_TEAM_ID, 0, 0, 0, 0, 0, 0, 0, null));

        mockMvc.perform(get("/api/v1/teams/" + TEAM_SLUG + "/tournament-stats"))
                .andExpect(status().isOk());

        verify(teamService).resolveTeamId(TEAM_SLUG);
        verify(standingsQueryService).getTeamStats(RESOLVED_TEAM_ID);
    }

    @Test
    @DisplayName("GET tournament-history は slug を解決して 200 を返す")
    void teamHistory_slug_resolved_200() throws Exception {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(RESOLVED_TEAM_ID);
        given(standingsQueryService.getTeamHistory(RESOLVED_TEAM_ID))
                .willReturn(TeamTournamentHistoryResponse.builder()
                        .teamId(RESOLVED_TEAM_ID)
                        .history(List.of())
                        .build());

        mockMvc.perform(get("/api/v1/teams/" + TEAM_SLUG + "/tournament-history"))
                .andExpect(status().isOk());

        verify(teamService).resolveTeamId(TEAM_SLUG);
        verify(standingsQueryService).getTeamHistory(RESOLVED_TEAM_ID);
    }

    @Test
    @DisplayName("ApiResponse でラップして返す（契約の回帰防止）")
    void teamStats_returns_apiresponse() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(RESOLVED_TEAM_ID);
        given(standingsQueryService.getTeamStats(RESOLVED_TEAM_ID))
                .willReturn(new TeamTournamentStatsResponse(
                        RESOLVED_TEAM_ID, 1, 2, 3, 4, 5, 6, 7, 1));

        ApiResponse<TeamTournamentStatsResponse> body =
                controller.getTeamStats(TEAM_SLUG).getBody();

        org.assertj.core.api.Assertions.assertThat(body).isNotNull();
        org.assertj.core.api.Assertions.assertThat(body.getData().getTeamId())
                .isEqualTo(RESOLVED_TEAM_ID);
    }
}
