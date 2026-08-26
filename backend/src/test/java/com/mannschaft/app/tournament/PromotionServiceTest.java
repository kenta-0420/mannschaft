package com.mannschaft.app.tournament;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.tournament.dto.CreatePromotionRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.PromotionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link PromotionService} の単体テスト。
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: 変更系メソッドは orgId 束縛検証（tournamentRepository 経由）と
 * 主催組織 ADMIN/DEPUTY_ADMIN 検証（accessControlService 経由）が先行するため、大会が
 * {@code ORG_ID} 配下に存在するようスタブする。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PromotionService 単体テスト")
class PromotionServiceTest {

    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentStandingRepository standingRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentFixtureRepository matchRepository;
    @Mock private TournamentPromotionRecordRepository promotionRecordRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentMapper mapper;
    @Mock private AccessControlService accessControlService;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private PromotionService service;

    private static final Long ORG_ID = 100L;
    private static final Long TOURNAMENT_ID = 1L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("executePromotions")
    class ExecutePromotions {

        @Test
        @DisplayName("異常系: 未完了試合が存在する場合エラー")
        void 未完了試合存在() {
            TournamentEntity tournament = TournamentEntity.builder().organizationId(ORG_ID).build();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).build();
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(div));
            given(matchRepository.countIncompleteByDivisionId(any())).willReturn(3L);

            CreatePromotionRequest request = new CreatePromotionRequest(List.of());

            assertThatThrownBy(() -> service.executePromotions(ORG_ID, TOURNAMENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.MATCHES_NOT_COMPLETED);
        }
    }

    private Long any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
