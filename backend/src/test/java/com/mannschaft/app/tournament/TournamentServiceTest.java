package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.dto.CreateTournamentRequest;
import com.mannschaft.app.tournament.dto.TournamentResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.TournamentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentService 単体テスト")
class TournamentServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentTiebreakerRepository tiebreakerRepository;
    @Mock private TournamentStatDefRepository statDefRepository;
    @Mock private TournamentTemplateRepository templateRepository;
    @Mock private TournamentTemplateTiebreakerRepository templateTiebreakerRepository;
    @Mock private TournamentTemplateStatDefRepository templateStatDefRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentMapper mapper;

    @InjectMocks
    private TournamentService service;

    private static final Long ORG_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long TOURNAMENT_ID = 100L;

    @Nested
    @DisplayName("getTournament")
    class GetTournament {

        @Test
        @DisplayName("異常系: 大会が見つからない場合エラー")
        void 大会不存在() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTournament(TOURNAMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteTournament")
    class DeleteTournament {

        @Test
        @DisplayName("正常系: 大会の論理削除が成功する")
        void 論理削除成功() {
            TournamentEntity entity = TournamentEntity.builder().organizationId(ORG_ID).name("テスト大会").build();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));
            given(tournamentRepository.save(any())).willReturn(entity);

            service.deleteTournament(TOURNAMENT_ID);

            verify(tournamentRepository).save(any());
        }
    }

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("正常系: OPEN→IN_PROGRESS で参加チームがACTIVEになる")
        void ステータス変更でACTIVE化() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("テスト大会").build();
            setStatus(entity, TournamentStatus.OPEN);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));

            TournamentDivisionEntity div = TournamentDivisionEntity.builder().tournamentId(TOURNAMENT_ID).build();
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(div));

            TournamentParticipantEntity participant = TournamentParticipantEntity.builder()
                    .teamId(1L).build();
            given(participantRepository.findByDivisionIdAndStatus(any(), any()))
                    .willReturn(List.of(participant));
            given(participantRepository.saveAll(any())).willReturn(List.of(participant));
            given(tournamentRepository.save(any())).willReturn(entity);
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(statDefRepository.findByTournamentIdOrderBySortOrderAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(mapper.toTournamentResponse(any(), any(), any())).willReturn(null);

            service.changeStatus(TOURNAMENT_ID, TournamentStatus.IN_PROGRESS);

            verify(participantRepository).saveAll(any());
        }
    }

    @Nested
    @DisplayName("continueTournament")
    class ContinueTournament {

        @Test
        @DisplayName("異常系: COMPLETED/ARCHIVED以外の大会は継続不可")
        void 継続不可ステータス() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("テスト").build();
            // Default status is DRAFT
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.continueTournament(ORG_ID, USER_ID, TOURNAMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.INVALID_TOURNAMENT_STATUS);
        }
    }

    @Nested
    @DisplayName("verifyPublicAccess")
    class VerifyPublicAccess {

        @Test
        @DisplayName("異常系: 公開アクセス検証失敗（組織不一致）")
        void 組織不一致() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(999L).name("テスト").build();
            setVisibility(entity, TournamentVisibility.PUBLIC);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.verifyPublicAccess(ORG_ID, TOURNAMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    private void setStatus(TournamentEntity entity, TournamentStatus status) {
        try {
            var field = TournamentEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(entity, status);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void setVisibility(TournamentEntity entity, TournamentVisibility visibility) {
        try {
            var field = TournamentEntity.class.getDeclaredField("visibility");
            field.setAccessible(true);
            field.set(entity, visibility);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
