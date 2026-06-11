package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.dto.BatchScoreRequest;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.MatchService;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MatchService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchService 単体テスト")
class MatchServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentMatchdayRepository matchdayRepository;
    @Mock private TournamentMatchRepository matchRepository;
    @Mock private TournamentMatchSetRepository matchSetRepository;
    @Mock private TournamentMatchRosterRepository rosterRepository;
    @Mock private TournamentMatchPlayerStatRepository playerStatRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentStatDefRepository statDefRepository;
    @Mock private TournamentMapper mapper;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MatchService service;

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
            TournamentMatchEntity match = TournamentMatchEntity.builder()
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
            TournamentMatchEntity match = TournamentMatchEntity.builder()
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
            TournamentMatchEntity match = TournamentMatchEntity.builder()
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
            TournamentMatchEntity match = TournamentMatchEntity.builder()
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
            TournamentMatchEntity match = TournamentMatchEntity.builder()
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
            TournamentMatchEntity match1 = TournamentMatchEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(2L).build();
            TournamentMatchEntity match2 = TournamentMatchEntity.builder()
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
            TournamentMatchEntity match1 = TournamentMatchEntity.builder()
                    .homeParticipantId(1L).awayParticipantId(2L).version(2L).build();
            TournamentMatchEntity match2 = TournamentMatchEntity.builder()
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
