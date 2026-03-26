package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
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
