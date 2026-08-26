package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.catalog.VolleyballSetRules;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchSetEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchSetRepository;
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
 * F08.10 セット制スコア（バレーボール）の記録/更新サービス（sports/04_volleyball.md §4 / 01 §B.5）。
 *
 * <p><b>主動線（MVP 既定・§8.1）</b>: セットごとに {@code home_points}/{@code away_points} を直接記録する
 * 軽量経路。記録のたびに当該セットの勝者（デュース条件・{@link VolleyballSetRules}）を導出し、
 * 獲得セット数を {@code matches.home_score}/{@code away_score} に集計反映する（§B.1.2 勝敗格納規約）。</p>
 *
 * <p><b>IDOR 帰属チェーン</b>: 親 match をテナント取得（{@link MatchService#getMatchOrThrow}）した後、
 * 子 match_sets は {@code match_id} スコープでのみアクセスする（子 ID 直引き禁止・01 §A.4）。
 * 認可は {@link MatchAccessService#assertCanRecordTimeline} へ委譲する（タイムライン記録と同水準・03 §C.4）。
 * {@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p>セット内スコアの正本は {@code match_sets}、獲得セット数の正本は {@code matches.home_score/away_score}
 * という二層構造（§4.1）。COMPLETED 遷移の「全セット確定・3 セット先取」検証は
 * {@link MatchService#changeStatus} が獲得セット数（matches 列）に対して {@link VolleyballSetRules} で行う。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchSetService {

    /** セット番号の業務範囲（best-of-5・1〜5）。 */
    private static final int SET_NUMBER_MIN = 1;
    private static final int SET_NUMBER_MAX = 5;
    /** セット内得点の業務範囲上限（デュースで延長しても現実的上限）。 */
    private static final int POINTS_MAX = 99;

    private final MatchSetRepository matchSetRepository;
    private final MatchService matchService;
    private final MatchAccessService matchAccessService;
    private final ApplicationEventPublisher eventPublisher;

    // ─────────────────────────────────────────────
    // 取得（match_id スコープ・閲覧可視性は呼び出し側 Controller が F00 委譲）
    // ─────────────────────────────────────────────

    /**
     * 指定試合のセット一覧を取得する（セット番号昇順）。
     *
     * @param matchId        親 match ID
     * @param organizationId 認証テナント（親のテナントゲート）
     * @return セット一覧
     */
    public List<MatchSetEntity> listSets(UUID matchId, Long organizationId) {
        // 親 match のテナントゲート（不在/越境は 404）。
        matchService.getMatchOrThrow(matchId, organizationId);
        return matchSetRepository.findByMatchIdOrderBySetNumberAsc(matchId);
    }

    // ─────────────────────────────────────────────
    // セットスコア記録（upsert・(match_id, set_number)）
    // ─────────────────────────────────────────────

    /**
     * セットスコアを記録（upsert）する（sports/04 §8.1 主動線）。
     *
     * <ul>
     *   <li>セット制（VOLLEYBALL）でない試合への記録は 400（MATCH_024・症状を隠さない）。</li>
     *   <li>同一 {@code set_number} は既存行を更新（upsert・UNIQUE(match_id, set_number)）。</li>
     *   <li>デュース条件達成でセット勝者（{@code winner_side}）を導出（未決着は NULL）。</li>
     *   <li>記録後、獲得セット数を {@code matches.home_score}/{@code away_score} に集計反映する（§B.1.2）。</li>
     * </ul>
     *
     * @param matchId        親 match ID
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     * @param command        セットスコアコマンド
     * @return upsert されたセット行
     */
    @Transactional
    public MatchSetEntity recordSet(UUID matchId, Long organizationId, Long actorUserId,
                                    SetScoreCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);

        assertSetBased(match);
        validateCommand(command);

        boolean isFinalSet = VolleyballSetRules.isFinalSet(command.getSetNumber(), match.getPeriodFormat());
        TeamSide winner = VolleyballSetRules.resolveSetWinner(
                command.getHomePoints(), command.getAwayPoints(), isFinalSet);

        MatchSetEntity set = matchSetRepository
                .findByMatchIdAndSetNumber(matchId, command.getSetNumber())
                .orElseGet(() -> MatchSetEntity.builder()
                        .matchId(matchId)
                        .setNumber(command.getSetNumber())
                        .build());
        set.setHomePoints(command.getHomePoints());
        set.setAwayPoints(command.getAwayPoints());
        set.setFinalSet(isFinalSet);
        set.setWinnerSide(winner);

        MatchSetEntity saved = matchSetRepository.save(set);

        // 獲得セット数を matches.home_score/away_score に集計反映（試合の本戦スコアの正本・§B.1.2）。
        recomputeMatchScore(match);

        log.info("セットスコア記録: matchId={}, set={}, score={}-{}, winner={}, actor={}",
                matchId, command.getSetNumber(), command.getHomePoints(), command.getAwayPoints(),
                winner, actorUserId);

        // 07 §J.2: コミット後にスコア更新を観戦者へ配信する（セット確定でスコアが動く）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.scoreUpdated(match));
        return saved;
    }

    /**
     * 獲得セット数を {@code matches.home_score}/{@code away_score} に集計反映する（§B.1.2）。
     *
     * <p>勝者が確定したセット（{@code winner_side} 非 NULL）のみを数える。未決着セットは数えない。
     * matches 列を正本としつつ match_sets を根拠に再導出することで、整合（§4.2 のセット数一致）を担保する。</p>
     */
    private void recomputeMatchScore(MatchEntity match) {
        List<MatchSetEntity> sets = matchSetRepository.findByMatchIdOrderBySetNumberAsc(match.getId());
        int homeSets = 0;
        int awaySets = 0;
        for (MatchSetEntity s : sets) {
            if (s.getWinnerSide() == TeamSide.HOME) {
                homeSets++;
            } else if (s.getWinnerSide() == TeamSide.AWAY) {
                awaySets++;
            }
        }
        match.setHomeScore(homeSets);
        match.setAwayScore(awaySets);
    }

    // ─────────────────────────────────────────────
    // 検証
    // ─────────────────────────────────────────────

    /** セット制（VOLLEYBALL＝SET_BASED）以外への記録を弾く（400・症状を隠さない）。 */
    private void assertSetBased(MatchEntity match) {
        StateModel stateModel = match.getStateModel() != null
                ? match.getStateModel()
                : (match.getSport() != null ? match.getSport().stateModel() : null);
        if (stateModel != StateModel.SET_BASED) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
    }

    /** セット番号・得点の業務範囲を検証する（400・03 §C.4b 思想）。 */
    private void validateCommand(SetScoreCommand command) {
        if (command == null
                || command.getSetNumber() == null
                || command.getHomePoints() == null
                || command.getAwayPoints() == null) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        if (command.getSetNumber() < SET_NUMBER_MIN || command.getSetNumber() > SET_NUMBER_MAX) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        checkPoints(command.getHomePoints());
        checkPoints(command.getAwayPoints());
    }

    private void checkPoints(int value) {
        if (value < 0 || value > POINTS_MAX) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
    }

    /**
     * セットスコア記録/更新コマンド。
     *
     * <p>{@code set_number}（1〜5）をキーに upsert する。得点はラリーポイント（25 点/最終 15 点・デュース）。
     * セット勝者・最終セット判定・獲得セット数集計はサーバー側（{@link VolleyballSetRules}）が導出する
     * （クライアントの勝敗主張を信頼しない）。</p>
     */
    @Getter
    @Builder
    public static class SetScoreCommand {
        private final Integer setNumber;
        private final Integer homePoints;
        private final Integer awayPoints;
    }
}
