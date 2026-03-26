package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.MatchResult;
import com.mannschaft.app.tournament.MatchStatus;
import com.mannschaft.app.tournament.PromotionZone;
import com.mannschaft.app.tournament.StandingsRecalculationEvent;
import com.mannschaft.app.tournament.TiebreakerCriteria;
import com.mannschaft.app.tournament.TiebreakerDirection;
import com.mannschaft.app.tournament.TournamentFormat;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchSetEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.entity.TournamentTiebreakerEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchSetRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import com.mannschaft.app.tournament.repository.TournamentTiebreakerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link StandingsCalculationService} の単体テスト。
 * 順位表の自動計算・タイブレーク・プロモーションゾーン判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandingsCalculationService 単体テスト")
class StandingsCalculationServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;

    @Mock
    private TournamentDivisionRepository divisionRepository;

    @Mock
    private TournamentParticipantRepository participantRepository;

    @Mock
    private TournamentMatchRepository matchRepository;

    @Mock
    private TournamentMatchSetRepository matchSetRepository;

    @Mock
    private TournamentStandingRepository standingRepository;

    @Mock
    private TournamentTiebreakerRepository tiebreakerRepository;

    @InjectMocks
    private StandingsCalculationService standingsCalculationService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TOURNAMENT_ID = 1L;
    private static final Long DIVISION_ID = 10L;
    private static final Long PARTICIPANT_A = 100L;
    private static final Long PARTICIPANT_B = 200L;
    private static final Long PARTICIPANT_C = 300L;

    private TournamentEntity createTournament() {
        return TournamentEntity.builder()
                .organizationId(1L)
                .name("テストリーグ")
                .format(TournamentFormat.LEAGUE)
                .winPoints(3)
                .drawPoints(1)
                .lossPoints(0)
                .createdBy(1L)
                .build();
    }

    private TournamentDivisionEntity createDivision(int promotionSlots, int relegationSlots,
                                                      int playoffSlots) {
        return TournamentDivisionEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .name("1部リーグ")
                .promotionSlots(promotionSlots)
                .relegationSlots(relegationSlots)
                .playoffPromotionSlots(playoffSlots)
                .build();
    }

    private TournamentParticipantEntity createParticipant(Long id) {
        return TournamentParticipantEntity.builder()
                .id(id)
                .divisionId(DIVISION_ID)
                .teamId(id)
                .seed(id.intValue())
                .build();
    }

    private TournamentMatchEntity createMatch(Long homeId, Long awayId, int homeScore, int awayScore,
                                               MatchResult result) {
        TournamentMatchEntity match = TournamentMatchEntity.builder()
                .matchdayId(1L)
                .homeParticipantId(homeId)
                .awayParticipantId(awayId)
                .homeScore(homeScore)
                .awayScore(awayScore)
                .result(result)
                .status(MatchStatus.COMPLETED)
                .build();
        ReflectionTestUtils.setField(match, "createdAt", LocalDateTime.now());
        return match;
    }

    private void setupBasicMocks(TournamentEntity tournament, TournamentDivisionEntity division,
                                  List<TournamentParticipantEntity> participants,
                                  List<TournamentMatchEntity> matches) {
        given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
        given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));
        given(participantRepository.findByDivisionIdOrderBySeedAsc(DIVISION_ID)).willReturn(participants);
        given(matchRepository.findByDivisionIdAndStatus(DIVISION_ID, MatchStatus.COMPLETED)).willReturn(matches);
        given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID)).willReturn(List.of());
        given(standingRepository.findByDivisionIdAndParticipantId(eq(DIVISION_ID), anyLong()))
                .willReturn(Optional.empty());
        given(standingRepository.save(any(TournamentStandingEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    // ========================================
    // recalculate
    // ========================================

    @Nested
    @DisplayName("recalculate")
    class Recalculate {

        @Test
        @DisplayName("正常系: ホーム勝利で勝点3が加算される")
        void recalculate_ホーム勝利_勝点3加算() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B)
            );
            List<TournamentMatchEntity> matches = List.of(
                    createMatch(PARTICIPANT_A, PARTICIPANT_B, 2, 1, MatchResult.HOME_WIN)
            );

            setupBasicMocks(tournament, division, participants, matches);
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(2)).save(any(TournamentStandingEntity.class));
        }

        @Test
        @DisplayName("正常系: 引き分けで両チームに勝点1が加算される")
        void recalculate_引き分け_勝点1ずつ() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B)
            );
            List<TournamentMatchEntity> matches = List.of(
                    createMatch(PARTICIPANT_A, PARTICIPANT_B, 1, 1, MatchResult.DRAW)
            );

            setupBasicMocks(tournament, division, participants, matches);
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(2)).save(any(TournamentStandingEntity.class));
        }

        @Test
        @DisplayName("正常系: 没収勝ちが正しく処理される")
        void recalculate_没収勝ち_正しく処理() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B)
            );
            List<TournamentMatchEntity> matches = List.of(
                    createMatch(PARTICIPANT_A, PARTICIPANT_B, 3, 0, MatchResult.FORFEIT_HOME_WIN)
            );

            setupBasicMocks(tournament, division, participants, matches);
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(2)).save(any(TournamentStandingEntity.class));
        }

        @Test
        @DisplayName("正常系: トーナメント不在で早期リターン")
        void recalculate_トーナメント不在_早期リターン() {
            // Given
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: ディビジョン不在で早期リターン")
        void recalculate_ディビジョン不在_早期リターン() {
            // Given
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(createTournament()));
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.empty());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 試合0件でも全参加者の順位表が作成される")
        void recalculate_試合0件_順位表作成() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B)
            );

            setupBasicMocks(tournament, division, participants, List.of());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(2)).save(any(TournamentStandingEntity.class));
        }

        @Test
        @DisplayName("正常系: homeParticipantIdがnullの試合はスキップされる")
        void recalculate_参加者null_スキップ() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A)
            );
            TournamentMatchEntity matchWithNull = TournamentMatchEntity.builder()
                    .matchdayId(1L)
                    .homeParticipantId(null)
                    .awayParticipantId(PARTICIPANT_A)
                    .homeScore(0)
                    .awayScore(0)
                    .result(MatchResult.BYE)
                    .status(MatchStatus.COMPLETED)
                    .build();
            ReflectionTestUtils.setField(matchWithNull, "createdAt", LocalDateTime.now());

            setupBasicMocks(tournament, division, participants, List.of(matchWithNull));

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(1)).save(any(TournamentStandingEntity.class));
        }

        @Test
        @DisplayName("正常系: セット別スコアが集計される")
        void recalculate_セット別スコア_集計される() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B)
            );
            TournamentMatchEntity match = createMatch(PARTICIPANT_A, PARTICIPANT_B, 3, 1, MatchResult.HOME_WIN);
            List<TournamentMatchSetEntity> sets = List.of(
                    TournamentMatchSetEntity.builder().matchId(1L).setNumber(1).homeScore(25).awayScore(20).build(),
                    TournamentMatchSetEntity.builder().matchId(1L).setNumber(2).homeScore(20).awayScore(25).build(),
                    TournamentMatchSetEntity.builder().matchId(1L).setNumber(3).homeScore(25).awayScore(22).build()
            );

            setupBasicMocks(tournament, division, participants, List.of(match));
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(sets);

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(2)).save(any(TournamentStandingEntity.class));
        }
    }

    // ========================================
    // プロモーションゾーン判定
    // ========================================

    @Nested
    @DisplayName("プロモーションゾーン判定")
    class PromotionZoneDetermination {

        @Test
        @DisplayName("正常系: 昇格枠内のチームはPROMOTED")
        void recalculate_昇格枠内_PROMOTED() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(1, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B)
            );
            List<TournamentMatchEntity> matches = List.of(
                    createMatch(PARTICIPANT_A, PARTICIPANT_B, 3, 0, MatchResult.HOME_WIN)
            );

            setupBasicMocks(tournament, division, participants, matches);
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(2)).save(argThat(standing ->
                    standing.getPromotionZone() != null));
        }

        @Test
        @DisplayName("正常系: 降格枠のチームはRELEGATED")
        void recalculate_降格枠_RELEGATED() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 1, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B),
                    createParticipant(PARTICIPANT_C)
            );
            List<TournamentMatchEntity> matches = List.of(
                    createMatch(PARTICIPANT_A, PARTICIPANT_B, 3, 0, MatchResult.HOME_WIN),
                    createMatch(PARTICIPANT_A, PARTICIPANT_C, 2, 0, MatchResult.HOME_WIN),
                    createMatch(PARTICIPANT_B, PARTICIPANT_C, 1, 0, MatchResult.HOME_WIN)
            );

            setupBasicMocks(tournament, division, participants, matches);
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(3)).save(any(TournamentStandingEntity.class));
        }
    }

    // ========================================
    // onStandingsRecalculation (イベントリスナー)
    // ========================================

    @Nested
    @DisplayName("onStandingsRecalculation")
    class OnStandingsRecalculation {

        @Test
        @DisplayName("正常系: イベント受信で再計算が呼ばれる")
        void onStandingsRecalculation_正常_再計算呼ばれる() {
            // Given
            StandingsRecalculationEvent event = new StandingsRecalculationEvent(
                    this, DIVISION_ID, TOURNAMENT_ID);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            // When
            standingsCalculationService.onStandingsRecalculation(event);

            // Then
            verify(tournamentRepository).findById(TOURNAMENT_ID);
        }
    }

    // ========================================
    // タイブレーク
    // ========================================

    @Nested
    @DisplayName("タイブレーク")
    class Tiebreaker {

        @Test
        @DisplayName("正常系: タイブレークルール指定時にソートが適用される")
        void recalculate_タイブレーク_ソート適用() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(
                    createParticipant(PARTICIPANT_A),
                    createParticipant(PARTICIPANT_B)
            );
            List<TournamentMatchEntity> matches = List.of(
                    createMatch(PARTICIPANT_A, PARTICIPANT_B, 2, 1, MatchResult.HOME_WIN)
            );

            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));
            given(participantRepository.findByDivisionIdOrderBySeedAsc(DIVISION_ID)).willReturn(participants);
            given(matchRepository.findByDivisionIdAndStatus(DIVISION_ID, MatchStatus.COMPLETED)).willReturn(matches);
            given(matchSetRepository.findByMatchIdOrderBySetNumberAsc(any())).willReturn(List.of());

            List<TournamentTiebreakerEntity> tiebreakers = List.of(
                    TournamentTiebreakerEntity.builder()
                            .tournamentId(TOURNAMENT_ID)
                            .priority(1)
                            .criteria(TiebreakerCriteria.POINTS)
                            .direction(TiebreakerDirection.DESC)
                            .build(),
                    TournamentTiebreakerEntity.builder()
                            .tournamentId(TOURNAMENT_ID)
                            .priority(2)
                            .criteria(TiebreakerCriteria.SCORE_DIFFERENCE)
                            .direction(TiebreakerDirection.DESC)
                            .build()
            );
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID))
                    .willReturn(tiebreakers);
            given(standingRepository.findByDivisionIdAndParticipantId(eq(DIVISION_ID), anyLong()))
                    .willReturn(Optional.empty());
            given(standingRepository.save(any(TournamentStandingEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository, times(2)).save(any(TournamentStandingEntity.class));
        }

        @Test
        @DisplayName("正常系: 既存の順位表エンティティが更新される")
        void recalculate_既存Standing_更新される() {
            // Given
            TournamentEntity tournament = createTournament();
            TournamentDivisionEntity division = createDivision(0, 0, 0);
            List<TournamentParticipantEntity> participants = List.of(createParticipant(PARTICIPANT_A));

            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));
            given(participantRepository.findByDivisionIdOrderBySeedAsc(DIVISION_ID)).willReturn(participants);
            given(matchRepository.findByDivisionIdAndStatus(DIVISION_ID, MatchStatus.COMPLETED))
                    .willReturn(List.of());
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID))
                    .willReturn(List.of());

            TournamentStandingEntity existingStanding = TournamentStandingEntity.builder()
                    .divisionId(DIVISION_ID)
                    .participantId(PARTICIPANT_A)
                    .rank(1)
                    .build();
            given(standingRepository.findByDivisionIdAndParticipantId(DIVISION_ID, PARTICIPANT_A))
                    .willReturn(Optional.of(existingStanding));
            given(standingRepository.save(any(TournamentStandingEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            standingsCalculationService.recalculate(DIVISION_ID, TOURNAMENT_ID);

            // Then
            verify(standingRepository).save(existingStanding);
        }
    }
}
