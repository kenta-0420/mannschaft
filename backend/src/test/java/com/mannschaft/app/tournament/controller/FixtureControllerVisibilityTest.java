package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.dto.FixtureResponse;
import com.mannschaft.app.tournament.dto.MatchdayResponse;
import com.mannschaft.app.tournament.dto.RosterResponse;
import com.mannschaft.app.tournament.service.FixtureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F08.7 順位UI Wave0 検分フォロー（B-2a）→ 認可根治戦役 Wave2 トランシェ2C 追随。
 *
 * <p>従来は {@link FixtureController} 自身が {@code ContentVisibilityChecker} を保持し GET 系の
 * 可視性ガードを行っていたが、Wave2 トランシェ2C で divId/matchId の親（tId）束縛検証を
 * 併せて行う必要が生じたため、可視性判定・束縛検証はいずれも {@link FixtureService} 側に集約した
 * （{@code FixtureServiceTest} 参照）。本テストはコントローラーが閲覧者 ID（認証済み/匿名）を
 * 正しく {@link FixtureService} へ委譲することのみを検証する（薄いアダプタとしての契約）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FixtureController — Service への委譲契約（B-2a→Wave2C）")
class FixtureControllerVisibilityTest {

    private static final Long ORG_ID = 100L;
    private static final Long T_ID = 7L;
    private static final Long DIV_ID = 11L;
    private static final Long MATCH_ID = 21L;
    private static final Long VIEWER = 5L;

    @Mock
    private FixtureService matchService;

    @InjectMocks
    private FixtureController controller;

    @Test
    @DisplayName("listMatchdays は認証済み閲覧者 ID を伝播して Service へ委譲する")
    void listMatchdays_delegatesWithViewerId() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
            when(matchService.listMatchdays(T_ID, DIV_ID, VIEWER)).thenReturn(java.util.List.<MatchdayResponse>of());

            assertThat(controller.listMatchdays(ORG_ID, T_ID, DIV_ID).getStatusCode().is2xxSuccessful()).isTrue();

            verify(matchService).listMatchdays(T_ID, DIV_ID, VIEWER);
        }
    }

    @Test
    @DisplayName("listMatchdays は匿名（userId=null）でも Service へ委譲する")
    void listMatchdays_delegatesAnonymous() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
            when(matchService.listMatchdays(T_ID, DIV_ID, null)).thenReturn(java.util.List.<MatchdayResponse>of());

            assertThat(controller.listMatchdays(ORG_ID, T_ID, DIV_ID).getStatusCode().is2xxSuccessful()).isTrue();

            verify(matchService).listMatchdays(T_ID, DIV_ID, null);
        }
    }

    @Test
    @DisplayName("getMatch は tId・matchId・閲覧者 ID を Service へ委譲する")
    void getMatch_delegates() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
            when(matchService.getMatch(T_ID, MATCH_ID, VIEWER)).thenReturn((FixtureResponse) null);

            controller.getMatch(ORG_ID, T_ID, MATCH_ID);

            verify(matchService).getMatch(T_ID, MATCH_ID, VIEWER);
        }
    }

    @Test
    @DisplayName("listRosters は tId・matchId・閲覧者 ID を Service へ委譲する")
    void listRosters_delegates() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
            when(matchService.listRosters(T_ID, MATCH_ID, VIEWER)).thenReturn(java.util.List.<RosterResponse>of());

            controller.listRosters(ORG_ID, T_ID, MATCH_ID);

            verify(matchService).listRosters(T_ID, MATCH_ID, VIEWER);
        }
    }
}
