package com.mannschaft.app.tournament.listener;

import com.mannschaft.app.match.MatchCompletedEvent;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.dto.MatchScoringTally;
import com.mannschaft.app.match.service.MatchScoringTallyService;
import com.mannschaft.app.tournament.BasicStatKeys;
import com.mannschaft.app.tournament.RankingsRecalculationEvent;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentFixturePlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchdayRepository;
import com.mannschaft.app.tournament.service.FixtureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchScoreFixtureListener} の純 UT（test-first・05 §H.2 / 06 §I.2 第一陣）。
 *
 * <p>検証: (a) 単独試合（fixtureId=null）無視、(b) fixture 引当→既存 {@link FixtureService#updateScore}
 * へスコア反映、(c) 順位再計算（既存 updateScore 内の {@code StandingsRecalculationEvent} 発火）が起動、
 * (d) 冪等（再発火で全列置換）、(e) participant⇔side（home participant=HOME 固定）。
 * 加えて fixture 引当不能時に例外を投げずスキップする（越境で壊さない）ことを確認する。
 * 依存はすべて Mockito モック（純リスナー層）。トートロジーではなく、実引数（ScoreUpdateRequest）を捕捉して
 * 本戦合算スコア・延長 null・PK 分離・home/away マッピングを実アサートする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchScoreFixtureListener 順位連携 UT")
class MatchScoreFixtureListenerTest {

    private static final long FIXTURE_ID = 8001L;
    private static final long MATCHDAY_ID = 700L;
    private static final long DIVISION_ID = 90L;
    private static final long TOURNAMENT_ID = 5L;

    @Mock
    private TournamentFixtureRepository fixtureRepository;
    @Mock
    private TournamentMatchdayRepository matchdayRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private FixtureService tournamentMatchService;
    @Mock
    private MatchScoringTallyService matchScoringTallyService;
    @Mock
    private TournamentFixturePlayerStatRepository playerStatRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MatchScoreFixtureListener listener;

    private TournamentFixtureEntity fixture;

    @BeforeEach
    void setUp() {
        fixture = TournamentFixtureEntity.builder()
                .matchdayId(MATCHDAY_ID)
                .homeParticipantId(11L)
                .awayParticipantId(22L)
                .build();

        TournamentMatchdayEntity matchday = TournamentMatchdayEntity.builder()
                .divisionId(DIVISION_ID)
                .name("第1節")
                .matchdayNumber(1)
                .build();
        TournamentDivisionEntity division = TournamentDivisionEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .name("1部")
                .build();

        lenient().when(fixtureRepository.findById(FIXTURE_ID)).thenReturn(Optional.of(fixture));
        lenient().when(matchdayRepository.findById(MATCHDAY_ID)).thenReturn(Optional.of(matchday));
        lenient().when(divisionRepository.findById(DIVISION_ID)).thenReturn(Optional.of(division));
    }

    private MatchCompletedEvent event(Long fixtureId, Integer home, Integer away,
                                      Integer homePk, Integer awayPk) {
        return MatchCompletedEvent.builder()
                .matchId(UUID.randomUUID())
                .tournamentFixtureId(fixtureId)
                .homeScore(home)
                .awayScore(away)
                .homePenaltyScore(homePk)
                .awayPenaltyScore(awayPk)
                .status(MatchStatus.COMPLETED)
                .build();
    }

    // ─── (a) 単独試合（fixtureId=null）は無視 ──────────────────────

    @Test
    @DisplayName("(a) tournamentFixtureId=null（単独試合）は何もしない（updateScore 非呼出・fixture 引当なし）")
    void nullFixtureIsIgnored() {
        listener.onMatchCompleted(event(null, 2, 1, null, null));

        verify(tournamentMatchService, never()).updateScore(any(), any(), any());
        verify(fixtureRepository, never()).findById(any());
    }

    // ─── (b) fixture 引当→スコア反映（本戦合算・延長 null・PK 分離） ──

    @Test
    @DisplayName("(b) fixture 引当→既存 updateScore に本戦合算スコアを反映（延長は本戦合算済・PK は分離値）")
    void resolvedFixtureUpdatesScore() {
        // 本戦 3-2（延長合算済み）＋ PK 5-4
        listener.onMatchCompleted(event(FIXTURE_ID, 3, 2, 5, 4));

        ArgumentCaptor<ScoreUpdateRequest> captor = ArgumentCaptor.forClass(ScoreUpdateRequest.class);
        verify(tournamentMatchService).updateScore(eq(TOURNAMENT_ID), eq(FIXTURE_ID), captor.capture());
        ScoreUpdateRequest req = captor.getValue();

        // 本戦スコア（イベントで延長合算済み）
        assertThat(req.getHomeScore()).isEqualTo(3);
        assertThat(req.getAwayScore()).isEqualTo(2);
        // 延長別スコアは Phase 5b-3 で廃止（イベントで本戦合算済み・05 §H.1 移行表）
        // PK 戦は分離値をそのまま渡す
        assertThat(req.getHomePenaltyScore()).isEqualTo(5);
        assertThat(req.getAwayPenaltyScore()).isEqualTo(4);
    }

    // ─── (c) 順位再計算の起動（updateScore 経由で既存 StandingsRecalc が走る） ──

    @Test
    @DisplayName("(c) 順位再計算は既存 updateScore 内の StandingsRecalculationEvent 発火に乗る（updateScore が起動される）")
    void standingsRecalculationIsTriggeredViaUpdateScore() {
        listener.onMatchCompleted(event(FIXTURE_ID, 1, 0, null, null));

        // 既存 updateScore は内部で divisionId/tournamentId 指定の StandingsRecalculationEvent を発火する。
        // 本リスナーが正しい tournamentId/fixtureId で updateScore を起動することが順位連携の発火点。
        verify(tournamentMatchService).updateScore(eq(TOURNAMENT_ID), eq(FIXTURE_ID), any());
    }

    // ─── (d) 冪等（再発火で全列置換・二重計上しない） ──────────────

    @Test
    @DisplayName("(d) 同一 fixture への再発火（訂正）でも updateScore を最新値で再呼出＝全列置換で冪等")
    void idempotentOnRefire() {
        // 1 回目: 1-0
        listener.onMatchCompleted(event(FIXTURE_ID, 1, 0, null, null));
        // 2 回目（訂正・再 COMPLETED）: 2-2 + PK 4-3
        listener.onMatchCompleted(event(FIXTURE_ID, 2, 2, 4, 3));

        ArgumentCaptor<ScoreUpdateRequest> captor = ArgumentCaptor.forClass(ScoreUpdateRequest.class);
        verify(tournamentMatchService, times(2)).updateScore(eq(TOURNAMENT_ID), eq(FIXTURE_ID), captor.capture());

        // 2 回目は最新値で全列上書き（加算ではなく置換）
        ScoreUpdateRequest second = captor.getAllValues().get(1);
        assertThat(second.getHomeScore()).isEqualTo(2);
        assertThat(second.getAwayScore()).isEqualTo(2);
        assertThat(second.getHomePenaltyScore()).isEqualTo(4);
        assertThat(second.getAwayPenaltyScore()).isEqualTo(3);
    }

    // ─── (e) participant⇔side: home participant = HOME 固定 ──────────

    @Test
    @DisplayName("(e) participant⇔side は home participant=HOME 固定: イベントの homeScore が ScoreUpdateRequest.homeScore へ対応")
    void participantSideMappingHomeIsHome() {
        // home=4, away=1。fixture.homeParticipantId(11) が HOME 側＝homeScore を受ける。
        listener.onMatchCompleted(event(FIXTURE_ID, 4, 1, null, null));

        ArgumentCaptor<ScoreUpdateRequest> captor = ArgumentCaptor.forClass(ScoreUpdateRequest.class);
        verify(tournamentMatchService).updateScore(eq(TOURNAMENT_ID), eq(FIXTURE_ID), captor.capture());
        ScoreUpdateRequest req = captor.getValue();

        // home participant 側＝HOME に homeScore、away participant 側＝AWAY に awayScore（入替えない）
        assertThat(req.getHomeScore()).isEqualTo(4);
        assertThat(req.getAwayScore()).isEqualTo(1);
        // fixture の participant 割当（home=11 / away=22）は不変であることを確認（HOME 固定の前提）
        assertThat(fixture.getHomeParticipantId()).isEqualTo(11L);
        assertThat(fixture.getAwayParticipantId()).isEqualTo(22L);
    }

    // ─── 入口①非破壊: セット制大会でも sets=null で委譲（セット判定に巻き込まれない） ──

    @Test
    @DisplayName("入口①非破壊: リスナーは常に sets=null で updateScore へ委譲する（hasSets大会でも本戦スコア判定にフォールバックさせる）")
    void delegatesWithNullSetsAlways() {
        // サッカー等の単一スコア試合完了イベント。リスナーはセット概念を持たないため sets=null で渡す。
        // 受け側 determineResult は hasSets=true 大会でも sets=null なら本戦スコア判定へフォールバックし無害。
        listener.onMatchCompleted(event(FIXTURE_ID, 2, 1, null, null));

        ArgumentCaptor<ScoreUpdateRequest> captor = ArgumentCaptor.forClass(ScoreUpdateRequest.class);
        verify(tournamentMatchService).updateScore(eq(TOURNAMENT_ID), eq(FIXTURE_ID), captor.capture());
        assertThat(captor.getValue().getSets()).isNull();
    }

    // ─── 引当不能（fixture なし）は例外を投げずスキップ（越境で壊さない） ──

    @Test
    @DisplayName("fixture 引当不能でも例外を投げず updateScore を呼ばずにスキップする（05 §H.2 (b)）")
    void missingFixtureSkipsWithoutThrowing() {
        when(fixtureRepository.findById(FIXTURE_ID)).thenReturn(Optional.empty());

        listener.onMatchCompleted(event(FIXTURE_ID, 1, 0, null, null));

        verify(tournamentMatchService, never()).updateScore(any(), any(), any());
    }

    @Test
    @DisplayName("tournamentId 解決不能（matchday/division 欠落）でも例外を投げずスキップする")
    void unresolvableTournamentIdSkips() {
        when(matchdayRepository.findById(MATCHDAY_ID)).thenReturn(Optional.empty());

        listener.onMatchCompleted(event(FIXTURE_ID, 1, 0, null, null));

        verify(tournamentMatchService, never()).updateScore(any(), any(), any());
    }

    // ─── Phase 5b-2: 個人ランキング基本スタッツ（得点/アシスト）の match_events 同期 ──

    @Test
    @DisplayName("(5b-2 a) match_events 集計を得点/アシストの fixture スナップショットへ同期し participant を side で引当てる")
    void syncsBasicPlayerStatsFromMatchEvents() {
        // HOME 選手 10: 2得点/1アシスト、AWAY 選手 30: 1得点
        given(matchScoringTallyService.tallyScoringStatsForMatch(any(UUID.class))).willReturn(List.of(
                new MatchScoringTally(10L, TeamSide.HOME, 2, 1),
                new MatchScoringTally(30L, TeamSide.AWAY, 1, 0)));

        listener.onMatchCompleted(event(FIXTURE_ID, 3, 1, null, null));

        ArgumentCaptor<TournamentFixturePlayerStatEntity> captor =
                ArgumentCaptor.forClass(TournamentFixturePlayerStatEntity.class);
        // 2 選手 × {goals, assists} = 4 行
        verify(playerStatRepository, times(4)).save(captor.capture());

        Map<String, TournamentFixturePlayerStatEntity> byKey = captor.getAllValues().stream()
                .collect(Collectors.toMap(s -> s.getUserId() + ":" + s.getStatKey(), Function.identity()));

        // HOME 選手 10 → home participant(11) に得点 2 / アシスト 1
        assertThat(byKey.get("10:goals").getValueInt()).isEqualTo(2);
        assertThat(byKey.get("10:goals").getParticipantId()).isEqualTo(11L);
        assertThat(byKey.get("10:goals").getMatchId()).isEqualTo(FIXTURE_ID);
        assertThat(byKey.get("10:assists").getValueInt()).isEqualTo(1);
        // AWAY 選手 30 → away participant(22) に得点 1 / アシスト 0
        assertThat(byKey.get("30:goals").getValueInt()).isEqualTo(1);
        assertThat(byKey.get("30:goals").getParticipantId()).isEqualTo(22L);
        assertThat(byKey.get("30:assists").getValueInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("(5b-2 b) 同期前に基本 statKey 群を delete してから insert（冪等・大会固有 statKey は削除対象外）")
    void deletesBasicStatKeysBeforeInsertForIdempotency() {
        given(matchScoringTallyService.tallyScoringStatsForMatch(any(UUID.class))).willReturn(List.of(
                new MatchScoringTally(10L, TeamSide.HOME, 1, 0)));

        listener.onMatchCompleted(event(FIXTURE_ID, 1, 0, null, null));

        // 削除は基本 statKey（goals/assists）のみを対象とする（大会固有 statKey=H.6 は残置）
        verify(playerStatRepository).deleteByMatchIdAndStatKeyIn(FIXTURE_ID, BasicStatKeys.ALL);
        assertThat(BasicStatKeys.ALL).containsExactlyInAnyOrder("goals", "assists");
    }

    @Test
    @DisplayName("(5b-2 c) スナップショット同期後に RankingsRecalculationEvent を発火する")
    void firesRankingsRecalculationAfterSync() {
        given(matchScoringTallyService.tallyScoringStatsForMatch(any(UUID.class))).willReturn(List.of(
                new MatchScoringTally(10L, TeamSide.HOME, 1, 0)));

        listener.onMatchCompleted(event(FIXTURE_ID, 1, 0, null, null));

        ArgumentCaptor<RankingsRecalculationEvent> captor =
                ArgumentCaptor.forClass(RankingsRecalculationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getTournamentId()).isEqualTo(TOURNAMENT_ID);
    }

    @Test
    @DisplayName("(5b-2 d) 得点者が居ない試合でも delete とランキング再計算は走る（0 行 insert・順位反映の冪等）")
    void emptyTalliesStillDeletesAndRecalculates() {
        given(matchScoringTallyService.tallyScoringStatsForMatch(any(UUID.class))).willReturn(List.of());

        listener.onMatchCompleted(event(FIXTURE_ID, 0, 0, null, null));

        verify(playerStatRepository).deleteByMatchIdAndStatKeyIn(FIXTURE_ID, BasicStatKeys.ALL);
        verify(playerStatRepository, never()).save(any());
        verify(eventPublisher).publishEvent(any(RankingsRecalculationEvent.class));
    }

    @Test
    @DisplayName("(5b-2 e) participant 未割当（fixture の home/away participant=null）の集計はスキップし他を壊さない")
    void skipsTallyWhenParticipantUnassigned() {
        // fixture の away participant 未割当
        fixture = TournamentFixtureEntity.builder()
                .matchdayId(MATCHDAY_ID)
                .homeParticipantId(11L)
                .awayParticipantId(null)
                .build();
        lenient().when(fixtureRepository.findById(FIXTURE_ID)).thenReturn(Optional.of(fixture));

        given(matchScoringTallyService.tallyScoringStatsForMatch(any(UUID.class))).willReturn(List.of(
                new MatchScoringTally(10L, TeamSide.HOME, 1, 0),
                new MatchScoringTally(30L, TeamSide.AWAY, 1, 0)));

        listener.onMatchCompleted(event(FIXTURE_ID, 1, 1, null, null));

        // HOME 側（participant 11）のみ insert（goals/assists 2 行）。AWAY は participant=null でスキップ。
        ArgumentCaptor<TournamentFixturePlayerStatEntity> captor =
                ArgumentCaptor.forClass(TournamentFixturePlayerStatEntity.class);
        verify(playerStatRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(s -> s.getUserId() == 10L);
    }

    @Test
    @DisplayName("(5b-2 f) 単独試合（fixtureId=null）は個人スタッツ同期も走らない")
    void nullFixtureSkipsPlayerStatSync() {
        listener.onMatchCompleted(event(null, 2, 1, null, null));

        verify(matchScoringTallyService, never()).tallyScoringStatsForMatch(any());
        verify(playerStatRepository, never()).deleteByMatchIdAndStatKeyIn(any(), anyCollection());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
