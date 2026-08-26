package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.catalog.ScoredComponentCatalog;
import com.mannschaft.app.match.domain.ScoredApparatus;
import com.mannschaft.app.match.domain.ScoredComponentType;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchScoredComponentEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.match.repository.MatchScoredComponentRepository;
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
 * F08.10 採点競技（フィギュアスケート/体操＝第 4 状態モデル類型 SCORED）の<b>審判別/種目別採点内訳</b>サービス
 * （sports/07_scored.md §4B / §11 / 01 §B.1.2 / §D.8）。
 *
 * <p><b>二層正本（再導出パターン・§4B.2）</b>: 採点内訳（{@code match_scored_components}）を HOME/AWAY ごとに
 * 合計し、{@code matches.home_score}/{@code away_score}（整数スケール×1000）へ再導出して反映する。これは
 * {@code match_sets}（セット内得点→獲得セット数）・団体戦（子ボード勝ち星→親列）と全く同じ二層正本構造であり、
 * §B.1.2 の単一正準（合計点の大小で勝敗導出＝{@code resolveResult()} 再利用）を崩さない。
 * 合計点（matches 列）は内訳から再導出されたスナップショットである。</p>
 *
 * <p><b>MVP 合計点直接入力（{@link MatchScoredResultService}）との両立</b>: 内訳がある場合は本サービスで内訳から
 * 合計を導出する。内訳が無い場合は従来どおり {@code MatchScoredResultService} が合計点を直接入力する（両立）。
 * いずれの経路でも勝敗の正準は {@code home_score}/{@code away_score} の大小（不変・§B.1.2）。</p>
 *
 * <p><b>DEDUCTION（減点）の符号</b>: フィギュアの減点（転倒等）は当該 side の合計から差し引く負方向の項目。
 * 内訳を符号付きで集計する（TES/PCS/D_SCORE/E_SCORE は加算・DEDUCTION は減算・§4B.2）。
 * side 合計が負になる場合は 0 にクランプする（{@code home_score}/{@code away_score} は UNSIGNED）。</p>
 *
 * <p><b>採点改竄防止（§11 / 03 §C.7）</b>: 内訳の記録は team 中心権限
 * （{@link MatchAccessService#assertCanEditMeta}）に限り、再導出した合計の変更を before/after で
 * <b>監査ログ</b>に記録する（{@link MatchScoredResultService#recordScore} と同じ監査パターン）。
 * 採点は順位・表彰に直結し改竄インパクトが大きいため監査は必須。</p>
 *
 * <p><b>IDOR 帰属チェーン（01 §A.4）</b>: 親 match をテナント取得（{@link MatchService#getMatchOrThrow}・
 * 不在/越境は 404）した後、子 {@code match_scored_components} は {@code match_id} スコープでのみアクセスする
 * （子 ID 直引き禁止）。{@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B / §11 / 01 §B.1.2 / §D.8</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchScoredComponentService {

    /** 1 試合に登録できる内訳行数の現実的上限（誤入力/濫用の防御・03 §C.4b 思想）。 */
    private static final int MAX_COMPONENTS = 200;

    private final MatchScoredComponentRepository componentRepository;
    private final MatchRepository matchRepository;
    private final MatchService matchService;
    private final MatchAccessService matchAccessService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // 取得（match_id スコープ・閲覧可視性は呼び出し側 Controller が F00 委譲）
    // ─────────────────────────────────────────────

    /**
     * 指定試合の採点内訳一覧を取得する（作成時刻昇順・match_id スコープ）。
     *
     * @param matchId        親 match ID
     * @param organizationId 認証テナント（親のテナントゲート）
     * @return 採点内訳一覧
     */
    public List<MatchScoredComponentEntity> listComponents(UUID matchId, Long organizationId) {
        matchService.getMatchOrThrow(matchId, organizationId);
        return componentRepository.findByMatchIdOrderByCreatedAtAsc(matchId);
    }

    // ─────────────────────────────────────────────
    // 採点内訳の記録（全置換 upsert・(match_id) スコープ）→ 合計点を再導出
    // ─────────────────────────────────────────────

    /**
     * 採点内訳を記録（全置換）し、HOME/AWAY ごとの合計点を {@code matches.home_score}/{@code away_score} へ
     * 再導出反映する（二層正本・§4B.2）。
     *
     * <ul>
     *   <li>採点競技（SCORED）でない試合への記録は 400（MATCH_029・症状を隠さない）。</li>
     *   <li>各内訳の {@code component_type}/{@code apparatus} は当該競技のカタログ列挙であること
     *       （列挙外は 400・MATCH_024・症状を隠さない・§4B.2 / §10）。</li>
     *   <li>同一 match の内訳は<b>全置換</b>（再記録時は既存行を削除し新行で置き換える＝冪等な確定）。</li>
     *   <li>HOME/AWAY ごとに符号付き集計（DEDUCTION は減算）し、負は 0 にクランプして合計を導出。</li>
     *   <li>再導出した合計を before/after で監査記録し（採点改竄防止・§11）、コミット後に観戦者へ配信する（§9）。</li>
     * </ul>
     *
     * @param matchId        採点競技 match ID
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     * @param command        採点内訳コマンド（内訳明細の一覧）
     * @return 更新された match（合計点が内訳から再導出済み）
     */
    @Transactional
    public MatchEntity recordComponents(UUID matchId, Long organizationId, Long actorUserId,
                                        ScoredComponentsCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);

        Sport sport = match.getSport();
        assertScored(match, sport);
        validateCommand(command, sport);

        // 全置換: 既存内訳を削除して新しい内訳で置き換える（再記録の冪等性・再導出の単純化）。
        componentRepository.deleteByMatchId(matchId);
        componentRepository.flush();

        for (ScoredComponentLine line : command.getLines()) {
            MatchScoredComponentEntity entity = MatchScoredComponentEntity.builder()
                    .matchId(matchId)
                    .competitorSide(line.getCompetitorSide())
                    .apparatus(line.getApparatus())
                    .judgeLabel(line.getJudgeLabel())
                    .componentType(line.getComponentType())
                    .pointsScaled(line.getPointsScaled())
                    .build();
            componentRepository.save(entity);
        }

        Integer beforeHome = match.getHomeScore();
        Integer beforeAway = match.getAwayScore();

        // 二層正本: 内訳を HOME/AWAY ごとに符号付き集計し matches 列へ再導出反映（§4B.2 / §B.1.2）。
        int homeTotal = aggregateSide(command.getLines(), TeamSide.HOME);
        int awayTotal = aggregateSide(command.getLines(), TeamSide.AWAY);
        match.setHomeScore(homeTotal);
        match.setAwayScore(awayTotal);
        // 採点競技は勝ち方を持たない（勝敗＝合計点差そのもの・§6 / §10）。明示的に NULL を保証する。
        match.setWinMethod(null);
        MatchEntity saved = matchRepository.save(match);

        // 採点改竄防止: 再導出した合計を before/after で監査記録する（§11 / 03 §C.7）。
        String metadata = String.format(
                "{\"matchId\":\"%s\",\"teamId\":%s,\"sport\":\"%s\",\"source\":\"components\","
                        + "\"componentCount\":%d,"
                        + "\"before\":{\"home\":%s,\"away\":%s},"
                        + "\"after\":{\"home\":%s,\"away\":%s}}",
                matchId, match.getTeamId(), sport, command.getLines().size(),
                beforeHome, beforeAway, homeTotal, awayTotal);
        auditLogService.record(AuditEventType.MATCH_SCORE_FINALIZED.name(), actorUserId, null,
                match.getTeamId(), organizationId, null, null, null, metadata);

        log.info("採点内訳記録: matchId={}, sport={}, lines={}, scaled={}-{}, actor={}",
                matchId, sport, command.getLines().size(), homeTotal, awayTotal, actorUserId);

        // §9: コミット後にスコア更新を観戦者へ配信する（合計点が動く）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.scoreUpdated(saved));
        return saved;
    }

    /**
     * 指定 side の内訳を符号付きで集計する（DEDUCTION は減算・他は加算・負は 0 クランプ・§4B.2）。
     *
     * @param lines 内訳明細
     * @param side  集計対象 side
     * @return 当該 side の合計点（整数スケール×1000・0 以上）
     */
    private int aggregateSide(List<ScoredComponentLine> lines, TeamSide side) {
        long total = 0;
        for (ScoredComponentLine line : lines) {
            if (line.getCompetitorSide() != side) {
                continue;
            }
            int points = line.getPointsScaled() == null ? 0 : line.getPointsScaled();
            if (line.getComponentType() == ScoredComponentType.DEDUCTION) {
                total -= points;
            } else {
                total += points;
            }
        }
        if (total < 0) {
            // home_score/away_score は UNSIGNED。減点超過で負になる異常入力は 0 にクランプする。
            return 0;
        }
        if (total > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    // ─────────────────────────────────────────────
    // 検証
    // ─────────────────────────────────────────────

    /** 採点競技（フィギュア/体操＝SCORED）以外への操作を弾く（400・症状を隠さない）。 */
    private void assertScored(MatchEntity match, Sport sport) {
        StateModel stateModel = match.getStateModel() != null
                ? match.getStateModel()
                : (sport != null ? sport.stateModel() : null);
        if (stateModel != StateModel.SCORED || !ScoredComponentCatalog.isScoredSport(sport)) {
            throw new BusinessException(MatchErrorCode.MATCH_029);
        }
    }

    /**
     * 採点内訳コマンドを検証する（400・症状を隠さない・§4B.2 / §10）。
     *
     * <ul>
     *   <li>内訳が空 / 件数上限超過は 400（MATCH_024）。</li>
     *   <li>各行の {@code competitor_side}（HOME/AWAY・2 者対戦 MVP）は必須。</li>
     *   <li>各行の {@code component_type} は当該競技のカタログ列挙であること（列挙外は 400）。</li>
     *   <li>{@code apparatus} は指定された場合のみ当該競技のカタログ列挙であること（NULL 許容）。</li>
     *   <li>{@code points_scaled} は非負（整数スケール×1000・減点も絶対値を正で入れ DEDUCTION 種別で符号付け）。</li>
     * </ul>
     */
    private void validateCommand(ScoredComponentsCommand command, Sport sport) {
        if (command == null || command.getLines() == null || command.getLines().isEmpty()) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        if (command.getLines().size() > MAX_COMPONENTS) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        for (ScoredComponentLine line : command.getLines()) {
            if (line == null
                    || line.getCompetitorSide() == null
                    || line.getPointsScaled() == null
                    || line.getPointsScaled() < 0) {
                throw new BusinessException(MatchErrorCode.MATCH_024);
            }
            if (!ScoredComponentCatalog.isComponentTypeAllowed(sport, line.getComponentType())) {
                throw new BusinessException(MatchErrorCode.MATCH_024);
            }
            if (!ScoredComponentCatalog.isApparatusAllowed(sport, line.getApparatus())) {
                throw new BusinessException(MatchErrorCode.MATCH_024);
            }
        }
    }

    /**
     * 採点内訳記録コマンド（採点競技・§4B）。内訳明細の一覧を全置換で受け取る。
     */
    @Getter
    @Builder
    public static class ScoredComponentsCommand {
        /** 内訳明細の一覧（全置換）。 */
        private final List<ScoredComponentLine> lines;
    }

    /**
     * 採点内訳の 1 明細（採点競技・§4B）。
     *
     * <p>{@code competitor_side}（HOME/AWAY・MVP 2 者対戦）・{@code component_type}（必須）・
     * {@code points_scaled}（整数スケール×1000・非負）が中核。{@code apparatus}（種目/セグメント）と
     * {@code judge_label}（審判識別）は任意。減点は {@code component_type=DEDUCTION}＋正の {@code points_scaled}
     * で表し、集計時に符号付きで減算する（クライアントは絶対値を入れる・§4B.2）。</p>
     */
    @Getter
    @Builder
    public static class ScoredComponentLine {
        private final TeamSide competitorSide;
        private final ScoredApparatus apparatus;
        private final String judgeLabel;
        private final ScoredComponentType componentType;
        private final Integer pointsScaled;
    }
}
