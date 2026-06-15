package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.dto.BatchScoreRequest;
import com.mannschaft.app.tournament.dto.FixtureSetRequest;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.FixtureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link FixtureService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FixtureService 単体テスト")
class FixtureServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentMatchdayRepository matchdayRepository;
    @Mock private TournamentFixtureRepository matchRepository;
    @Mock private TournamentFixtureSetRepository matchSetRepository;
    @Mock private TournamentFixtureRosterRepository rosterRepository;
    @Mock private TournamentFixturePlayerStatRepository playerStatRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentStatDefRepository statDefRepository;
    @Mock private TournamentMapper mapper;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private FixtureService service;

    private static final Long TOURNAMENT_ID = 1L;
    private static final Long MATCH_ID = 10L;

    @Nested
    @DisplayName("updateScore")
    class UpdateScore {

        @Test
        @DisplayName("異常系: 試合が見つからない場合エラー")
        void 試合不存在() {
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(1, 0, null, null, null, null, null, null, null)))
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
                    new ScoreUpdateRequest(-1, 0, null, null, null, null, null, null, null)))
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
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null, null, null));

            verify(eventPublisher).publishEvent(any(StandingsRecalculationEvent.class));
        }

        @Test
        @DisplayName("異常系: 楽観ロック — stale client（request.version=3 vs entity.version=5）は 409 相当の例外で弾かれ更新されない")
        void 楽観ロック衝突() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(5L).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));

            assertThatThrownBy(() -> service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null, 3L, null)))
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
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null, 5L, null));

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
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null, null, null));

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
                    new BatchScoreRequest.MatchScoreEntry(100L, 2, 1, null, null, null, null, null, 2L, null),
                    new BatchScoreRequest.MatchScoreEntry(200L, 0, 0, null, null, null, null, null, 1L, null)));

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
                    new BatchScoreRequest.MatchScoreEntry(100L, 2, 1, null, null, null, null, null, 2L, null),
                    new BatchScoreRequest.MatchScoreEntry(200L, 0, 3, null, null, null, null, null, 4L, null)));

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
                    new ScoreUpdateRequest(65, 80, null, null, null, null, null, null, sets));

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
                    new ScoreUpdateRequest(65, 70, null, null, null, null, null, null, sets));

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
                    new ScoreUpdateRequest(45, 45, null, null, null, null, null, null, sets));

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
                    new ScoreUpdateRequest(50, 45, null, null, null, null, null, null, sets));

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
                    new ScoreUpdateRequest(2, 1, null, null, null, null, null, null, null));

            assertThat(match.getResult()).isEqualTo(FixtureResult.HOME_WIN);
            assertThat(match.getWinnerParticipantId()).isEqualTo(HOME_ID);
        }

        @Test
        @DisplayName("従来ロジック温存: hasSets=false大会では本戦＋延長合算＋PKで判定（延長同点→PKでHOME_WIN）")
        void 非セット制_延長PKの従来ロジック温存() {
            TournamentFixtureEntity match = TournamentFixtureEntity.builder()
                    .homeParticipantId(HOME_ID).awayParticipantId(AWAY_ID).build();
            given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(match));
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(setTournament(false, null, false)));
            stubResponseChain();

            // 本戦1-1＋延長1-1（合算2-2）→ PK 5-4 で HOME_WIN（#1473 延長PKロジック温存）
            service.updateScore(TOURNAMENT_ID, MATCH_ID,
                    new ScoreUpdateRequest(1, 1, 1, 1, 5, 4, null, null, null));

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
                    new ScoreUpdateRequest(0, 0, null, null, null, null, null, null, sets)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.INVALID_SCORE);

            verify(matchRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("generateMatchdays")
    class GenerateMatchdays {

        @Test
        @DisplayName("異常系: 参加チーム2チーム未満はエラー")
        void 参加チーム不足() {
            TournamentEntity tournament = TournamentEntity.builder()
                    .organizationId(1L).format(TournamentFormat.LEAGUE).build();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            given(participantRepository.findByDivisionIdOrderBySeedAsc(5L))
                    .willReturn(List.of(TournamentParticipantEntity.builder().teamId(1L).build()));

            assertThatThrownBy(() -> service.generateMatchdays(TOURNAMENT_ID, 5L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.INSUFFICIENT_PARTICIPANTS);
        }
    }
}
