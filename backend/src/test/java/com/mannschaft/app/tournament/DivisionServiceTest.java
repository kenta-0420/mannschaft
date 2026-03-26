package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.dto.CreateDivisionRequest;
import com.mannschaft.app.tournament.dto.CreateParticipantRequest;
import com.mannschaft.app.tournament.dto.DivisionResponse;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.service.DivisionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link DivisionService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DivisionService 単体テスト")
class DivisionServiceTest {

    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentMapper mapper;

    @InjectMocks
    private DivisionService service;

    private static final Long TOURNAMENT_ID = 1L;
    private static final Long DIV_ID = 10L;

    @Nested
    @DisplayName("addParticipant")
    class AddParticipant {

        @Test
        @DisplayName("異常系: 重複参加チーム登録エラー")
        void 重複参加チーム() {
            given(participantRepository.findByDivisionIdAndTeamId(DIV_ID, 5L))
                    .willReturn(Optional.of(TournamentParticipantEntity.builder().build()));

            CreateParticipantRequest request = new CreateParticipantRequest(5L, null, null);

            assertThatThrownBy(() -> service.addParticipant(DIV_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.DUPLICATE_PARTICIPANT);
        }

        @Test
        @DisplayName("異常系: 最大参加チーム数超過")
        void 最大参加チーム数超過() {
            given(participantRepository.findByDivisionIdAndTeamId(DIV_ID, 5L)).willReturn(Optional.empty());
            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).maxParticipants(2).build();
            given(divisionRepository.findById(DIV_ID)).willReturn(Optional.of(div));
            given(participantRepository.countByDivisionId(DIV_ID)).willReturn(2L);

            CreateParticipantRequest request = new CreateParticipantRequest(5L, null, null);

            assertThatThrownBy(() -> service.addParticipant(DIV_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.MAX_PARTICIPANTS_EXCEEDED);
        }

        @Test
        @DisplayName("正常系: 参加チーム追加成功")
        void 参加チーム追加成功() {
            given(participantRepository.findByDivisionIdAndTeamId(DIV_ID, 5L)).willReturn(Optional.empty());
            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).maxParticipants(10).build();
            given(divisionRepository.findById(DIV_ID)).willReturn(Optional.of(div));
            given(participantRepository.countByDivisionId(DIV_ID)).willReturn(1L);
            TournamentParticipantEntity saved = TournamentParticipantEntity.builder().teamId(5L).build();
            given(participantRepository.save(any())).willReturn(saved);
            given(mapper.toParticipantResponse(saved)).willReturn(null);

            CreateParticipantRequest request = new CreateParticipantRequest(5L, null, null);
            service.addParticipant(DIV_ID, request);

            verify(participantRepository).save(any());
        }
    }

    @Nested
    @DisplayName("deleteDivision")
    class DeleteDivision {

        @Test
        @DisplayName("異常系: ディビジョンが見つからない")
        void ディビジョン不存在() {
            given(divisionRepository.findByIdAndTournamentId(DIV_ID, TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteDivision(TOURNAMENT_ID, DIV_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.DIVISION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("removeParticipant")
    class RemoveParticipant {

        @Test
        @DisplayName("異常系: 参加チームが見つからない")
        void 参加チーム不存在() {
            given(participantRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeParticipant(99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        }
    }
}
