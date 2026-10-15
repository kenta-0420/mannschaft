package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.catalog.WinMethodCatalog;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * F08.10 ターン制（将棋/囲碁）の対局結果＋団体戦（親子ボード）サービス
 * （sports/05_shogi.md §4 / sports/06_go.md §4 / 01 §B.1.2 / §B.6 / §D.7）。
 *
 * <p>セット制の {@link MatchSetService} と同じ<b>二層正本</b>の思想:</p>
 * <ul>
 *   <li><b>個人戦（TURN_BASED かつ parent_match_id=NULL）</b>: 勝敗を {@code home_score}/{@code away_score} に
 *       1-0 / 0-1 / 0-0 で直接格納し、勝ち方を {@code win_method}（{@link WinMethodCatalog} で検証）に保持する
 *       （§B.1.2 / §4.2）。総手数 {@code total_moves} は任意。</li>
 *   <li><b>団体戦の親（parent_match_id=NULL・子ボードを束ねる）</b>: 親の勝敗は子ボードの<b>勝ち星集計</b>から
 *       導出し、{@code home_score}/{@code away_score} に勝ち星数（整数スケール）を集計反映する。子ボードの確定で
 *       親を再導出する（{@link MatchSetService} のセット数再導出と同型・§4.3 / §B.6）。</li>
 * </ul>
 *
 * <p><b>勝ち星の整数スケール（§B.6）</b>: 引分けボード（千日手/持将棋/持碁＝子 0-0）は両者に 0.5 勝ずつ。
 * {@code home_score}/{@code away_score} は整数のため「勝ち=2・引分=各 1」スケールで集計し（UI で 0.5 換算表示）、
 * 親の勝ち星の大小で W/D/L を導出する（同数=親 DRAW・{@code win_method}=NULL）。</p>
 *
 * <p><b>IDOR 帰属チェーン（01 §A.4 / §C.4）</b>: 親 match をテナント取得（{@link MatchService#getMatchOrThrow}）した後、
 * 子ボードは {@code parent_match_id} スコープで取得する二段アクセス（子直引きで親をまたぐ越境を遮断）。子ボード自身も
 * テナント帰属を持つため、子操作時は子をテナント取得 → 親も同一テナントであることを検証する（親子テナント整合）。
 * 認可は {@link MatchAccessService} へ委譲する。{@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p><b>記録権限（§C.2a）</b>: TURN_BASED 個人戦は「対局者本人 or 主体チーム ADMIN or 記録係」が記録可能
 * （{@link MatchAccessService#assertCanRecordTimeline}＝§C.2a の類型分岐を含む）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchTurnResultService {

    /** 勝ち星の整数スケール: 1 ボードの勝ち＝2・引分＝各 1（0.5 勝の整数表現・§B.6）。 */
    private static final int WIN_SCALE = 2;
    private static final int DRAW_SCALE = 1;

    /** 総手数の業務範囲上限（現実的上限・将棋/囲碁とも数百手・余裕を持たせる）。 */
    private static final int TOTAL_MOVES_MAX = 1000;

    /** 団体戦のボード数上限（現実的上限）。 */
    private static final int BOARD_NUMBER_MAX = 99;

    private final MatchRepository matchRepository;
    private final MatchService matchService;
    private final MatchAccessService matchAccessService;
    private final ApplicationEventPublisher eventPublisher;

    // ─────────────────────────────────────────────
    // 個人戦の対局結果記録（home/away_score=1-0/0-1/0-0 ＋ win_method）
    // ─────────────────────────────────────────────

    /**
     * ターン制（将棋/囲碁）の対局結果を記録する（§4.2 / §B.1.2）。
     *
     * <ul>
     *   <li>ターン制（TURN_BASED）でない試合への記録は 400（MATCH_029・症状を隠さない）。</li>
     *   <li>勝者あり（{@code winnerSide} 非 NULL）: 勝者側スコア=1・敗者側=0、勝ち方（{@code winMethod}）必須かつ
     *       当該競技カタログの列挙値であること（NULL/列挙外は 400・MATCH_028）。</li>
     *   <li>引分（{@code winnerSide}=NULL＝千日手/持将棋/持碁）: home/away_score=0/0、勝ち方は NULL でなければ 400。</li>
     *   <li>総手数（{@code totalMoves}）は任意（業務範囲外は 400）。</li>
     *   <li>子ボード（{@code parent_match_id} 設定済）への記録なら、記録後に親の勝ち星を再集計する（§4.3）。</li>
     * </ul>
     *
     * @param matchId        対局 match ID（個人戦 or 団体戦の子ボード）
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     * @param command        対局結果コマンド
     * @return 更新された match
     */
    @Transactional
    public MatchEntity recordIndividualResult(UUID matchId, Long organizationId, Long actorUserId,
                                              TurnResultCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);

        assertTurnBased(match);
        validateResultCommand(match, command);

        if (command.getWinnerSide() == null) {
            // 引分（千日手/持将棋/持碁）: 両方 0・勝ち方 NULL（§4.2）
            match.setHomeScore(0);
            match.setAwayScore(0);
            match.setWinMethod(null);
        } else if (command.getWinnerSide() == TeamSide.HOME) {
            match.setHomeScore(1);
            match.setAwayScore(0);
            match.setWinMethod(command.getWinMethod());
        } else { // AWAY
            match.setHomeScore(0);
            match.setAwayScore(1);
            match.setWinMethod(command.getWinMethod());
        }
        // 総手数は任意（指定があればセット・null なら据え置きしない＝明示上書きで「不明」へ戻せる）
        match.setTotalMoves(command.getTotalMoves());

        MatchEntity saved = matchRepository.save(match);

        // 子ボードの結果確定なら親の勝ち星を再集計する（二層再導出・§4.3）。
        if (saved.getParentMatchId() != null) {
            recomputeParentFromBoards(saved.getParentMatchId(), organizationId);
        }

        log.info("対局結果記録: matchId={}, winner={}, winMethod={}, totalMoves={}, parent={}, actor={}",
                matchId, command.getWinnerSide(), saved.getWinMethod(), saved.getTotalMoves(),
                saved.getParentMatchId(), actorUserId);

        // 07 §J.2: コミット後にスコア更新を観戦者へ配信する（勝敗が動く）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.scoreUpdated(saved));
        return saved;
    }

    // ─────────────────────────────────────────────
    // 団体戦の子ボード作成・一覧・親勝ち星集計
    // ─────────────────────────────────────────────

    /**
     * 団体戦の子ボードを作成する（親 match 配下・§B.6 / §C.4）。
     *
     * <p>親が団体戦の親（TURN_BASED かつ parent_match_id=NULL）であること、親子が同一テナントであることを検証する。
     * 子ボードは独立した試合実体（matches）であり、親と同一の organization_id/team_id/sport を継承する
     * （マスアサインメント防止＝親から導出・クライアント値を信頼しない）。{@code board_number} はクライアント指定だが
     * 業務範囲（1〜99）と親内一意（既存と重複なら 400）を検証する。</p>
     *
     * @param parentMatchId  親（団体戦）match ID
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     * @param boardNumber    ボード順（1=大将/主将 等）
     * @param opponentTeamId 相手チーム ID（任意・親から継承する場合は NULL 可）
     * @param opponentName   未登録相手名（任意）
     * @return 作成された子ボード match
     */
    @Transactional
    public MatchEntity createBoard(UUID parentMatchId, Long organizationId, Long actorUserId,
                                   Integer boardNumber, Long opponentTeamId, String opponentName) {
        MatchEntity parent = matchService.getMatchOrThrow(parentMatchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, parent);

        assertTurnBased(parent);
        // 親は団体戦の親（parent_match_id=NULL）でなければならない（子の下にさらに子は作らない・§B.6）。
        if (parent.getParentMatchId() != null) {
            throw new BusinessException(MatchErrorCode.MATCH_030);
        }
        if (boardNumber == null || boardNumber < 1 || boardNumber > BOARD_NUMBER_MAX) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        // 親内でボード番号は一意（重複起票防止・§B.6 UNIQUE 思想）。
        boolean dup = matchRepository.findByParentMatchIdOrderByBoardNumberAsc(parentMatchId).stream()
                .anyMatch(b -> boardNumber.equals(b.getBoardNumber()));
        if (dup) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }

        MatchEntity board = MatchEntity.builder()
                // テナント・主体チーム・競技・種別・記録モードは親から継承する（サーバー導出・改竄耐性）
                .organizationId(parent.getOrganizationId())
                .teamId(parent.getTeamId())
                .sport(parent.getSport())
                .stateModel(parent.getStateModel())
                .kind(parent.getKind())
                .homeAway(parent.getHomeAway())
                .opponentTeamId(opponentTeamId != null ? opponentTeamId : parent.getOpponentTeamId())
                .opponentName(opponentName)
                .kickoffAt(parent.getKickoffAt())
                .venue(parent.getVenue())
                .status(parent.getStatus())
                .hasScorekeeper(parent.isHasScorekeeper())
                .scorekeeperUserId(parent.getScorekeeperUserId())
                // 団体戦の子ボードとしての結線
                .parentMatchId(parentMatchId)
                .boardNumber(boardNumber)
                .createdBy(actorUserId)
                .build();

        MatchEntity saved = matchRepository.save(board);
        log.info("団体戦ボード作成: parent={}, boardId={}, boardNumber={}, actor={}",
                parentMatchId, saved.getId(), boardNumber, actorUserId);
        return saved;
    }

    /**
     * 団体戦の子ボード一覧を取得する（親 ID スコープ・二段アクセス・§C.4）。
     *
     * <p>親 match をテナント取得（不在/越境は 404）してから {@code parent_match_id} スコープで引く
     * （子直引き禁止）。閲覧可視性は呼び出し側 Controller が F00（{@link MatchAccessService#assertCanView}）に委譲する。</p>
     *
     * @param parentMatchId  親（団体戦）match ID
     * @param organizationId 認証テナント
     * @return 子ボード一覧（board_number 昇順）
     */
    public List<MatchEntity> listBoards(UUID parentMatchId, Long organizationId) {
        MatchEntity parent = matchService.getMatchOrThrow(parentMatchId, organizationId);
        assertTurnBased(parent);
        return matchRepository.findByParentMatchIdOrderByBoardNumberAsc(parentMatchId);
    }

    /**
     * 子ボードをテナント＋親帰属検証して取得する（IDOR・二段アクセス・§C.4）。
     *
     * <p>子ボード自身をテナント取得（不在/越境は 404）した後、
     * {@code board.parent_match_id == parentMatchId} と親の同一テナントを検証する（不一致は 404 統一・存在を漏らさない）。</p>
     *
     * @param parentMatchId  親 match ID
     * @param boardMatchId   子ボード match ID
     * @param organizationId 認証テナント
     * @return 子ボード match
     */
    public MatchEntity getBoardOrThrow(UUID parentMatchId, UUID boardMatchId, Long organizationId) {
        MatchEntity board = matchRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(boardMatchId, organizationId)
                .orElseThrow(() -> new BusinessException(MatchErrorCode.MATCH_030));
        if (!parentMatchId.equals(board.getParentMatchId())) {
            // 子ボードが指定の親に属さない（越境・親子不一致は 404 統一）
            throw new BusinessException(MatchErrorCode.MATCH_030);
        }
        // 親も同一テナントであることを検証（親子テナント整合・§C.4）。
        matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(parentMatchId, organizationId)
                .orElseThrow(() -> new BusinessException(MatchErrorCode.MATCH_030));
        return board;
    }

    /**
     * 親（団体戦）の勝敗を子ボードの勝ち星集計から再導出して {@code home_score}/{@code away_score} に反映する
     * （§4.3 / §B.6）。
     *
     * <p>勝ち星は整数スケール（勝ち=2・引分=各 1）で集計し、親の勝ち星の大小で W/D/L を導出する。
     * 親の勝ち星同数のときは親 DRAW（{@code win_method}=NULL）。勝敗ありでも団体戦の親は勝ち方を持たない
     * （{@code win_method} は常に NULL＝個別ボードの勝ち方の集合体ゆえ単一の勝ち方に集約できない・§4.3）。</p>
     *
     * <p>未確定ボード（{@code home_score}/{@code away_score} がともに NULL）は集計対象外（まだ結果が入っていない）。</p>
     *
     * @param parentMatchId  親 match ID
     * @param organizationId 認証テナント
     * @return 再導出後の親 match
     */
    @Transactional
    public MatchEntity recomputeParentFromBoards(UUID parentMatchId, Long organizationId) {
        MatchEntity parent = matchService.getMatchOrThrow(parentMatchId, organizationId);
        List<MatchEntity> boards = matchRepository.findByParentMatchIdOrderByBoardNumberAsc(parentMatchId);

        int homeStars = 0;
        int awayStars = 0;
        for (MatchEntity b : boards) {
            Integer h = b.getHomeScore();
            Integer a = b.getAwayScore();
            if (h == null || a == null) {
                // 未確定ボードは集計しない
                continue;
            }
            if (h > a) {
                homeStars += WIN_SCALE;
            } else if (h < a) {
                awayStars += WIN_SCALE;
            } else {
                // 子ボード引分（千日手/持将棋/持碁＝0-0）は両者に 0.5 勝（整数スケール各 1）
                homeStars += DRAW_SCALE;
                awayStars += DRAW_SCALE;
            }
        }
        parent.setHomeScore(homeStars);
        parent.setAwayScore(awayStars);
        // 団体戦の親は勝ち方を持たない（個別ボードの勝ち方の集合・親の勝敗はスコア大小で導出・§4.3）。
        parent.setWinMethod(null);
        MatchEntity saved = matchRepository.save(parent);

        log.info("団体戦親勝ち星再集計: parent={}, stars(scaled)={}-{}", parentMatchId, homeStars, awayStars);
        eventPublisher.publishEvent(MatchLiveUpdateEvent.scoreUpdated(saved));
        return saved;
    }

    // ─────────────────────────────────────────────
    // 検証
    // ─────────────────────────────────────────────

    /** ターン制（将棋/囲碁＝TURN_BASED）以外への操作を弾く（400・症状を隠さない）。 */
    private void assertTurnBased(MatchEntity match) {
        StateModel stateModel = match.getStateModel() != null
                ? match.getStateModel()
                : (match.getSport() != null ? match.getSport().stateModel() : null);
        if (stateModel != StateModel.TURN_BASED) {
            throw new BusinessException(MatchErrorCode.MATCH_029);
        }
    }

    /**
     * 対局結果コマンドを検証する（§4.2 / §D.7・400）。
     *
     * <ul>
     *   <li>勝者あり: 勝ち方が当該競技カタログの列挙値かつ非 NULL（NULL/列挙外は 400・MATCH_028）。</li>
     *   <li>引分（勝者 NULL）: 勝ち方は NULL でなければならない（責務分離・§4.2・MATCH_028）。</li>
     *   <li>総手数: 業務範囲（0〜1000）。範囲外は 400（MATCH_024）。</li>
     * </ul>
     */
    private void validateResultCommand(MatchEntity match, TurnResultCommand command) {
        if (command == null) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        if (command.getWinnerSide() == null) {
            // 引分: 勝ち方は付けられない
            if (command.getWinMethod() != null) {
                throw new BusinessException(MatchErrorCode.MATCH_028);
            }
        } else {
            // 勝敗あり: 勝ち方必須かつ当該競技カタログの列挙値
            if (command.getWinMethod() == null
                    || !WinMethodCatalog.isValid(match.getSport(), command.getWinMethod())) {
                throw new BusinessException(MatchErrorCode.MATCH_028);
            }
        }
        if (command.getTotalMoves() != null
                && (command.getTotalMoves() < 0 || command.getTotalMoves() > TOTAL_MOVES_MAX)) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
    }

    /**
     * 対局結果記録コマンド（ターン制・§4.2 / §B.1.2）。
     *
     * <p>{@code winnerSide}=NULL は引分（千日手/持将棋/持碁）。勝敗の格納（1-0/0-1/0-0）はサーバーが導出するため
     * スコア列はコマンドに含めない（クライアントの勝敗主張＝勝者 side ＋勝ち方のみ受け取る）。</p>
     */
    @Getter
    @Builder
    public static class TurnResultCommand {
        /** 勝者サイド（HOME=先手/黒・AWAY=後手/白・NULL=引分）。 */
        private final TeamSide winnerSide;
        /** 勝ち方の enum 名（ShogiWinMethod/GoWinMethod・引分は NULL）。 */
        private final String winMethod;
        /** 総手数（任意・NULL=不明）。 */
        private final Integer totalMoves;
    }
}
