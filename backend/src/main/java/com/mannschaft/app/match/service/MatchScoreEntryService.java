package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.catalog.ScoredComponentCatalog;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchScoreEntryEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.match.repository.MatchScoreEntryRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * F08.10 採点競技（フィギュアスケート/体操＝第 4 状態モデル類型 SCORED）の<b>多人数順位制</b>サービス
 * （sports/07_scored.md §5B / §6 / §11 / 01 §B.1.2 / §D.8）。
 *
 * <p><b>本来形の対戦モデル（§5B）</b>: MVP の 2 者対戦（{@link MatchScoredResultService}・home/away）を超え、
 * 1 match＝1 種目（イベント）に複数の出場者（エントリ）が並び、各自の合計点（整数スケール×1000）から
 * 順位を導出する（フィギュア大会・体操の個人総合順位）。出場者エントリの正本は本サービスが管理する
 * {@code match_score_entries} であり、{@code home_score}/{@code away_score} の 2 者列は主役でなくなる。</p>
 *
 * <p><b>順位導出（§5B.2 / §6）</b>: {@code rank_position} を合計点降順で導出する。同点は同順位とし、
 * 次順位を飛ばす標準ルール（1,2,2,4）に従う。順位はサーバーが再計算し、クライアントの主張は信頼しない。</p>
 *
 * <p><b>二層正本（再導出パターン・§5B.2 / §4B.2）</b>: 整合策として {@code matches.home_score}（整数スケール×1000）に
 * 「優勝エントリ（最上位の合計点）」を補助的に再導出反映し、順位表/ダッシュボードの既存導線が空にならないようにする。
 * {@code away_score} は多人数順位制では意味を持たないため 0 に正規化する（home/away 2 者勝敗とは別軸＝§6）。
 * これは {@code match_sets}（セット内得点→獲得セット数）・団体戦（子ボード勝ち星→親列）と同じ二層正本構造。</p>
 *
 * <p><b>MVP 2 者対戦（{@link MatchScoredResultService}）・審判内訳（{@link MatchScoredComponentService}）との両立</b>:
 * 本サービスは多人数順位制の<b>追加モード</b>であり、既存の合計点直接入力（2 者）・審判別内訳（2 者）の経路を変更しない。
 * 採点競技のどの記録経路でも勝敗/順位の正準データはそれぞれの正本テーブル/列に閉じる。</p>
 *
 * <p><b>採点改竄防止（§11 / 03 §C.7）</b>: エントリの記録は team 中心権限
 * （{@link MatchAccessService#assertCanEditMeta}）に限り、変更を before/after で<b>監査ログ</b>に記録する
 * （{@link MatchScoredResultService#recordScore} と同じ監査パターン）。採点は順位・表彰に直結し改竄インパクトが
 * 大きいため監査は必須。</p>
 *
 * <p><b>IDOR 帰属チェーン（01 §A.4）</b>: 親 match をテナント取得（{@link MatchService#getMatchOrThrow}・
 * 不在/越境は 404）した後、子 {@code match_score_entries} は {@code match_id} スコープでのみアクセスする
 * （子 ID 直引き禁止）。{@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §5B / §6 / §11 / 01 §B.1.2 / §D.8</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchScoreEntryService {

    /** 1 試合に登録できる出場者エントリ数の現実的上限（誤入力/濫用の防御・03 §C.4b 思想）。 */
    private static final int MAX_ENTRIES = 500;

    private final MatchScoreEntryRepository entryRepository;
    private final MatchRepository matchRepository;
    private final MatchService matchService;
    private final MatchAccessService matchAccessService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // 取得（match_id スコープ・閲覧可視性は呼び出し側 Controller が F00 委譲）
    // ─────────────────────────────────────────────

    /**
     * 指定試合の出場者エントリ一覧を取得する（順位昇順・同順位は合計点降順・match_id スコープ）。
     *
     * @param matchId        親 match ID
     * @param organizationId 認証テナント（親のテナントゲート）
     * @return 出場者エントリ一覧（順位順）
     */
    public List<MatchScoreEntryEntity> listEntries(UUID matchId, Long organizationId) {
        matchService.getMatchOrThrow(matchId, organizationId);
        return entryRepository.findByMatchIdOrderByRankPositionAscTotalScaledDesc(matchId);
    }

    // ─────────────────────────────────────────────
    // 出場者エントリの記録（全置換 upsert・match_id スコープ）→ 順位算出 + 補助スコア再導出
    // ─────────────────────────────────────────────

    /**
     * 出場者エントリを記録（全置換）し、合計点降順で順位（{@code rank_position}）を算出して保存する（§5B.2 / §6）。
     * 補助として {@code matches.home_score} に最上位エントリの合計点を再導出反映する（二層正本・§5B.2）。
     *
     * <ul>
     *   <li>採点競技（SCORED）でない試合への記録は 400（MATCH_029・症状を隠さない）。</li>
     *   <li>同一 match のエントリは<b>全置換</b>（再記録時は既存行を削除し新行で置き換える＝冪等な確定）。</li>
     *   <li>合計点（整数スケール×1000）は非負（負は 400・MATCH_024）。出場者は識別子（user/team）or 名前を要する。</li>
     *   <li>順位は合計点降順・同点同順位（1,2,2,4）でサーバーが算出する（クライアント主張は信頼しない）。</li>
     *   <li>再導出した最上位合計点を before/after で監査記録し（採点改竄防止・§11）、コミット後に観戦者へ配信する（§9）。</li>
     * </ul>
     *
     * @param matchId        採点競技 match ID
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     * @param command        出場者エントリコマンド（エントリ明細の一覧）
     * @return 順位算出済みの出場者エントリ一覧（順位昇順）
     */
    @Transactional
    public List<MatchScoreEntryEntity> recordEntries(UUID matchId, Long organizationId, Long actorUserId,
                                                     ScoreEntriesCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanEditMeta(actorUserId, match);

        Sport sport = match.getSport();
        assertScored(match, sport);
        validateCommand(command);

        // 全置換: 既存エントリを削除して新しいエントリで置き換える（再記録の冪等性・順位再計算の単純化）。
        entryRepository.deleteByMatchId(matchId);
        entryRepository.flush();

        List<MatchScoreEntryEntity> entities = new ArrayList<>();
        for (ScoreEntryLine line : command.getLines()) {
            MatchScoreEntryEntity entity = MatchScoreEntryEntity.builder()
                    .matchId(matchId)
                    .competitorUserId(line.getCompetitorUserId())
                    .competitorName(line.getCompetitorName())
                    .competitorTeamId(line.getCompetitorTeamId())
                    .totalScaled(line.getTotalScaled())
                    .build();
            entities.add(entity);
        }

        // 順位算出: 合計点降順・同点同順位（1,2,2,4・標準順位法・§5B.2 / §6）。
        assignRanks(entities);

        for (MatchScoreEntryEntity entity : entities) {
            entryRepository.save(entity);
        }

        Integer beforeHome = match.getHomeScore();
        Integer beforeAway = match.getAwayScore();

        // 二層正本（§5B.2）: 補助として最上位エントリの合計点を home_score へ再導出反映する
        // （順位表/ダッシュボードの既存導線が空にならないように）。away_score は多人数順位制で意味を持たないため 0。
        int topScaled = entities.stream()
                .map(MatchScoreEntryEntity::getTotalScaled)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        match.setHomeScore(topScaled);
        match.setAwayScore(0);
        // 採点競技は勝ち方を持たない（勝敗/順位＝合計点そのもの・§6 / §10）。明示的に NULL を保証する。
        match.setWinMethod(null);
        matchRepository.save(match);

        // 採点改竄防止: 再導出した最上位合計点を before/after で監査記録する（§11 / 03 §C.7）。
        String metadata = String.format(
                "{\"matchId\":\"%s\",\"teamId\":%s,\"sport\":\"%s\",\"source\":\"entries\","
                        + "\"entryCount\":%d,\"topScaled\":%d,"
                        + "\"before\":{\"home\":%s,\"away\":%s},"
                        + "\"after\":{\"home\":%s,\"away\":%s}}",
                matchId, match.getTeamId(), sport, entities.size(), topScaled,
                beforeHome, beforeAway, topScaled, 0);
        auditLogService.record(AuditEventType.MATCH_SCORE_FINALIZED.name(), actorUserId, null,
                match.getTeamId(), organizationId, null, null, null, metadata);

        log.info("採点エントリ記録: matchId={}, sport={}, entries={}, topScaled={}, actor={}",
                matchId, sport, entities.size(), topScaled, actorUserId);

        // §9: コミット後にスコア（順位）更新を観戦者へ配信する（live 順位）。
        eventPublisher.publishEvent(MatchLiveUpdateEvent.scoreUpdated(match));

        // 順位昇順（同順位は合計点降順）で返す（順位表向き）。
        entities.sort(Comparator
                .comparingInt((MatchScoreEntryEntity e) -> e.getRankPosition() == null ? Integer.MAX_VALUE : e.getRankPosition())
                .thenComparing(Comparator.comparingInt((MatchScoreEntryEntity e) ->
                        e.getTotalScaled() == null ? 0 : e.getTotalScaled()).reversed()));
        return entities;
    }

    /**
     * 出場者エントリに順位（{@code rank_position}）を合計点降順で付与する（同点同順位・標準順位法 1,2,2,4・§5B.2 / §6）。
     *
     * <p>合計点の高い順に並べ、同じ合計点には同じ順位を与え、次の異なる合計点の順位は「それまでの件数+1」へ飛ばす。
     * 例: 100,90,90,80 → 1,2,2,4。{@code total_scaled} は非負前提（検証済み）。</p>
     *
     * @param entities 出場者エントリ（順位を破壊的に書き込む）
     */
    private void assignRanks(List<MatchScoreEntryEntity> entities) {
        // 合計点降順でソート（NULL は 0 扱い・検証済みのため通常 NULL は来ない）。
        List<MatchScoreEntryEntity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingInt(
                (MatchScoreEntryEntity e) -> e.getTotalScaled() == null ? 0 : e.getTotalScaled()).reversed());

        Integer previousScaled = null;
        int previousRank = 0;
        int processed = 0;
        for (MatchScoreEntryEntity entity : sorted) {
            processed++;
            int scaled = entity.getTotalScaled() == null ? 0 : entity.getTotalScaled();
            if (previousScaled != null && scaled == previousScaled) {
                // 同点 → 直前と同順位（次順位を飛ばす標準ルール）。
                entity.setRankPosition(previousRank);
            } else {
                // 異なる得点 → 順位はそれまでの処理件数（1-based・1,2,2,4 を実現）。
                entity.setRankPosition(processed);
                previousRank = processed;
                previousScaled = scaled;
            }
        }
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
     * 出場者エントリコマンドを検証する（400・症状を隠さない・§5B.1）。
     *
     * <ul>
     *   <li>エントリが空 / 件数上限超過は 400（MATCH_024）。</li>
     *   <li>各エントリの {@code total_scaled} は必須かつ非負（整数スケール×1000）。</li>
     *   <li>各エントリは出場者識別（{@code competitor_user_id}・{@code competitor_team_id}）か
     *       表示名（{@code competitor_name}）のいずれかを持つこと（誰のスコアか不明な行を弾く）。</li>
     * </ul>
     */
    private void validateCommand(ScoreEntriesCommand command) {
        if (command == null || command.getLines() == null || command.getLines().isEmpty()) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        if (command.getLines().size() > MAX_ENTRIES) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        for (ScoreEntryLine line : command.getLines()) {
            if (line == null
                    || line.getTotalScaled() == null
                    || line.getTotalScaled() < 0) {
                throw new BusinessException(MatchErrorCode.MATCH_024);
            }
            boolean hasIdentity = line.getCompetitorUserId() != null
                    || line.getCompetitorTeamId() != null
                    || (line.getCompetitorName() != null && !line.getCompetitorName().isBlank());
            if (!hasIdentity) {
                throw new BusinessException(MatchErrorCode.MATCH_024);
            }
        }
    }

    /**
     * 出場者エントリ記録コマンド（採点競技・多人数順位制・§5B）。エントリ明細の一覧を全置換で受け取る。
     */
    @Getter
    @Builder
    public static class ScoreEntriesCommand {
        /** 出場者エントリ明細の一覧（全置換）。 */
        private final List<ScoreEntryLine> lines;
    }

    /**
     * 出場者エントリの 1 明細（採点競技・多人数順位制・§5B）。
     *
     * <p>出場者は {@code competitor_user_id}（登録選手）／{@code competitor_team_id}（団体採点）／
     * {@code competitor_name}（未登録選手名）のいずれかで識別する。{@code total_scaled}（合計点・整数スケール×1000・
     * 非負）が中核。順位（{@code rank_position}）はサーバーが算出するため本明細には含めない（マスアサインメント防止）。</p>
     */
    @Getter
    @Builder
    public static class ScoreEntryLine {
        private final Long competitorUserId;
        private final String competitorName;
        private final Long competitorTeamId;
        private final Integer totalScaled;
    }
}
