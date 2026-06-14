package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link MatchTurnResultService} の純 UT（test-first・sports/05_shogi.md §4 / 06_go.md §4 / 01 §B.1.2 / §B.6）。
 *
 * <p>個人戦勝敗格納（1-0/0-1/0-0）・win_method 検証・団体戦の勝ち星集計（子DRAW 0.5 畳み込み・親同数=DRAW）・
 * ターン制以外への操作拒否・子ボード IDOR を Mockito モックで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchTurnResultService（ターン制対局結果・団体戦集計）UT")
class MatchTurnResultServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchService matchService;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MatchTurnResultService service;

    /** matchId -> entity の擬似ストア。 */
    private final java.util.Map<UUID, MatchEntity> store = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(matchRepository.save(any())).thenAnswer(inv -> {
            MatchEntity m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(UUID.randomUUID());
            }
            store.put(m.getId(), m);
            return m;
        });
        lenient().when(matchService.getMatchOrThrow(any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            MatchEntity m = store.get(id);
            if (m == null) {
                throw new BusinessException(MatchErrorCode.MATCH_001);
            }
            return m;
        });
        lenient().when(matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(any(), any()))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    Long org = inv.getArgument(1);
                    MatchEntity m = store.get(id);
                    if (m == null || !org.equals(m.getOrganizationId())) {
                        return Optional.empty();
                    }
                    return Optional.of(m);
                });
        lenient().when(matchRepository.findByParentMatchIdOrderByBoardNumberAsc(any()))
                .thenAnswer(inv -> {
                    UUID parentId = inv.getArgument(0);
                    List<MatchEntity> boards = new ArrayList<>();
                    for (MatchEntity m : store.values()) {
                        if (parentId.equals(m.getParentMatchId())) {
                            boards.add(m);
                        }
                    }
                    boards.sort(java.util.Comparator.comparing(MatchEntity::getBoardNumber));
                    return boards;
                });
    }

    private MatchEntity newMatch(Sport sport, UUID parentMatchId, Integer boardNumber) {
        MatchEntity m = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .sport(sport)
                .stateModel(sport.stateModel())
                .status(MatchStatus.IN_PROGRESS)
                .parentMatchId(parentMatchId)
                .boardNumber(boardNumber)
                .createdBy(ACTOR)
                .build();
        m.setId(UUID.randomUUID());
        store.put(m.getId(), m);
        return m;
    }

    // ─── 個人戦勝敗格納（§B.1.2） ──────────────────────────────

    @Test
    @DisplayName("HOME勝ち: home_score=1/away_score=0・win_method 保持（将棋・投了）")
    void home勝ち() {
        MatchEntity m = newMatch(Sport.SHOGI, null, null);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(TeamSide.HOME).winMethod("RESIGNATION").totalMoves(101).build();

        MatchEntity saved = service.recordIndividualResult(m.getId(), ORG, ACTOR, cmd);

        assertThat(saved.getHomeScore()).isEqualTo(1);
        assertThat(saved.getAwayScore()).isZero();
        assertThat(saved.getWinMethod()).isEqualTo("RESIGNATION");
        assertThat(saved.getTotalMoves()).isEqualTo(101);
    }

    @Test
    @DisplayName("AWAY勝ち: home_score=0/away_score=1（囲碁・目数差勝ち）")
    void away勝ち() {
        MatchEntity m = newMatch(Sport.GO, null, null);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(TeamSide.AWAY).winMethod("POINTS_WIN").build();

        MatchEntity saved = service.recordIndividualResult(m.getId(), ORG, ACTOR, cmd);

        assertThat(saved.getHomeScore()).isZero();
        assertThat(saved.getAwayScore()).isEqualTo(1);
        assertThat(saved.getWinMethod()).isEqualTo("POINTS_WIN");
    }

    @Test
    @DisplayName("引分: home_score=0/away_score=0・win_method=NULL（千日手/持碁）")
    void 引分() {
        MatchEntity m = newMatch(Sport.SHOGI, null, null);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(null).winMethod(null).build();

        MatchEntity saved = service.recordIndividualResult(m.getId(), ORG, ACTOR, cmd);

        assertThat(saved.getHomeScore()).isZero();
        assertThat(saved.getAwayScore()).isZero();
        assertThat(saved.getWinMethod()).isNull();
    }

    // ─── win_method 検証（§D.7） ──────────────────────────────

    @Test
    @DisplayName("勝敗ありで win_method=NULL は 400（MATCH_028）")
    void 勝敗ありでwin_method欠落は400() {
        MatchEntity m = newMatch(Sport.SHOGI, null, null);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(TeamSide.HOME).winMethod(null).build();

        assertThatThrownBy(() -> service.recordIndividualResult(m.getId(), ORG, ACTOR, cmd))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_028);
    }

    @Test
    @DisplayName("競技間流用の win_method は 400（将棋に囲碁の POINTS_WIN）")
    void 競技間流用は400() {
        MatchEntity m = newMatch(Sport.SHOGI, null, null);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(TeamSide.HOME).winMethod("POINTS_WIN").build();

        assertThatThrownBy(() -> service.recordIndividualResult(m.getId(), ORG, ACTOR, cmd))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_028);
    }

    @Test
    @DisplayName("引分なのに win_method を付けると 400（責務分離違反）")
    void 引分にwin_method付与は400() {
        MatchEntity m = newMatch(Sport.GO, null, null);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(null).winMethod("RESIGNATION").build();

        assertThatThrownBy(() -> service.recordIndividualResult(m.getId(), ORG, ACTOR, cmd))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_028);
    }

    @Test
    @DisplayName("ターン制以外（サッカー）への対局結果記録は 400（MATCH_029）")
    void 球技への記録は400() {
        MatchEntity m = newMatch(Sport.SOCCER, null, null);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(TeamSide.HOME).winMethod("RESIGNATION").build();

        assertThatThrownBy(() -> service.recordIndividualResult(m.getId(), ORG, ACTOR, cmd))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_029);
    }

    // ─── 団体戦の勝ち星集計（§4.3 / §B.6） ──────────────────────

    @Test
    @DisplayName("団体戦 3勝2敗 → 親 HOME 勝ち（勝ち星スケール 6-4）")
    void 団体戦3勝2敗で親home勝ち() {
        MatchEntity parent = newMatch(Sport.SHOGI, null, null);
        // 子 5 ボード: HOME 3 勝 / AWAY 2 勝
        recordBoard(parent.getId(), 1, TeamSide.HOME);
        recordBoard(parent.getId(), 2, TeamSide.HOME);
        recordBoard(parent.getId(), 3, TeamSide.HOME);
        recordBoard(parent.getId(), 4, TeamSide.AWAY);
        recordBoard(parent.getId(), 5, TeamSide.AWAY);

        MatchEntity reloaded = store.get(parent.getId());
        // 勝ち=2 スケール: HOME 3*2=6 / AWAY 2*2=4
        assertThat(reloaded.getHomeScore()).isEqualTo(6);
        assertThat(reloaded.getAwayScore()).isEqualTo(4);
        assertThat(reloaded.getWinMethod()).isNull(); // 親は勝ち方を持たない
        assertThat(reloaded.getHomeScore()).isGreaterThan(reloaded.getAwayScore()); // 親 HOME 勝ち（resolveResult=W）
    }

    @Test
    @DisplayName("子DRAW 0.5 畳み込み: 2勝2敗1分 → 親同点（5-5・親 DRAW）")
    void 子DRAW畳み込みで親同点() {
        MatchEntity parent = newMatch(Sport.GO, null, null);
        recordBoard(parent.getId(), 1, TeamSide.HOME);
        recordBoard(parent.getId(), 2, TeamSide.HOME);
        recordBoard(parent.getId(), 3, TeamSide.AWAY);
        recordBoard(parent.getId(), 4, TeamSide.AWAY);
        recordBoardDraw(parent.getId(), 5); // 引分ボード = 両者 0.5（スケール各 1）

        MatchEntity reloaded = store.get(parent.getId());
        // HOME 2*2 + 1 = 5 / AWAY 2*2 + 1 = 5
        assertThat(reloaded.getHomeScore()).isEqualTo(5);
        assertThat(reloaded.getAwayScore()).isEqualTo(5);
        // 親同数 → 親 DRAW（win_method=NULL・スコア同点で resolveResult=D）
        assertThat(reloaded.getHomeScore()).isEqualTo(reloaded.getAwayScore());
        assertThat(reloaded.getWinMethod()).isNull();
    }

    @Test
    @DisplayName("未確定ボードは集計対象外（結果未入力ボードは勝ち星に算入しない）")
    void 未確定ボードは集計対象外() {
        MatchEntity parent = newMatch(Sport.SHOGI, null, null);
        recordBoard(parent.getId(), 1, TeamSide.HOME);
        // ボード 2 は作成のみ（結果未入力＝home/away_score とも NULL）
        newMatch(Sport.SHOGI, parent.getId(), 2);
        service.recomputeParentFromBoards(parent.getId(), ORG);

        MatchEntity reloaded = store.get(parent.getId());
        assertThat(reloaded.getHomeScore()).isEqualTo(2); // HOME 1 勝のみ算入（2 スケール）
        assertThat(reloaded.getAwayScore()).isZero();
    }

    @Test
    @DisplayName("子ボードの結果確定で親が自動再集計される")
    void 子ボード確定で親自動再集計() {
        MatchEntity parent = newMatch(Sport.SHOGI, null, null);
        recordBoard(parent.getId(), 1, TeamSide.HOME);
        // 親は recordIndividualResult 内の親再集計で更新されているはず
        assertThat(store.get(parent.getId()).getHomeScore()).isEqualTo(2);
    }

    // ─── 子ボード IDOR（§C.4） ─────────────────────────────────

    @Test
    @DisplayName("他テナントの子ボードへのアクセスは 404（MATCH_030）")
    void 他テナント子ボードは404() {
        MatchEntity parent = newMatch(Sport.SHOGI, null, null);
        MatchEntity board = newMatch(Sport.SHOGI, parent.getId(), 1);
        long otherOrg = 999L;

        assertThatThrownBy(() -> service.getBoardOrThrow(parent.getId(), board.getId(), otherOrg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_030);
    }

    @Test
    @DisplayName("親子不一致（別親の子ボード指定）は 404（MATCH_030）")
    void 親子不一致は404() {
        MatchEntity parentA = newMatch(Sport.SHOGI, null, null);
        MatchEntity parentB = newMatch(Sport.SHOGI, null, null);
        MatchEntity boardOfA = newMatch(Sport.SHOGI, parentA.getId(), 1);

        // parentB の配下として boardOfA を引こうとすると 404
        assertThatThrownBy(() -> service.getBoardOrThrow(parentB.getId(), boardOfA.getId(), ORG))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_030);
    }

    // ─── 子ボード作成（§B.6） ──────────────────────────────────

    @Test
    @DisplayName("子ボードは親からテナント/チーム/競技を継承して作成される")
    void 子ボード作成で親継承() {
        MatchEntity parent = newMatch(Sport.SHOGI, null, null);
        MatchEntity board = service.createBoard(parent.getId(), ORG, ACTOR, 1, null, "対戦相手A");

        assertThat(board.getParentMatchId()).isEqualTo(parent.getId());
        assertThat(board.getBoardNumber()).isEqualTo(1);
        assertThat(board.getOrganizationId()).isEqualTo(ORG);
        assertThat(board.getTeamId()).isEqualTo(TEAM);
        assertThat(board.getSport()).isEqualTo(Sport.SHOGI);
    }

    @Test
    @DisplayName("同一親で重複ボード番号は 400")
    void 重複ボード番号は400() {
        MatchEntity parent = newMatch(Sport.SHOGI, null, null);
        service.createBoard(parent.getId(), ORG, ACTOR, 1, null, null);

        assertThatThrownBy(() -> service.createBoard(parent.getId(), ORG, ACTOR, 1, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
    }

    @Test
    @DisplayName("子ボードの配下にさらに子は作れない（親が団体戦の親でない）= 404")
    void 子の配下に子は不可() {
        MatchEntity parent = newMatch(Sport.SHOGI, null, null);
        MatchEntity board = newMatch(Sport.SHOGI, parent.getId(), 1);

        assertThatThrownBy(() -> service.createBoard(board.getId(), ORG, ACTOR, 1, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_030);
    }

    // ── helpers ──────────────────────────────────────────────

    /** 親配下に子ボードを作って勝敗を記録する（recordIndividualResult 経由＝親自動再集計込み）。 */
    private void recordBoard(UUID parentId, int boardNumber, TeamSide winner) {
        MatchEntity board = newMatch(Sport.SHOGI, parentId, boardNumber);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(winner).winMethod("RESIGNATION").build();
        service.recordIndividualResult(board.getId(), ORG, ACTOR, cmd);
    }

    /** 親配下に引分けの子ボードを記録する。 */
    private void recordBoardDraw(UUID parentId, int boardNumber) {
        MatchEntity board = newMatch(Sport.GO, parentId, boardNumber);
        MatchTurnResultService.TurnResultCommand cmd = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(null).winMethod(null).build();
        service.recordIndividualResult(board.getId(), ORG, ACTOR, cmd);
    }
}
