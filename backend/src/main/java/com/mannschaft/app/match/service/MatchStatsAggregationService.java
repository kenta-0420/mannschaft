package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.dto.MatchEventsResponse;
import com.mannschaft.app.match.dto.MatchEventResponse;
import com.mannschaft.app.match.dto.PlayerAppearanceResponse;
import com.mannschaft.app.match.dto.TeamMatchStatsResponse;
import com.mannschaft.app.match.dto.UserMatchStatsResponse;
import com.mannschaft.app.match.dto.UserMatchTimelineEntry;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.entity.PlayerAppearanceEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.match.repository.PlayerAppearanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F08.10 集計サービス（個人キャリア／個人タイムライン／チーム／試合内・02 §F・sports/01_soccer §4・§6）。
 *
 * <p><b>読み取り専用</b>（{@code @Transactional(readOnly=true)}）。集計の<b>枠組みはコア</b>、得点/アシスト/勝敗等の
 * <b>指標定義はサッカー固有</b>（sports/01_soccer §4・§6）を本サービスに実装する。</p>
 *
 * <h3>N+1 回避（02 §F.3）</h3>
 * <ol>
 *   <li>対象 match 群を {@code MatchRepository.findForXxxStats}（テナント絞り込み＋期間/kind/sport 絞り）で 1 クエリ取得。</li>
 *   <li>子（events / appearances）を {@code findByMatchIdIn} で<b>matchId IN の一括取得</b>（試合ごとの個別クエリを発行しない）。</li>
 *   <li>ランキングの表示名は {@code UserRepository.findByIdIn} で一括取得し匿名化追従（原則 4）。</li>
 * </ol>
 *
 * <p><b>テナント安全性</b>: 子の {@code findByMatchIdIn} はテナントゲートを持たないため、必ず手順 1 で
 * テナント絞り込み済みの matchId 集合のみを渡す（IDOR 根絶・01 §A.4）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §F
 *   / sports/01_soccer.md §4・§6</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchStatsAggregationService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * recentForm に含める直近試合数（soccer §6.2 / 02 §F.3）。
     * 全試合分を蓄積すると配列が無制限に肥大化するため、直近 N 件に限定する。
     */
    private static final int RECENT_FORM_SIZE = 5;

    /** 得点としてカウントする event_type（本戦のみ・PK 戦 PENALTY_SHOOTOUT は除外・soccer §6.1）。 */
    private static final Set<MatchEventType> GOAL_TYPES =
            EnumSet.of(MatchEventType.GOAL, MatchEventType.PENALTY_GOAL);

    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final PlayerAppearanceRepository appearanceRepository;
    private final MatchService matchService;
    private final UserRepository userRepository;

    // ─────────────────────────────────────────────
    // F.1 個人キャリア統計
    // ─────────────────────────────────────────────

    /**
     * 個人キャリア統計を集計する（02 §F.1・soccer §6.1）。
     *
     * @param organizationId 認証テナント（必須・越境遮断）
     * @param userId         対象ユーザー
     * @param teamId         team スコープ絞り（他者閲覧時に指定・本人横断は null）
     * @param from           集計開始（kickoff_at・null=無制限）
     * @param to             集計終了（null=無制限）
     * @param kind           kind 絞り（null=全種別）
     * @param sport          競技絞り（null=全競技）
     */
    public UserMatchStatsResponse aggregateUserStats(Long organizationId, Long userId, Long teamId,
                                                     LocalDateTime from, LocalDateTime to,
                                                     MatchKind kind, Sport sport) {
        List<MatchEntity> matches = matchRepository.findForUserStats(
                organizationId, userId, teamId, from, to, kind, sport);
        if (matches.isEmpty()) {
            return emptyUserStats(userId);
        }
        List<UUID> matchIds = matches.stream().map(MatchEntity::getId).toList();
        Map<UUID, MatchEntity> matchById = matches.stream()
                .collect(Collectors.toMap(MatchEntity::getId, m -> m));

        List<PlayerAppearanceEntity> appearances =
                appearanceRepository.findByMatchIdInAndPlayerUserId(matchIds, userId);
        List<MatchEventEntity> events = matchEventRepository.findByMatchIdIn(matchIds).stream()
                .filter(e -> userId.equals(e.getPlayerUserId()))
                .toList();

        // 出場系（appearances から）
        int totalMatches = appearances.size();
        int totalMinutes = appearances.stream()
                .map(PlayerAppearanceEntity::getComputedMinutes)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
        int starterMatches = (int) appearances.stream()
                .filter(PlayerAppearanceEntity::isStarter).count();

        // 得点系（events から・本戦のみ＝PENALTY_SHOOTOUT は GOAL_TYPES 非該当ゆえ自動除外）
        int goals = (int) events.stream().filter(e -> GOAL_TYPES.contains(e.getEventType())).count();
        int assists = (int) events.stream().filter(e -> e.getEventType() == MatchEventType.ASSIST).count();
        int ownGoals = (int) events.stream().filter(e -> e.getEventType() == MatchEventType.OWN_GOAL).count();
        int yellow = (int) events.stream()
                .filter(e -> e.getEventType() == MatchEventType.YELLOW_CARD
                        || e.getEventType() == MatchEventType.SECOND_YELLOW).count();
        int red = (int) events.stream().filter(e -> e.getEventType() == MatchEventType.RED_CARD).count();

        double starterRate = totalMatches == 0 ? 0.0 : (double) starterMatches / totalMatches;
        double avgMinutes = totalMatches == 0 ? 0.0 : (double) totalMinutes / totalMatches;
        // goalsPer90: totalMinutes=0 のとき NULL（0 除算を握りつぶさない・02 §未解決 4）
        Double goalsPer90 = totalMinutes == 0 ? null : goals / (totalMinutes / 90.0);

        return UserMatchStatsResponse.builder()
                .userId(userId)
                .totalMatches(totalMatches)
                .totalMinutes(totalMinutes)
                .goals(goals)
                .assists(assists)
                .ownGoals(ownGoals)
                .yellowCards(yellow)
                .redCards(red)
                .starterMatches(starterMatches)
                .starterRate(starterRate)
                .avgMinutes(avgMinutes)
                .goalsPer90(goalsPer90)
                .monthlyTrend(buildMonthlyTrend(appearances, events, matchById))
                .seasonTrend(buildSeasonTrend(appearances, events, matchById))
                .byKind(buildByKind(appearances, events, matchById))
                .build();
    }

    private UserMatchStatsResponse emptyUserStats(Long userId) {
        return UserMatchStatsResponse.builder()
                .userId(userId)
                .totalMatches(0).totalMinutes(0).goals(0).assists(0).ownGoals(0)
                .yellowCards(0).redCards(0).starterMatches(0)
                .starterRate(0.0).avgMinutes(0.0).goalsPer90(null)
                .monthlyTrend(List.of()).seasonTrend(List.of()).byKind(List.of())
                .build();
    }

    /** 月別推移（ライン用・02 §F.5）。試合のキックオフ月をキーに集計（サーバー TZ・02 §F.1）。 */
    private List<UserMatchStatsResponse.MonthlyStat> buildMonthlyTrend(
            List<PlayerAppearanceEntity> appearances, List<MatchEventEntity> events,
            Map<UUID, MatchEntity> matchById) {
        Map<String, int[]> acc = new LinkedHashMap<>(); // [matches, minutes, goals, assists]
        for (PlayerAppearanceEntity ap : appearances) {
            MatchEntity m = matchById.get(ap.getMatchId());
            String key = monthKey(m);
            if (key == null) {
                continue;
            }
            int[] v = acc.computeIfAbsent(key, k -> new int[4]);
            v[0]++;
            v[1] += nz(ap.getComputedMinutes());
        }
        for (MatchEventEntity e : events) {
            MatchEntity m = matchById.get(e.getMatchId());
            String key = monthKey(m);
            if (key == null) {
                continue;
            }
            int[] v = acc.computeIfAbsent(key, k -> new int[4]);
            if (GOAL_TYPES.contains(e.getEventType())) {
                v[2]++;
            } else if (e.getEventType() == MatchEventType.ASSIST) {
                v[3]++;
            }
        }
        return acc.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> UserMatchStatsResponse.MonthlyStat.builder()
                        .month(en.getKey())
                        .matches(en.getValue()[0]).minutes(en.getValue()[1])
                        .goals(en.getValue()[2]).assists(en.getValue()[3])
                        .build())
                .toList();
    }

    /** シーズン別推移（MVP は暦年・02 §未解決 5）。 */
    private List<UserMatchStatsResponse.SeasonStat> buildSeasonTrend(
            List<PlayerAppearanceEntity> appearances, List<MatchEventEntity> events,
            Map<UUID, MatchEntity> matchById) {
        Map<String, int[]> acc = new LinkedHashMap<>();
        for (PlayerAppearanceEntity ap : appearances) {
            String key = seasonKey(matchById.get(ap.getMatchId()));
            if (key == null) {
                continue;
            }
            int[] v = acc.computeIfAbsent(key, k -> new int[4]);
            v[0]++;
            v[1] += nz(ap.getComputedMinutes());
        }
        for (MatchEventEntity e : events) {
            String key = seasonKey(matchById.get(e.getMatchId()));
            if (key == null) {
                continue;
            }
            int[] v = acc.computeIfAbsent(key, k -> new int[4]);
            if (GOAL_TYPES.contains(e.getEventType())) {
                v[2]++;
            } else if (e.getEventType() == MatchEventType.ASSIST) {
                v[3]++;
            }
        }
        return acc.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> UserMatchStatsResponse.SeasonStat.builder()
                        .season(en.getKey())
                        .matches(en.getValue()[0]).minutes(en.getValue()[1])
                        .goals(en.getValue()[2]).assists(en.getValue()[3])
                        .build())
                .toList();
    }

    /** kind 別内訳（doughnut/bar 用）。 */
    private List<UserMatchStatsResponse.KindStat> buildByKind(
            List<PlayerAppearanceEntity> appearances, List<MatchEventEntity> events,
            Map<UUID, MatchEntity> matchById) {
        Map<MatchKind, int[]> acc = new LinkedHashMap<>();
        for (PlayerAppearanceEntity ap : appearances) {
            MatchEntity m = matchById.get(ap.getMatchId());
            if (m == null) {
                continue;
            }
            int[] v = acc.computeIfAbsent(m.getKind(), k -> new int[4]);
            v[0]++;
            v[1] += nz(ap.getComputedMinutes());
        }
        for (MatchEventEntity e : events) {
            MatchEntity m = matchById.get(e.getMatchId());
            if (m == null) {
                continue;
            }
            int[] v = acc.computeIfAbsent(m.getKind(), k -> new int[4]);
            if (GOAL_TYPES.contains(e.getEventType())) {
                v[2]++;
            } else if (e.getEventType() == MatchEventType.ASSIST) {
                v[3]++;
            }
        }
        return acc.entrySet().stream()
                .map(en -> UserMatchStatsResponse.KindStat.builder()
                        .kind(en.getKey().name())
                        .matches(en.getValue()[0]).minutes(en.getValue()[1])
                        .goals(en.getValue()[2]).assists(en.getValue()[3])
                        .build())
                .toList();
    }

    // ─────────────────────────────────────────────
    // F.2 個人タイムライン（ページング）
    // ─────────────────────────────────────────────

    /**
     * 個人タイムライン（試合別の関与イベント）を集計する（02 §F.2・ページング）。
     *
     * @return ページ内エントリ（新しい試合順）と総件数
     */
    public TimelinePage aggregateUserTimeline(Long organizationId, Long userId, Long teamId,
                                              LocalDateTime from, LocalDateTime to,
                                              MatchKind kind, Sport sport, int page, int size) {
        List<MatchEntity> matches = matchRepository.findForUserStats(
                organizationId, userId, teamId, from, to, kind, sport);
        if (matches.isEmpty()) {
            return new TimelinePage(List.of(), 0);
        }
        // 新しい試合順（kickoff_at desc・null は末尾）
        List<MatchEntity> sorted = matches.stream()
                .sorted(Comparator.comparing(MatchEntity::getKickoffAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        long total = sorted.size();

        int fromIdx = Math.min(page * size, sorted.size());
        int toIdx = Math.min(fromIdx + size, sorted.size());
        List<MatchEntity> pageMatches = sorted.subList(fromIdx, toIdx);
        if (pageMatches.isEmpty()) {
            return new TimelinePage(List.of(), total);
        }
        List<UUID> pageIds = pageMatches.stream().map(MatchEntity::getId).toList();

        Map<UUID, PlayerAppearanceEntity> apByMatch =
                appearanceRepository.findByMatchIdInAndPlayerUserId(pageIds, userId).stream()
                        .collect(Collectors.toMap(PlayerAppearanceEntity::getMatchId, a -> a, (a, b) -> a));
        Map<UUID, List<MatchEventEntity>> evByMatch =
                matchEventRepository.findByMatchIdIn(pageIds).stream()
                        .filter(e -> userId.equals(e.getPlayerUserId()))
                        .collect(Collectors.groupingBy(MatchEventEntity::getMatchId));

        List<UserMatchTimelineEntry> entries = new ArrayList<>();
        for (MatchEntity m : pageMatches) {
            PlayerAppearanceEntity ap = apByMatch.get(m.getId());
            List<MatchEventEntity> evs = evByMatch.getOrDefault(m.getId(), List.of());
            int g = (int) evs.stream().filter(e -> GOAL_TYPES.contains(e.getEventType())).count();
            int a = (int) evs.stream().filter(e -> e.getEventType() == MatchEventType.ASSIST).count();
            int y = (int) evs.stream().filter(e -> e.getEventType() == MatchEventType.YELLOW_CARD
                    || e.getEventType() == MatchEventType.SECOND_YELLOW).count();
            int r = (int) evs.stream().filter(e -> e.getEventType() == MatchEventType.RED_CARD).count();
            TeamSide side = ap != null ? ap.getTeamSide() : null;
            entries.add(UserMatchTimelineEntry.builder()
                    .matchId(m.getId())
                    .kickoffAt(m.getKickoffAt())
                    .opponent(resolveOpponentLabel(m, side))
                    .computedMinutes(ap != null ? ap.getComputedMinutes() : null)
                    .goals(g).assists(a).yellowCards(y).redCards(r)
                    .result(resolveResult(m, side))
                    .build());
        }
        return new TimelinePage(entries, total);
    }

    /** タイムラインのページ結果（エントリ＋総件数）。 */
    public record TimelinePage(List<UserMatchTimelineEntry> entries, long total) {
    }

    // ─────────────────────────────────────────────
    // F.3 チーム統計
    // ─────────────────────────────────────────────

    /**
     * チーム統計を集計する（02 §F.3・soccer §6.2）。
     *
     * @param includeRankings 選手別ランキングを含めるか（MEMBER 以上のみ true・SUPPORTER は false・02 §F.3）
     * @param rankingLimit    ランキングの top-N 上限（N+1/肥大回避・02 §F.3）
     */
    public TeamMatchStatsResponse aggregateTeamStats(Long organizationId, Long teamId,
                                                     LocalDateTime from, LocalDateTime to,
                                                     MatchKind kind, Sport sport,
                                                     boolean includeRankings, int rankingLimit) {
        List<MatchEntity> matches = matchRepository.findForTeamStats(
                organizationId, teamId, from, to, kind, sport);

        int wins = 0;
        int draws = 0;
        int losses = 0;
        int gf = 0;
        int ga = 0;
        List<String> recentForm = new ArrayList<>();
        Map<MatchKind, int[]> kindAcc = new LinkedHashMap<>(); // [matches,w,d,l,gf,ga]

        // 勝敗・得失点（本戦スコア＝home/away_score・soccer §4.3）。NEUTRAL は別カテゴリだが W/D/L 集計には含める。
        for (MatchEntity m : matches) {
            TeamSide side = sideOfTeam(m, teamId);
            Integer myScore = scoreForSide(m, side);
            Integer oppScore = scoreForSide(m, opposite(side));
            int[] kv = kindAcc.computeIfAbsent(m.getKind(), k -> new int[6]);
            kv[0]++;
            if (myScore != null && oppScore != null) {
                gf += myScore;
                ga += oppScore;
                kv[4] += myScore;
                kv[5] += oppScore;
                String r = result(myScore, oppScore);
                switch (r) {
                    case "W" -> {
                        wins++;
                        kv[1]++;
                    }
                    case "D" -> {
                        draws++;
                        kv[2]++;
                    }
                    default -> {
                        losses++;
                        kv[3]++;
                    }
                }
                recentForm.add(r);
            }
        }

        // 直近 RECENT_FORM_SIZE 件に絞る（findForTeamStats は kickoffAt ASC 順のため末尾が最新）。
        // 古い→新しい順を維持（TeamMatchStatsResponse.recentForm の Javadoc 仕様）。
        List<String> trimmedRecentForm = recentForm.size() > RECENT_FORM_SIZE
                ? recentForm.subList(recentForm.size() - RECENT_FORM_SIZE, recentForm.size())
                : recentForm;

        List<TeamMatchStatsResponse.PlayerRanking> rankings =
                includeRankings ? buildPlayerRankings(matches, teamId, rankingLimit) : List.of();

        List<TeamMatchStatsResponse.KindBreakdown> byKind = kindAcc.entrySet().stream()
                .map(en -> TeamMatchStatsResponse.KindBreakdown.builder()
                        .kind(en.getKey().name())
                        .matches(en.getValue()[0])
                        .wins(en.getValue()[1]).draws(en.getValue()[2]).losses(en.getValue()[3])
                        .goalsFor(en.getValue()[4]).goalsAgainst(en.getValue()[5])
                        .build())
                .toList();

        return TeamMatchStatsResponse.builder()
                .teamId(teamId)
                .totalMatches(matches.size())
                .wins(wins).draws(draws).losses(losses)
                .totalGoalsFor(gf).totalGoalsAgainst(ga)
                .goalDifference(gf - ga)
                .recentForm(trimmedRecentForm)
                .playerRankings(rankings)
                .byKind(byKind)
                .build();
    }

    /**
     * 選手別ランキングを構築する（goals desc・top-N 上限）。
     *
     * <p>当該チーム side のイベントから登録選手（player_user_id≠null）のみ集計し、表示名は
     * {@code findByIdIn} で一括取得して匿名化追従（原則 4・N+1 回避）。</p>
     */
    private List<TeamMatchStatsResponse.PlayerRanking> buildPlayerRankings(
            List<MatchEntity> matches, Long teamId, int limit) {
        if (matches.isEmpty()) {
            return List.of();
        }
        // matchId → そのチームの side をマップ化（HOME/AWAY 片側のみ集計）
        Map<UUID, TeamSide> sideByMatch = new HashMap<>();
        List<UUID> matchIds = new ArrayList<>();
        for (MatchEntity m : matches) {
            sideByMatch.put(m.getId(), sideOfTeam(m, teamId));
            matchIds.add(m.getId());
        }
        Map<UUID, MatchEntity> matchById = matches.stream()
                .collect(Collectors.toMap(MatchEntity::getId, m -> m));

        // 出場分（appearances）と得点/アシスト（events）を player_user_id 単位に集計
        Map<Long, int[]> stat = new HashMap<>(); // userId → [goals, assists, minutes]
        for (PlayerAppearanceEntity ap : appearanceRepository.findByMatchIdIn(matchIds)) {
            if (ap.getPlayerUserId() == null) {
                continue;
            }
            if (ap.getTeamSide() != sideByMatch.get(ap.getMatchId())) {
                continue; // 当該チーム side のみ
            }
            stat.computeIfAbsent(ap.getPlayerUserId(), k -> new int[3])[2] += nz(ap.getComputedMinutes());
        }
        for (MatchEventEntity e : matchEventRepository.findByMatchIdIn(matchIds)) {
            if (e.getPlayerUserId() == null) {
                continue;
            }
            if (e.getTeamSide() != sideByMatch.get(e.getMatchId())) {
                continue;
            }
            int[] v = stat.computeIfAbsent(e.getPlayerUserId(), k -> new int[3]);
            if (GOAL_TYPES.contains(e.getEventType())) {
                v[0]++;
            } else if (e.getEventType() == MatchEventType.ASSIST) {
                v[1]++;
            }
        }
        if (stat.isEmpty()) {
            return List.of();
        }
        // goals desc → assists desc → minutes desc で top-N
        List<Map.Entry<Long, int[]>> sorted = stat.entrySet().stream()
                .sorted((x, y) -> {
                    int c = Integer.compare(y.getValue()[0], x.getValue()[0]);
                    if (c != 0) {
                        return c;
                    }
                    c = Integer.compare(y.getValue()[1], x.getValue()[1]);
                    if (c != 0) {
                        return c;
                    }
                    return Integer.compare(y.getValue()[2], x.getValue()[2]);
                })
                .limit(Math.max(0, limit))
                .toList();

        List<Long> userIds = sorted.stream().map(Map.Entry::getKey).toList();
        Map<Long, String> nameById = userRepository.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getDisplayName));

        return sorted.stream()
                .map(en -> TeamMatchStatsResponse.PlayerRanking.builder()
                        .userId(en.getKey())
                        .displayName(nameById.getOrDefault(en.getKey(), null))
                        .goals(en.getValue()[0]).assists(en.getValue()[1]).minutes(en.getValue()[2])
                        .build())
                .toList();
    }

    // ─────────────────────────────────────────────
    // F.4 試合内 API
    // ─────────────────────────────────────────────

    /**
     * 試合内タイムラインを取得する（02 §F.4）。閲覧可否は呼び出し元（Controller）が事前検証する。
     * イベント一覧＋スコア整合警告（{@code scoreMismatch}・02 §E.5・soccer §4.2）を返す。
     */
    public MatchEventsResponse getMatchEvents(UUID matchId, Long organizationId) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        List<MatchEventEntity> events =
                matchEventRepository.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId);

        int[] derived = deriveScores(events);
        Integer home = match.getHomeScore();
        Integer away = match.getAwayScore();
        boolean mismatch = (home != null && home != derived[0]) || (away != null && away != derived[1]);

        return MatchEventsResponse.builder()
                .events(events.stream().map(MatchEventResponse::from).toList())
                .scoreMismatch(mismatch)
                .derivedHomeScore(derived[0])
                .derivedAwayScore(derived[1])
                .build();
    }

    /**
     * 試合内の出場記録一覧を取得する（02 §F.4・両サイド・computed_minutes 込み）。
     */
    public List<PlayerAppearanceResponse> getMatchAppearances(UUID matchId, Long organizationId) {
        matchService.getMatchOrThrow(matchId, organizationId); // テナント帰属確認（IDOR 1 段目）
        return appearanceRepository.findByMatchId(matchId).stream()
                .map(PlayerAppearanceResponse::from)
                .toList();
    }

    /**
     * イベントからホーム/アウェイ本戦得点を導出する（soccer §4.2）。
     *
     * <p>GOAL＋PENALTY_GOAL は自サイドへ、OWN_GOAL は<b>相手サイド</b>へ加算。PK 戦 PENALTY_SHOOTOUT は対象外。</p>
     */
    private int[] deriveScores(List<MatchEventEntity> events) {
        int home = 0;
        int away = 0;
        for (MatchEventEntity e : events) {
            TeamSide side = e.getTeamSide();
            if (side == null) {
                continue;
            }
            if (GOAL_TYPES.contains(e.getEventType())) {
                if (side == TeamSide.HOME) {
                    home++;
                } else {
                    away++;
                }
            } else if (e.getEventType() == MatchEventType.OWN_GOAL) {
                // OWN_GOAL は相手サイドへ加算（自殺点）
                if (side == TeamSide.HOME) {
                    away++;
                } else {
                    home++;
                }
            }
        }
        return new int[]{home, away};
    }

    // ─────────────────────────────────────────────
    // ヘルパー（勝敗・サイド・期間キー）
    // ─────────────────────────────────────────────

    private TeamSide sideOfTeam(MatchEntity m, Long teamId) {
        // 主体チームは HOME、登録相手として teamId が一致すれば AWAY（NEUTRAL でも team_side は HOME/AWAY 2 値）
        if (teamId.equals(m.getTeamId())) {
            return TeamSide.HOME;
        }
        if (teamId.equals(m.getOpponentTeamId())) {
            return TeamSide.AWAY;
        }
        return TeamSide.HOME; // 関与しない試合は findForTeamStats で除外済み
    }

    private TeamSide opposite(TeamSide side) {
        return side == TeamSide.HOME ? TeamSide.AWAY : TeamSide.HOME;
    }

    private Integer scoreForSide(MatchEntity m, TeamSide side) {
        return side == TeamSide.HOME ? m.getHomeScore() : m.getAwayScore();
    }

    /** 勝敗（本戦スコアから W/D/L・soccer §4.3）。両スコア確定時のみ判定し未確定は null（呼び出し側で除外）。 */
    private String resolveResult(MatchEntity m, TeamSide side) {
        if (side == null) {
            return null;
        }
        Integer my = scoreForSide(m, side);
        Integer opp = scoreForSide(m, opposite(side));
        if (my == null || opp == null) {
            return null;
        }
        return result(my, opp);
    }

    private String result(int my, int opp) {
        if (my > opp) {
            return "W";
        }
        if (my < opp) {
            return "L";
        }
        return "D";
    }

    private String resolveOpponentLabel(MatchEntity m, TeamSide side) {
        // 本人が HOME 側なら相手は登録相手 or opponentName。AWAY 側なら相手は主体チーム（ID のみ）。
        if (side == TeamSide.AWAY) {
            return m.getOpponentName() != null ? m.getOpponentName()
                    : ("team:" + m.getTeamId());
        }
        if (m.getOpponentName() != null && !m.getOpponentName().isBlank()) {
            return m.getOpponentName();
        }
        return m.getOpponentTeamId() != null ? ("team:" + m.getOpponentTeamId()) : null;
    }

    private String monthKey(MatchEntity m) {
        if (m == null || m.getKickoffAt() == null) {
            return null;
        }
        return m.getKickoffAt().format(MONTH_FMT);
    }

    /** シーズンキー（MVP は暦年・02 §未解決 5）。 */
    private String seasonKey(MatchEntity m) {
        if (m == null || m.getKickoffAt() == null) {
            return null;
        }
        return String.valueOf(m.getKickoffAt().getYear());
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
