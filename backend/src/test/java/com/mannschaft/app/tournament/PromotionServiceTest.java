package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.dto.CreatePromotionRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.PromotionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link PromotionService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromotionService 単体テスト")
class PromotionServiceTest {

    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentStandingRepository standingRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentMatchRepository matchRepository;
    @Mock private TournamentPromotionRecordRepository promotionRecordRepository;
    @Mock private TournamentMapper mapper;

    @InjectMocks
    private PromotionService service;

    private static final Long TOURNAMENT_ID = 1L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("executePromotions")
    class ExecutePromotions {

        @Test
        @DisplayName("異常系: 未完了試合が存在する場合エラー")
        void 未完了試合存在() {
            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).build();
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(div));
            given(matchRepository.countIncompleteByDivisionId(any())).willReturn(3L);

            CreatePromotionRequest request = new CreatePromotionRequest(List.of());

            assertThatThrownBy(() -> service.executePromotions(TOURNAMENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.MATCHES_NOT_COMPLETED);
        }
    }

    private Long any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
