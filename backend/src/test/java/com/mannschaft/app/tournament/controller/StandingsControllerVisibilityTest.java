package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F08.7 順位UI Wave0 — {@link StandingsController} 可視性ガード番人テスト。
 *
 * <p>認証系の順位/ランキング/マトリクス参照 EP に F00 共通可視性ガードが挿入されていることを
 * 検証する。以前はガードが完全に欠落しており、認証さえあれば非公開大会の順位を素通しで閲覧できる
 * セキュリティ穴になっていた。本テストはそのガードが外れたら落ちる番人として機能する。</p>
 *
 * <p>6 レベル × 閲覧者ロールの実判定は {@link ReferenceType#TOURNAMENT} の
 * {@code TournamentVisibilityResolver}（単体テスト済）に委譲されるため、ここでは
 * 「{@link ContentVisibilityChecker#canView} が tournamentId と現在ユーザーで必ず呼ばれること」
 * 「不可視（false）のとき 404（{@link TournamentErrorCode#TOURNAMENT_NOT_FOUND}）を投げ、
 * 配下サービスを一切呼ばないこと」「可視（true）のときサービスへ委譲すること」を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandingsController — 可視性ガード番人")
class StandingsControllerVisibilityTest {

    private static final Long ORG_ID = 100L;
    private static final Long T_ID = 7L;
    private static final Long DIV_ID = 11L;
    private static final Long VIEWER = 5L;

    @Mock
    private StandingsQueryService standingsQueryService;
    @Mock
    private StandingsCalculationService standingsCalculationService;
    @Mock
    private RankingsCalculationService rankingsCalculationService;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private StandingsController controller;

    @Nested
    @DisplayName("不可視（canView=false）のとき 404 を投げ、サービスを呼ばない")
    class Denied {

        @Test
        @DisplayName("getStandings は 404 でブロックされる")
        void standings_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.getStandings(ORG_ID, T_ID, DIV_ID))
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode")
                        .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);

                verifyNoInteractions(standingsQueryService);
            }
        }

        @Test
        @DisplayName("getMatrix は 404 でブロックされる")
        void matrix_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.getMatrix(ORG_ID, T_ID, DIV_ID))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(standingsQueryService);
            }
        }

        @Test
        @DisplayName("getRankings は 404 でブロックされる")
        void rankings_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.getRankings(ORG_ID, T_ID, "goals", 0, 50))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(rankingsCalculationService);
            }
        }

        @Test
        @DisplayName("getRankingSummary は 404 でブロックされる")
        void ranking_summary_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.getRankingSummary(ORG_ID, T_ID))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(rankingsCalculationService);
            }
        }

        @Test
        @DisplayName("recalculate は 404 でブロックされる")
        void recalculate_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.recalculate(ORG_ID, T_ID, DIV_ID))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(standingsCalculationService);
            }
        }

        @Test
        @DisplayName("未認証（userId=null）も canView に委譲され、不可視なら 404")
        void anonymous_denied() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, null))
                        .thenReturn(false);

                assertThatThrownBy(() -> controller.getStandings(ORG_ID, T_ID, DIV_ID))
                        .isInstanceOf(BusinessException.class);
                verifyNoInteractions(standingsQueryService);
            }
        }
    }

    @Nested
    @DisplayName("可視（canView=true）のとき canView を経てサービスへ委譲する")
    class Allowed {

        @Test
        @DisplayName("getStandings は可視時に getStandings(divId) を呼ぶ")
        void standings_allowed_delegates() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, T_ID, VIEWER))
                        .thenReturn(true);
                when(standingsQueryService.getStandings(DIV_ID)).thenReturn(java.util.List.of());

                assertThat(controller.getStandings(ORG_ID, T_ID, DIV_ID).getStatusCode().is2xxSuccessful())
                        .isTrue();

                // 可視性は親 tournament（T_ID）で判定する（divId ではない）
                verify(contentVisibilityChecker).canView(eq(ReferenceType.TOURNAMENT), eq(T_ID), eq(VIEWER));
                verify(standingsQueryService).getStandings(DIV_ID);
            }
        }

        @Test
        @DisplayName("recalculate は可視時に recalculate(divId, tId) を呼ぶ")
        void recalculate_allowed_delegates() {
            try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
                sec.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER);
                when(contentVisibilityChecker.canView(any(), eq(T_ID), eq(VIEWER))).thenReturn(true);

                controller.recalculate(ORG_ID, T_ID, DIV_ID);

                verify(standingsCalculationService).recalculate(DIV_ID, T_ID);
            }
        }
    }

    @Nested
    @DisplayName("認可（B-1/🟡）: 書込系 recalculate は org admin 限定の SpEL ガードを宣言している")
    class RecalculateAuthorization {

        @Test
        @DisplayName("recalculate は @accessGuard.isScopeAdmin(..., #orgId, 'ORGANIZATION') を宣言している")
        void recalculate_declares_org_admin_guard() throws NoSuchMethodException {
            Method m = StandingsController.class.getMethod(
                    "recalculate", Long.class, Long.class, Long.class);
            PreAuthorize annotation = m.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("recalculate に @PreAuthorize が無いと読取権限だけで再計算を起動できてしまう")
                    .isNotNull();
            assertThat(annotation.value())
                    .isEqualTo("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')");
        }

        @Test
        @DisplayName("読取系（getStandings 等）には org admin ガードを付けない（一般会員の閲覧を塞がない）")
        void read_handlers_not_admin_gated() throws NoSuchMethodException {
            Method standings = StandingsController.class.getMethod(
                    "getStandings", Long.class, Long.class, Long.class);
            assertThat(standings.getAnnotation(PreAuthorize.class))
                    .as("順位表 GET に org admin ガードが付くと一般会員が順位を見られなくなる")
                    .isNull();
        }
    }
}
