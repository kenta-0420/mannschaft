package com.mannschaft.app.tournament;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.tournament.dto.BatchScoreRequest;
import com.mannschaft.app.tournament.dto.FixtureSetRequest;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.FixtureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link FixtureService} の単体テスト。
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: matchId→tId・divId→tId・mdId→divId の束縛検証が
 * 各変更系メソッドの先頭に追加されたため、{@link #stubAuthzDefaults()} で既定「束縛 OK」を
 * lenient スタブしておく（本テストの関心事は束縛検証自体ではなくビジネスロジックであるため）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FixtureService 単体テスト")
class FixtureServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentMatchdayRepository matchdayRepository;
    @Mock private TournamentFixtureRepository matchRepository;
    @Mock private TournamentFixtureSetRepository matchSetRepository;
    @Mock private TournamentFixtureRosterRepository rosterRepository;
    @Mock private TournamentFixturePlayerStatRepository playerStatRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentStatDefRepository statDefRepository;
    @Mock private TournamentMapper mapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.mannschaft.app.match.service.MatchService matchService;
    @Mock private AccessControlService accessControlService;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private FixtureService service;

    private static final Long ORG_ID = 1L;
    private static final Long TOURNAMENT_ID = 1L;
    private static final Long MATCH_ID = 10L;

    /**
     * 束縛検証（matchId→tId・divId→tId・mdId→divId）の既定「OK」スタブ。
     * 個別テストで束縛不一致（BOLA/IDOR）を検証したい場合は該当スタブを上書きすること。
     */
    @BeforeEach
    void stubAuthzDefaults() {
        lenient().when(matchRepository.countByIdAndTournamentId(anyLong(), anyLong())).thenReturn(1L);
        lenient().when(divisionRepository.findByIdAndTournamentId(anyLong(), anyLong()))
                .thenReturn(Optional.of(TournamentDivisionEntity.builder().tournamentId(TOURNAMENT_ID).build()));
        lenient().when(matchdayRepository.findByIdAndDivisionId(anyLong(), anyLong()))
                .thenReturn(Optional.of(TournamentMatchdayEntity.builder().divisionId(5L).build()));
    }

    /**
     * match 正本化（{@code recordMatchCanonical}）が participant→team を解決できるよう、
     * home/away participant の team_id をスタブする（H.1.2・participant 経由解決）。
     * tournament（org/sport 解決元）も併せて返す。score 系テストで共通利用する。
     */
    private void stubCanonicalRecordingChain(Long homeParticipantId, Long awayParticipantId,
                                             Long homeTeamId, Long awayTeamId) {
        // sport 未指定（TournamentEntity の Builder.Default = SOCCER）の大会を返す既定スタブ。
        stubCanonicalRecordingChainWithSport(homeParticipantId, awayParticipantId,
                homeTeamId, awayTeamId, null);
    }

    /**
     * {@link #stubCanonicalRecordingChain} の sport 明示版（F08.10 多競技対応・🟡-1a）。
     * 大会 {@code sport}（null なら Builder.Default の SOCCER）を持つ tournament を返し、
     * canonical match へ sport が伝播することを検証するために用いる。
     */
    private void stubCanonicalRecordingChainWithSport(Long homeParticipantId, Long awayParticipantId,
                                                      Long homeTeamId, Long awayTeamId, String sport) {
        TournamentEntity.TournamentEntityBuilder tb = TournamentEntity.builder()
                .organizationId(99L).name("t").format(TournamentFormat.LEAGUE).createdBy(1L);
        if (sport != null) {
            tb.sport(sport);
        }
        lenient().when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tb.build()));
        if (homeParticipantId != null) {
            lenient().when(participantRepository.findById(homeParticipantId))
                    .thenReturn(Optional.of(TournamentParticipantEntity.builder()
                            .id(homeParticipantId).divisionId(5L).teamId(homeTeamId).build()));
        }
        if (awayParticipantId != null) {
            lenient().when(participantRepository.findById(awayParticipantId))
                    .thenReturn(Optional.of(TournamentParticipantEntity.builder()
                            .id(awayParticipantId).divisionId(5L).teamId(awayTeamId).build()));
        }
    }

    @Nested
    @DisplayName("updateScore")
    class UpdateScore {

        @Test
        @DisplayName("異常系: 試合が見つからない場合エラー")
        void 試合不存在() {
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(1, 0, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.MATCH_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 負のスコアはエラー")
        void 負のスコア() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));

            assertThatThrownBy(() -> service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(-1, 0, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.INVALID_SCORE);
        }

        @Test
        @DisplayName("正常系: スコア更新で順位表再計算イベントが発火される")
        void スコア更新成功() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(matchRepository.save(any())).willReturn(match);
            TournamentMatchdayEntity md = TournamentMatchdayEntity.builder().divisionId(5L).build();
            given(matchdayRepository.findById(any())).willReturn(Optional.of(md));
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());
            given(playerStatRepository.findByMatchId(any())).willReturn(List.of());
            given(mapper.toMatchResponse(any(), any(), any())).willReturn(null);

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null));

            verify(eventPublisher).publishEvent(any(StandingsRecalculationEvent.class));
        }

        @Test
        @DisplayName("異常系: 楽観ロック — stale client（request.version=3 vs entity.version=5）は 409 相当の例外で弾かれ更新されない")
        void 楽観ロック衝突() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(5L).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));

            assertThatThrownBy(() -> service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, null, null, null, 3L, null)))
                    // GlobalExceptionHandler で HTTP 409（CONFLICT）に変換される例外型
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);

            // 衝突時はスコア保存も順位再計算イベント発火も起きない（サイレント上書きしない）
            verify(matchRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("正常系: 楽観ロック — request.version が entity.version と一致すれば更新成功＋StandingsRecalc 発火")
        void 楽観ロック一致で更新成功() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(5L).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(matchRepository.save(any())).willReturn(match);
            TournamentMatchdayEntity md = TournamentMatchdayEntity.builder().divisionId(5L).build();
            given(matchdayRepository.findById(any())).willReturn(Optional.of(md));
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());
            given(playerStatRepository.findByMatchId(any())).willReturn(List.of());
            given(mapper.toMatchResponse(any(), any(), any())).willReturn(null);

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, null, null, null, 5L, null));

            verify(eventPublisher).publishEvent(any(StandingsRecalculationEvent.class));
        }

        @Test
        @DisplayName("後方互換: request.version=null は版チェックなし（従来挙動）で更新成功")
        void 版null後方互換() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(5L).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(matchRepository.save(any())).willReturn(match);
            TournamentMatchdayEntity md = TournamentMatchdayEntity.builder().divisionId(5L).build();
            given(matchdayRepository.findById(any())).willReturn(Optional.of(md));
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());
            given(playerStatRepository.findByMatchId(any())).willReturn(List.of());
            given(mapper.toMatchResponse(any(), any(), any())).willReturn(null);

            // 第8引数 version=null
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null));

            verify(eventPublisher).publishEvent(any(StandingsRecalculationEvent.class));
        }
    }

    @Nested
    @DisplayName("batchUpdateScores")
    class BatchUpdateScores {

        private static final Long DIVISION_ID = 5L;
        private static final Long MATCHDAY_ID = 7L;

        @Test
        @DisplayName("異常系: 楽観ロック — batch 内 1 件でも stale なら全体 409 相当の例外で中断（部分適用しない）")
        void batch楽観ロック衝突で全体中断() {
            // 1 件目は一致（version=2）、2 件目が stale（request=1 vs entity=4）
            TournamentFixtureEntity match1 = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(2L).build();
            TournamentFixtureEntity match2 = TournamentFixtureEntity.builder()
                    .homeParticipantId(3L).awayParticipantId(4L).version(4L).build();
            given(matchRepository.findById(100L)).willReturn(Optional.of(match1));
            given(matchRepository.findById(200L)).willReturn(Optional.of(match2));

            BatchScoreRequest request = new BatchScoreRequest(List.of(
                    new BatchScoreRequest.MatchScoreEntry(100L, 2, 1, null, null, null, 2L, null),
                    new BatchScoreRequest.MatchScoreEntry(200L, 0, 0, null, null, null, 1L, null)));

            assertThatThrownBy(() -> service.batchUpdateScores(TOURNAMENT_ID, DIVISION_ID, MATCHDAY_ID, request))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);

            // @Transactional により全ロールバック。順位再計算イベントは発火しない
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("正常系: batch 全件の version が一致すれば全件更新＋StandingsRecalc は 1 回だけ発火")
        void batch全件一致で更新成功() {
            TournamentFixtureEntity match1 = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(2L).build();
            TournamentFixtureEntity match2 = TournamentFixtureEntity.builder()
                    .homeParticipantId(3L).awayParticipantId(4L).version(4L).build();
            given(matchRepository.findById(100L)).willReturn(Optional.of(match1));
            given(matchRepository.findById(200L)).willReturn(Optional.of(match2));
            given(matchRepository.save(any())).willReturn(match1);

            BatchScoreRequest request = new BatchScoreRequest(List.of(
                    new BatchScoreRequest.MatchScoreEntry(100L, 2, 1, null, null, null, 2L, null),
                    new BatchScoreRequest.MatchScoreEntry(200L, 0, 3, null, null, null, 4L, null)));

            service.batchUpdateScores(TOURNAMENT_ID, DIVISION_ID, MATCHDAY_ID, request);

            verify(eventPublisher).publishEvent(any(StandingsRecalculationEvent.class));
        }
    }

    @Nested
    @DisplayName("セット制の勝敗判定（F08.7 セット制①）")
    class SetScoring {

        private static final Long HOME_ID = 1L;
        private static final Long AWAY_ID = 2L;

        /** updateScore が getMatch でレスポンス構築する際に必要な共通モックを設定する。 */
        private void stubResponseChain() {
            given(matchRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            TournamentMatchdayEntity md = TournamentMatchdayEntity.builder().divisionId(5L).build();
            given(matchdayRepository.findById(any())).willReturn(Optional.of(md));
            lenient().when(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).thenReturn(List.of());
            given(playerStatRepository.findByMatchId(any())).willReturn(List.of());
            given(mapper.toMatchResponse(any(), any(), any())).willReturn(null);
        }

        private TournamentEntity setTournament(boolean hasSets, Integer setsToWin, boolean hasDraw) {
            return TournamentEntity.builder()
                    .organizationId(1L)
                    .name("セット制大会")
                    .format(TournamentFormat.LEAGUE)
                    .hasSets(hasSets)
                    .setsToWin(setsToWin)
                    .hasDraw(hasDraw)
                    .createdBy(1L)
                    .build();
        }

        @Test
        @DisplayName("正常系: hasSets大会で home 3-1（勝セット数）→ HOME_WIN・勝者=home")
        void セット制_ホーム3勝1敗で勝利() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(setTournament(true, 3, false)));
            stubResponseChain();

            // 本戦合計点は home の方が少ない（65 vs 80）が、勝セット数は home 3 / away 1 → HOME_WIN
            List<FixtureSetRequest> sets = List.of(
                    new FixtureSetRequest(1, 25, 20),
                    new FixtureSetRequest(2, 10, 25),
                    new FixtureSetRequest(3, 25, 15),
                    new FixtureSetRequest(4, 25, 20));
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(65, 80, null, null, null, null, sets));

            assertThat(match.getResult()).isEqualTo(FixtureResult.HOME_WIN);
            assertThat(match.getWinnerParticipantId()).isEqualTo(HOME_ID);
        }

        @Test
        @DisplayName("正常系: hasSets大会で away が setsToWin(2) 到達 → AWAY_WIN")
        void セット制_setsToWin到達で勝利() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(setTournament(true, 2, false)));
            stubResponseChain();

            // away が 2 セット先取（home 1 / away 2）→ AWAY_WIN
            List<FixtureSetRequest> sets = List.of(
                    new FixtureSetRequest(1, 25, 20),
                    new FixtureSetRequest(2, 18, 25),
                    new FixtureSetRequest(3, 22, 25));
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(65, 70, null, null, null, null, sets));

            assertThat(match.getResult()).isEqualTo(FixtureResult.AWAY_WIN);
            assertThat(match.getWinnerParticipantId()).isEqualTo(AWAY_ID);
        }

        @Test
        @DisplayName("境界系: hasSets大会で勝セット数同数・hasDraw=true → DRAW")
        void セット制_勝セット数同数_hasDrawありでDRAW() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(setTournament(true, null, true)));
            stubResponseChain();

            // 1-1 の勝セット同数 → hasDraw=true なので DRAW
            List<FixtureSetRequest> sets = List.of(
                    new FixtureSetRequest(1, 25, 20),
                    new FixtureSetRequest(2, 20, 25));
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(45, 45, null, null, null, null, sets));

            assertThat(match.getResult()).isEqualTo(FixtureResult.DRAW);
            assertThat(match.getWinnerParticipantId()).isNull();
        }

        @Test
        @DisplayName("境界系: hasSets大会で勝セット数同数・hasDraw=false → 合計点で判定（home合計多→HOME_WIN）")
        void セット制_勝セット数同数_hasDrawなしで合計点判定() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(setTournament(true, null, false)));
            stubResponseChain();

            // 勝セット 1-1 同数。合計点 home 50 / away 45 → HOME_WIN（確定不能を握りつぶさない安全既定）
            List<FixtureSetRequest> sets = List.of(
                    new FixtureSetRequest(1, 25, 20),
                    new FixtureSetRequest(2, 25, 25));
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(50, 45, null, null, null, null, sets));

            assertThat(match.getResult()).isEqualTo(FixtureResult.HOME_WIN);
            assertThat(match.getWinnerParticipantId()).isEqualTo(HOME_ID);
        }

        @Test
        @DisplayName("入口①非破壊: hasSets大会でも sets=null なら従来の本戦スコア判定にフォールバック（home 2-1→HOME_WIN）")
        void セット制大会_setsNullで本戦スコアにフォールバック() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(setTournament(true, 3, false)));
            stubResponseChain();

            // sets=null。hasSets=true でもセット判定せず本戦スコア 2-1 で HOME_WIN（例外を投げない）
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null));

            assertThat(match.getResult()).isEqualTo(FixtureResult.HOME_WIN);
            assertThat(match.getWinnerParticipantId()).isEqualTo(HOME_ID);
        }

        @Test
        @DisplayName("従来ロジック温存: hasSets=false大会では本戦スコア＋PKで判定（本戦同点→PKでHOME_WIN）")
        void 非セット制_延長PKの従来ロジック温存() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(setTournament(false, null, false)));
            stubResponseChain();

            // 本戦2-2（延長得点は本戦へ合算済み・延長別列は Phase 5b-3 で廃止）→ PK 5-4 で HOME_WIN（#1473 PKロジック温存）
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 2, 5, 4, null, null, null));

            assertThat(match.getResult()).isEqualTo(FixtureResult.HOME_WIN);
            assertThat(match.getWinnerParticipantId()).isEqualTo(HOME_ID);
        }

        @Test
        @DisplayName("異常系: 負のセットスコアは INVALID_SCORE")
        void セット負値はエラー() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));

            List<FixtureSetRequest> sets = List.of(new FixtureSetRequest(1, -1, 20));
            assertThatThrownBy(() -> service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(0, 0, null, null, null, null, sets)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.INVALID_SCORE);

            verify(matchRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("match 正本化（Phase5b-2'・系統B→matches 正本・05 §H.1〜H.2.3）")
    class MatchCanonicalRecording {

        private static final Long HOME_PARTICIPANT = 1L;
        private static final Long AWAY_PARTICIPANT = 2L;
        private static final Long HOME_TEAM = 1000L;
        private static final Long AWAY_TEAM = 2000L;

        private void stubResponseChain() {
            given(matchRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            TournamentMatchdayEntity md = TournamentMatchdayEntity.builder().divisionId(5L).build();
            given(matchdayRepository.findById(any())).willReturn(Optional.of(md));
            lenient().when(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).thenReturn(List.of());
            given(playerStatRepository.findByMatchId(any())).willReturn(List.of());
            given(mapper.toMatchResponse(any(), any(), any())).willReturn(null);
        }

        @Test
        @DisplayName("updateScore: matches を正本化（org=大会org・team=home participant の team・kind=TOURNAMENT・fixtureId・スコア反映）")
        void updateScoreRecordsMatchCanonical() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_PARTICIPANT).awayParticipantId(AWAY_PARTICIPANT).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            stubCanonicalRecordingChain(HOME_PARTICIPANT, AWAY_PARTICIPANT, HOME_TEAM, AWAY_TEAM);
            stubResponseChain();

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, 5, 4, null, null, null));

            ArgumentCaptor<com.mannschaft.app.match.service.MatchService.RecordTournamentScoreCommand> captor =
                    ArgumentCaptor.forClass(
                            com.mannschaft.app.match.service.MatchService.RecordTournamentScoreCommand.class);
            verify(matchService).recordTournamentScore(captor.capture());
            var c = captor.getValue();
            assertThat(c.getOrganizationId()).isEqualTo(99L);
            // team = home participant の team_id（HOME 固定・participant 経由解決・H.1.2）
            assertThat(c.getTeamId()).isEqualTo(HOME_TEAM);
            assertThat(c.getOpponentTeamId()).isEqualTo(AWAY_TEAM);
            assertThat(c.getTournamentFixtureId()).isEqualTo(MATCH_ID);
            assertThat(c.getHomeScore()).isEqualTo(2);
            assertThat(c.getAwayScore()).isEqualTo(1);
            assertThat(c.getHomePenaltyScore()).isEqualTo(5);
            assertThat(c.getAwayPenaltyScore()).isEqualTo(4);
            // 既定（sport 未設定の大会＝Builder.Default の SOCCER）は SOCCER の canonical match を作る（従来挙動）。
            assertThat(c.getSport()).isEqualTo(com.mannschaft.app.match.domain.Sport.SOCCER);
        }

        @Test
        @DisplayName("F08.10 多競技: バレー大会の直接入力は sport=VOLLEYBALL の canonical match を作る")
        void recordsVolleyballSportFromTournament() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_PARTICIPANT).awayParticipantId(AWAY_PARTICIPANT).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            stubCanonicalRecordingChainWithSport(HOME_PARTICIPANT, AWAY_PARTICIPANT, HOME_TEAM, AWAY_TEAM,
                    "VOLLEYBALL");
            stubResponseChain();

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(3, 1, null, null, null, null, null));

            ArgumentCaptor<com.mannschaft.app.match.service.MatchService.RecordTournamentScoreCommand> captor =
                    ArgumentCaptor.forClass(
                            com.mannschaft.app.match.service.MatchService.RecordTournamentScoreCommand.class);
            verify(matchService).recordTournamentScore(captor.capture());
            // 大会 sport が canonical match に伝播する（多競技で誤った SOCCER の正本を作らない）。
            assertThat(captor.getValue().getSport())
                    .isEqualTo(com.mannschaft.app.match.domain.Sport.VOLLEYBALL);
        }

        @Test
        @DisplayName("F08.10 多競技: 将棋大会の直接入力は sport=SHOGI の canonical match を作る")
        void recordsShogiSportFromTournament() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_PARTICIPANT).awayParticipantId(AWAY_PARTICIPANT).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            stubCanonicalRecordingChainWithSport(HOME_PARTICIPANT, AWAY_PARTICIPANT, HOME_TEAM, AWAY_TEAM,
                    "SHOGI");
            stubResponseChain();

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(1, 0, null, null, null, null, null));

            ArgumentCaptor<com.mannschaft.app.match.service.MatchService.RecordTournamentScoreCommand> captor =
                    ArgumentCaptor.forClass(
                            com.mannschaft.app.match.service.MatchService.RecordTournamentScoreCommand.class);
            verify(matchService).recordTournamentScore(captor.capture());
            assertThat(captor.getValue().getSport())
                    .isEqualTo(com.mannschaft.app.match.domain.Sport.SHOGI);
        }

        @Test
        @DisplayName("二重発火回避: updateScore は MatchCompletedEvent を発火しない（StandingsRecalc は 1 回のみ・リスナー二重書込なし）")
        void updateScoreDoesNotFireMatchCompletedEvent() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_PARTICIPANT).awayParticipantId(AWAY_PARTICIPANT).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            stubCanonicalRecordingChain(HOME_PARTICIPANT, AWAY_PARTICIPANT, HOME_TEAM, AWAY_TEAM);
            stubResponseChain();

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null));

            // 系統B は StandingsRecalculationEvent を 1 回だけ発火（fixture 同期＋順位は同期経路に閉じる）。
            verify(eventPublisher, times(1)).publishEvent(any(StandingsRecalculationEvent.class));
            // MatchCompletedEvent は発火しない（AFTER_COMMIT リスナーによる fixture 二重書込/二重 recalc を避ける）。
            verify(eventPublisher, never()).publishEvent(any(com.mannschaft.app.match.MatchCompletedEvent.class));
        }

        @Test
        @DisplayName("participant 経由解決: home participant の team を team に充てる（team_id 単独逆引きしない・H.1.2）")
        void resolvesTeamViaParticipantNotReverseLookup() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_PARTICIPANT).awayParticipantId(AWAY_PARTICIPANT).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            stubCanonicalRecordingChain(HOME_PARTICIPANT, AWAY_PARTICIPANT, HOME_TEAM, AWAY_TEAM);
            stubResponseChain();

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(0, 3, null, null, null, null, null));

            // participant を findById で引いている（team_id 単独逆引きではない）
            verify(participantRepository).findById(HOME_PARTICIPANT);
            verify(participantRepository).findById(AWAY_PARTICIPANT);
        }

        @Test
        @DisplayName("BYE/未割当: home participant 無しなら matches 正本化をスキップ（fixture スナップショットは従来どおり）")
        void skipsCanonicalRecordingForBye() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(null).awayParticipantId(AWAY_PARTICIPANT).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            // tournament は引けるが home participant が無いため正本化しない
            lenient().when(tournamentRepository.findById(TOURNAMENT_ID))
                    .thenReturn(Optional.of(TournamentEntity.builder()
                            .organizationId(99L).name("t").format(TournamentFormat.LEAGUE).createdBy(1L).build()));
            stubResponseChain();

            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(3, 0, null, null, null, null, null));

            verify(matchService, never()).recordTournamentScore(any());
            // fixture スナップショットと順位再計算は従来どおり動く
            verify(eventPublisher).publishEvent(any(StandingsRecalculationEvent.class));
        }

        @Test
        @DisplayName("batchUpdateScores: 各 fixture を matches へ正本化し、StandingsRecalc は 1 回・MatchCompletedEvent は発火しない")
        void batchRecordsEachMatchCanonical() {
            TournamentFixtureEntity m1 = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_PARTICIPANT).awayParticipantId(AWAY_PARTICIPANT).build();
            TournamentFixtureEntity m2 = TournamentFixtureEntity.builder()
                    .homeParticipantId(3L).awayParticipantId(4L).build();
            given(matchRepository.findById(100L)).willReturn(Optional.of(m1));
            given(matchRepository.findById(200L)).willReturn(Optional.of(m2));
            given(matchRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            stubCanonicalRecordingChain(HOME_PARTICIPANT, AWAY_PARTICIPANT, HOME_TEAM, AWAY_TEAM);
            lenient().when(participantRepository.findById(3L))
                    .thenReturn(Optional.of(TournamentParticipantEntity.builder()
                            .id(3L).divisionId(5L).teamId(3000L).build()));
            lenient().when(participantRepository.findById(4L))
                    .thenReturn(Optional.of(TournamentParticipantEntity.builder()
                            .id(4L).divisionId(5L).teamId(4000L).build()));

            BatchScoreRequest request = new BatchScoreRequest(List.of(
                    new BatchScoreRequest.MatchScoreEntry(100L, 2, 1, null, null, null, null, null),
                    new BatchScoreRequest.MatchScoreEntry(200L, 0, 3, null, null, null, null, null)));

            service.batchUpdateScores(TOURNAMENT_ID, 5L, 7L, request);

            // 2 fixture 分の正本化が呼ばれる
            verify(matchService, times(2)).recordTournamentScore(any());
            // StandingsRecalc は batch で 1 回だけ・MatchCompletedEvent は発火しない
            verify(eventPublisher, times(1)).publishEvent(any(StandingsRecalculationEvent.class));
            verify(eventPublisher, never()).publishEvent(any(com.mannschaft.app.match.MatchCompletedEvent.class));
        }
    }

    @Nested
    @DisplayName("generateMatchdays")
    class GenerateMatchdays {

        @Test
        @DisplayName("異常系: 参加チーム2チーム未満はエラー")
        void 参加チーム不足() {
            TournamentEntity tournament = TournamentEntity.builder()
                    .organizationId(ORG_ID).format(TournamentFormat.LEAGUE).build();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            given(participantRepository.findByDivisionIdOrderBySeedAsc(5L))
                    .willReturn(List.of(TournamentParticipantEntity.builder().teamId(1L).build()));

            assertThatThrownBy(() -> service.generateMatchdays(ORG_ID, TOURNAMENT_ID, 5L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.INSUFFICIENT_PARTICIPANTS);
        }
    }

    /**
     * 認可根治戦役 Wave2 トランシェ2C: GET 系可視性ガードの番人テスト
     * （旧 FixtureControllerVisibilityTest から移設・Service 層集約に伴う追随）。
     */
    @Nested
    @DisplayName("可視性ガード（閲覧系・F00 委譲）")
    class VisibilityGuard {

        private static final Long DIV_ID = 11L;
        private static final Long VIEWER = 5L;

        @Test
        @DisplayName("listMatchdays: 不可視（canView=false）なら 404 でブロックしサービス層を進めない")
        void listMatchdays_denied() {
            given(contentVisibilityChecker.canView(
                    com.mannschaft.app.common.visibility.ReferenceType.TOURNAMENT, TOURNAMENT_ID, VIEWER))
                    .willReturn(false);

            assertThatThrownBy(() -> service.listMatchdays(TOURNAMENT_ID, DIV_ID, VIEWER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("getMatch: 不可視（canView=false）なら 404 でブロックする")
        void getMatch_denied() {
            given(contentVisibilityChecker.canView(
                    com.mannschaft.app.common.visibility.ReferenceType.TOURNAMENT, TOURNAMENT_ID, VIEWER))
                    .willReturn(false);

            assertThatThrownBy(() -> service.getMatch(TOURNAMENT_ID, MATCH_ID, VIEWER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("listRosters: 不可視（canView=false）なら 404 でブロックする")
        void listRosters_denied() {
            given(contentVisibilityChecker.canView(
                    com.mannschaft.app.common.visibility.ReferenceType.TOURNAMENT, TOURNAMENT_ID, VIEWER))
                    .willReturn(false);

            assertThatThrownBy(() -> service.listRosters(TOURNAMENT_ID, MATCH_ID, VIEWER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("未認証（viewer=null）も canView に委譲され、不可視なら 404")
        void anonymous_denied() {
            given(contentVisibilityChecker.canView(
                    com.mannschaft.app.common.visibility.ReferenceType.TOURNAMENT, TOURNAMENT_ID, null))
                    .willReturn(false);

            assertThatThrownBy(() -> service.getMatch(TOURNAMENT_ID, MATCH_ID, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("可視（canView=true）なら listMatchdays はディビジョン束縛検証を経てサービス層へ進む")
        void listMatchdays_allowed() {
            given(contentVisibilityChecker.canView(
                    com.mannschaft.app.common.visibility.ReferenceType.TOURNAMENT, TOURNAMENT_ID, VIEWER))
                    .willReturn(true);
            given(matchdayRepository.findByDivisionIdOrderByMatchdayNumberAsc(DIV_ID)).willReturn(List.of());

            assertThat(service.listMatchdays(TOURNAMENT_ID, DIV_ID, VIEWER)).isEmpty();
        }
    }
}
