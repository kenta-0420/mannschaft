package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.FixtureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F08.7 順位UI Wave0 検分フォロー（B-2a） — {@link FixtureController} GET 可視性ガード番人テスト。
 *
 * <p>従来 FixtureController の GET 系（節一覧・試合詳細・出場メンバー一覧）には可視性ガードが無く、
 * 認証さえあれば非公開大会の対戦カード・結果・出場メンバーを素通しで閲覧できる漏洩穴になっていた。
 * 本テストは GET 系に F00 共通可視性ガード（{@code contentVisibilityChecker.canView(TOURNAMENT, tId, viewer)}・
 * 不可視は IDOR 防止のため 404）が挿入されていることを検証する。可視性は常に class 階層パスの親
 * tournament（{@code tId}）で判定される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FixtureController — GET 可視性ガード番人（B-2a）")
class FixtureControllerVisibilityTest {

    private static final Long ORG_ID = 100L;
    private static final Long T_ID = 7L;
    private static final Long DIV_ID = 11L;
    private static final Long MATCH_ID = 21L;
    private static final Long VIEWER = 5L;

    @Mock
    private FixtureService matchService;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private FixtureController controller;

    @Nested
    @DisplayName("不可視（canView=false）のとき 404 を投げ、サービスを呼ばない")
    class Denied {

        @Test
        @DisplayName("listMatchdays は 404 でブロックされる")
        void listMatchdays_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.listMatchdays(ORG_ID, T_ID, DIV_ID))
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode")
                        .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);

                verifyNoInteractions(matchService);
            }
        }

        @Test
        @DisplayName("getMatch は 404 でブロックされる")
        void getMatch_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.getMatch(ORG_ID, T_ID, MATCH_ID))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(matchService);
            }
        }

        @Test
        @DisplayName("listRosters は 404 でブロックされる")
        void listRosters_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.listRosters(ORG_ID, T_ID, MATCH_ID))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(matchService);
            }
        }

        @Test
        @DisplayName("未認証（userId=null）も canView に委譲され、不可視なら 404")
        void anonymous_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, null))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.getMatch(ORG_ID, T_ID, MATCH_ID))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(matchService);
            }
        }
    }

    @Nested
    @DisplayName("可視（canView=true）のとき canView を経てサービスへ委譲する")
    class Allowed {

        @Test
        @DisplayName("listMatchdays は可視時に listMatchdays(divId) を呼ぶ")
        void listMatchdays_allowed_delegates() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(true);
                when(matchService.listMatchdays(DIV_ID)).thenReturn(java.util.List.of());

                assertThat(controller.listMatchdays(ORG_ID, T_ID, DIV_ID).getStatusCode().is2xxSuccessful())
                        .isTrue();

                // 可視性は親 tournament（T_ID）で判定する（divId ではない）
                verify(contentVisibilityChecker).canView(eq(ReferenceType.TOURNAMENT), eq(T_ID), eq(VIEWER));
                verify(matchService).listMatchdays(DIV_ID);
            }
        }

        @Test
        @DisplayName("getMatch は可視時に getMatch(matchId) を呼ぶ")
        void getMatch_allowed_delegates() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(true);

                controller.getMatch(ORG_ID, T_ID, MATCH_ID);

                verify(matchService).getMatch(MATCH_ID);
            }
        }
    }
}
