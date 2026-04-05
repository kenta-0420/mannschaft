package com.mannschaft.app.tournament;

import com.mannschaft.app.tournament.entity.TournamentMatchPlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import com.mannschaft.app.tournament.repository.TournamentIndividualRankingRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchPlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link RankingsCalculationService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RankingsCalculationService 単体テスト")
class RankingsCalculationServiceTest {

    @Mock private TournamentStatDefRepository statDefRepository;
    @Mock private TournamentMatchPlayerStatRepository playerStatRepository;
    @Mock private TournamentIndividualRankingRepository rankingRepository;
    @Mock private TournamentMapper mapper;

    @InjectMocks
    private RankingsCalculationService service;

    private static final Long TOURNAMENT_ID = 1L;

    @Nested
    @DisplayName("recalculateAll")
    class RecalculateAll {

        @Test
        @DisplayName("正常系: ランキング対象の成績項目がない場合は何もしない")
        void ランキング対象なし() {
            given(statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of());

            service.recalculateAll(TOURNAMENT_ID);

            // No rankingRepository interactions expected
        }

        @Test
        @DisplayName("正常系: INTEGER型の集計が正しく実行される")
        void INTEGER型集計() {
            TournamentStatDefEntity def = TournamentStatDefEntity.builder()
                    .tournamentId(TOURNAMENT_ID)
                    .statKey("goals")
                    .dataType(StatDataType.INTEGER)
                    .aggregationType(StatAggregationType.SUM)
                    .isRankingTarget(true)
                    .build();
            given(statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(def));

            TournamentMatchPlayerStatEntity stat = TournamentMatchPlayerStatEntity.builder()
                    .userId(100L).participantId(1L).statKey("goals").valueInt(3).build();
            given(playerStatRepository.findByTournamentIdAndStatKey(TOURNAMENT_ID, "goals"))
                    .willReturn(List.of(stat));

            service.recalculateAll(TOURNAMENT_ID);

            verify(rankingRepository).deleteByTournamentIdAndStatKey(TOURNAMENT_ID, "goals");
            verify(rankingRepository).save(any());
        }
    }
}
