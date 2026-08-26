package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.StateModel;
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

import java.util.UUID;

/**
 * F08.10 採点競技（フィギュアスケート/体操＝第 4 状態モデル類型 SCORED）の採点結果サービス
 * （sports/07_scored.md §4 / §11 / 01 §B.1.2 / §D.8）。
 *
 * <p>MVP は<b>合計点のみ・2 者対戦</b>（HOME/AWAY）。両者の合計点を<b>整数スケール×1000</b>で
 * {@code home_score}/{@code away_score} に直接格納し、勝敗はスコア列の大小で導出する（{@code resolveResult()}
 * 再利用・§B.1.2）。同点（整数スケール同値）は引分（DRAW・§6）。採点競技は勝ち方（{@code win_method}）・PK・
 * 手数（{@code total_moves}）の概念を持たない（いずれも NULL・§3 / §10）。</p>
 *
 * <p><b>採点改竄防止（§11 / 03 §C.7）</b>: 採点スコアの確定・更新は team 中心権限
 * （作成者/記録係/主体チーム ADMIN/DEPUTY＝{@link MatchAccessService#assertCanEditMeta}）に限り、
 * 全変更を before/after で<b>監査ログ</b>に記録する（{@link MatchService#finalizeScore} と同じ監査パターン）。
 * 採点は順位・表彰に直結し改竄インパクトが大きいため監査は必須。</p>
 *
 * <p><b>IDOR（01 §A.4 / §C.4）</b>: match をテナント取得（{@link MatchService#getMatchOrThrow}・不在/越境は 404）した後に
 * 操作する。{@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p><b>後段 Phase（MVP では実装しない）</b>: 審判別内訳子表（{@code match_scored_components}・§4B）・
 * 多人数順位制（{@code match_score_entries}・§5B）は設計済 DDL を別波で実装する。本サービスは合計点のみを扱う。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4 / §11 / 01 §B.1.2 / §D.8</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchScoredResultService {

    private final MatchRepository matchRepository;
    private final MatchService matchService;
    private final MatchAccessService matchAccessService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    /**
     * 採点競技の合計点を記録する（MVP・合計点のみ・2 者対戦・§4 / §B.1.2）。
     *
     * <ul>
     *   <li>採点競技（SCORED）でない試合への記録は 400（MATCH_029・症状を隠さない）。</li>
     *   <li>合計点（整数スケール×1000）を {@code home_score}/{@code away_score} に格納する。
     *       負値は受け付けない（400・MATCH_024）。範囲上限は DTO のバリデーションで担保。</li>
     *   <li>勝ち方（{@code win_method}）・PK・手数は採点競技では使わないため触れない（NULL のまま）。</li>
     *   <li>変更を before/after で監査記録し（採点改竄防止・§11）、コミット後に観戦者へスコア配信する（§9）。</li>
     * </ul>
     *
     * @param matchId          採点競技 match ID
     * @param organizationId   認証テナント
     * @param actorUserId      操作者ユーザー ID
     * @param command          採点結果コマンド（合計点・整数スケール×1000）
     * @return 更新された match
     */
    @Transactional
    public MatchEntity recordScore(UUID matchId, Long organizationId, Long actorUserId,
                                   ScoredResultCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);

        assertScored(match);
        if (command == null) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        Integer homeScoreScaled = command.getHomeScoreScaled();
        Integer awayScoreScaled = command.getAwayScoreScaled();
        if (homeScoreScaled == null || awayScoreScaled == null
                || homeScoreScaled < 0 || awayScoreScaled < 0) {
            // 合計点は必須かつ非負（整数スケール×1000・症状を隠さない・400）
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }

        Integer beforeHome = match.getHomeScore();
        Integer beforeAway = match.getAwayScore();

        match.setHomeScore(homeScoreScaled);
        match.setAwayScore(awayScoreScaled);
        // 採点競技は勝ち方を持たない（勝敗＝合計点差そのもの・§6 / §10）。明示的に NULL を保証する。
        match.setWinMethod(null);
        MatchEntity saved = matchRepository.save(match);

        // 採点改竄防止: 全変更を before/after で監査記録する（finalizeScore と同じ監査パターン・§11 / 03 §C.7）。
        String metadata = String.format(
                "{\"matchId\":\"%s\",\"teamId\":%s,\"sport\":\"%s\","
                        + "\"before\":{\"home\":%s,\"away\":%s},"
                        + "\"after\":{\"home\":%s,\"away\":%s}}",
                matchId, match.getTeamId(), match.getSport(),
                beforeHome, beforeAway, homeScoreScaled, awayScoreScaled);
        auditLogService.record(AuditEventType.MATCH_SCORE_FINALIZED.name(), actorUserId, null,
                match.getTeamId(), organizationId, null, null, null, metadata);

        log.info("採点結果記録: matchId={}, sport={}, scaled={}-{}, actor={}",
                matchId, saved.getSport(), homeScoreScaled, awayScoreScaled, actorUserId);

        // §9: コミット後にスコア更新を観戦者へ配信する（勝敗が動く・機微情報を含まないスコアサマリのみ）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.scoreUpdated(saved));
        return saved;
    }

    /** 採点競技（フィギュア/体操＝SCORED）以外への操作を弾く（400・症状を隠さない）。 */
    private void assertScored(MatchEntity match) {
        StateModel stateModel = match.getStateModel() != null
                ? match.getStateModel()
                : (match.getSport() != null ? match.getSport().stateModel() : null);
        if (stateModel != StateModel.SCORED) {
            throw new BusinessException(MatchErrorCode.MATCH_029);
        }
    }

    /**
     * 採点結果記録コマンド（採点競技・§4 / §B.1.2）。
     *
     * <p>合計点（整数スケール×1000）を受け取り、サーバーが {@code home_score}/{@code away_score} に格納する。
     * 勝敗の正準はスコア列大小（§B.1.2）。小数⇔整数スケール変換は FE/DTO 層で行う（§4.1）。</p>
     */
    @Getter
    @Builder
    public static class ScoredResultCommand {
        /** ホーム合計点（整数スケール×1000）。 */
        private final Integer homeScoreScaled;
        /** アウェイ合計点（整数スケール×1000）。 */
        private final Integer awayScoreScaled;
    }
}
