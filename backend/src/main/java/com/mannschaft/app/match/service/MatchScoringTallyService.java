package com.mannschaft.app.match.service;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.dto.MatchScoringTally;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1 試合の基本スコアリング（得点/アシスト）を選手別に集計し、ドメイン越境用の DTO で公開するサービス
 * （F08.10 05 §H.2.2・個人ランキングの match_events 正本化）。
 *
 * <p><b>役割</b>: 個人ランキング（得点王/アシスト王）の源泉を {@code match_events}（GOAL/ASSIST）へ
 * 正本化するための<b>越境用の読み取り口</b>。tournament ドメインの {@code MatchScoreFixtureListener} が
 * 試合完了イベント（{@code MatchCompletedEvent}）受信時に本メソッドを呼び、得られた集計を fixture
 * スナップショット（{@code tournament_match_player_stats}）へ同期する。Entity は越境させず
 * {@link MatchScoringTally}（プレーン DTO）のみ返すため、{@code CrossDomainEntityImportArchTest}
 * （CLAUDE.md 原則 1）に抵触しない。</p>
 *
 * <p><b>集計定義</b>: {@code MatchStatsAggregationService} と同一（得点 = {@code GOAL + PENALTY_GOAL}、
 * PK 戦 {@code PENALTY_SHOOTOUT} は除外・アシスト = {@code ASSIST}）。アプリ全体で得点/アシストの
 * 数え方を一致させるため、定義を本サービスにも明記して二重実装の差異を防ぐ
 * （集計結果が分析画面と順位ランキングで一致することを保証）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.2.2</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchScoringTallyService {

    /** 得点としてカウントする event_type（本戦のみ・PK 戦 PENALTY_SHOOTOUT は除外・soccer §6.1）。 */
    private static final Set<MatchEventType> GOAL_TYPES =
            EnumSet.of(MatchEventType.GOAL, MatchEventType.PENALTY_GOAL);

    private final MatchEventRepository matchEventRepository;

    /**
     * 指定試合の選手別の得点/アシストを集計する。
     *
     * <p>{@code player_user_id} が null のイベント（未登録選手・ゲスト）は user 紐付けが無く
     * 順位ランキングに乗らないため集計対象外とする。得点もアシストも 0 の選手は結果に含めない
     * （スナップショットに 0 行を量産しないため）。同一選手が両 side に現れる異常データでも
     * {@code (playerUserId, teamSide)} 単位に分けて集計する（fixture の participant 引当を side で行うため）。</p>
     *
     * @param matchId 集計対象の試合 ID（UUIDv7）
     * @return 選手別の得点/アシスト集計（得点/アシストいずれかが 1 以上の選手のみ）
     */
    public List<MatchScoringTally> tallyScoringStatsForMatch(UUID matchId) {
        Map<TallyKey, int[]> byKey = new LinkedHashMap<>(); // key → [goals, assists]
        for (MatchEventEntity e : matchEventRepository.findByMatchId(matchId)) {
            Long playerUserId = e.getPlayerUserId();
            if (playerUserId == null) {
                continue;
            }
            int[] v = byKey.computeIfAbsent(new TallyKey(playerUserId, e.getTeamSide()), k -> new int[2]);
            if (GOAL_TYPES.contains(e.getEventType())) {
                v[0]++;
            } else if (e.getEventType() == MatchEventType.ASSIST) {
                v[1]++;
            }
        }

        List<MatchScoringTally> result = new ArrayList<>();
        for (Map.Entry<TallyKey, int[]> en : byKey.entrySet()) {
            int goals = en.getValue()[0];
            int assists = en.getValue()[1];
            if (goals == 0 && assists == 0) {
                continue;
            }
            result.add(new MatchScoringTally(en.getKey().playerUserId(), en.getKey().teamSide(), goals, assists));
        }
        return result;
    }

    /** 集計キー（選手×サイド）。 */
    private record TallyKey(Long playerUserId, TeamSide teamSide) {
    }
}
