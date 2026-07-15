package com.mannschaft.app.tournament;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.tournament.dto.CreateParticipantRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link DivisionService} の単体テスト。
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: 変更系メソッドは orgId 束縛検証（tournamentRepository 経由）と
 * 主催組織 ADMIN/DEPUTY_ADMIN 検証（accessControlService 経由）が先行するため、各テストで
 * 大会が {@code ORG_ID} 配下に存在するようスタブする（{@code accessControlService} はデフォルトの
 * no-op モックで許可扱い＝本テストの関心事はビジネスロジックであり認可判定自体ではない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DivisionService 単体テスト")
class DivisionServiceTest {

    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private com.mannschaft.app.tournament.repository.TournamentRepository tournamentRepository;
    @Mock private TournamentMapper mapper;
    @Mock private com.mannschaft.app.tournament.service.TournamentContactSpaceProvisioningService contactSpaceProvisioningService;
    @Mock private com.mannschaft.app.filesharing.service.SharedFolderService sharedFolderService;
    @Mock private AccessControlService accessControlService;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private DivisionService service;

    private static final Long ORG_ID = 100L;
    private static final Long TOURNAMENT_ID = 1L;
    private static final Long DIV_ID = 10L;
    private static final Long USER_ID = 999L;

    private void stubTournamentInOrg() {
        TournamentEntity tournament = TournamentEntity.builder()
                .organizationId(ORG_ID).build();
        given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
    }

    @Nested
    @DisplayName("addParticipant")
    class AddParticipant {

        @Test
        @DisplayName("異常系: 重複参加チーム登録エラー")
        void 重複参加チーム() {
            stubTournamentInOrg();
            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).build();
            given(divisionRepository.findByIdAndTournamentId(DIV_ID, TOURNAMENT_ID)).willReturn(Optional.of(div));
            given(participantRepository.findByDivisionIdAndTeamId(DIV_ID, 5L))
                    .willReturn(Optional.of(TournamentParticipantEntity.builder().build()));

            CreateParticipantRequest request = new CreateParticipantRequest(5L, null, null);

            assertThatThrownBy(() -> service.addParticipant(ORG_ID, TOURNAMENT_ID, DIV_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.DUPLICATE_PARTICIPANT);
        }

        @Test
        @DisplayName("異常系: 最大参加チーム数超過")
        void 最大参加チーム数超過() {
            stubTournamentInOrg();
            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).maxParticipants(2).build();
            given(divisionRepository.findByIdAndTournamentId(DIV_ID, TOURNAMENT_ID)).willReturn(Optional.of(div));
            given(participantRepository.findByDivisionIdAndTeamId(DIV_ID, 5L)).willReturn(Optional.empty());
            given(participantRepository.countByDivisionId(DIV_ID)).willReturn(2L);

            CreateParticipantRequest request = new CreateParticipantRequest(5L, null, null);

            assertThatThrownBy(() -> service.addParticipant(ORG_ID, TOURNAMENT_ID, DIV_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.MAX_PARTICIPANTS_EXCEEDED);
        }

        @Test
        @DisplayName("正常系: 参加チーム追加成功")
        void 参加チーム追加成功() {
            stubTournamentInOrg();
            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).maxParticipants(10).build();
            given(divisionRepository.findByIdAndTournamentId(DIV_ID, TOURNAMENT_ID)).willReturn(Optional.of(div));
            given(participantRepository.findByDivisionIdAndTeamId(DIV_ID, 5L)).willReturn(Optional.empty());
            given(participantRepository.countByDivisionId(DIV_ID)).willReturn(1L);
            TournamentParticipantEntity saved = TournamentParticipantEntity.builder().teamId(5L).build();
            given(participantRepository.save(any())).willReturn(saved);
            given(mapper.toParticipantResponse(saved)).willReturn(null);

            CreateParticipantRequest request = new CreateParticipantRequest(5L, null, null);
            service.addParticipant(ORG_ID, TOURNAMENT_ID, DIV_ID, USER_ID, request);

            verify(participantRepository).save(any());
        }
    }

    @Nested
    @DisplayName("deleteDivision")
    class DeleteDivision {

        @Test
        @DisplayName("異常系: ディビジョンが見つからない")
        void ディビジョン不存在() {
            stubTournamentInOrg();
            given(divisionRepository.findByIdAndTournamentId(DIV_ID, TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteDivision(ORG_ID, TOURNAMENT_ID, DIV_ID, USER_ID))
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
            stubTournamentInOrg();
            TournamentDivisionEntity div = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).build();
            given(divisionRepository.findByIdAndTournamentId(DIV_ID, TOURNAMENT_ID)).willReturn(Optional.of(div));
            given(participantRepository.findByIdAndDivisionId(99L, DIV_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeParticipant(ORG_ID, TOURNAMENT_ID, DIV_ID, 99L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        }
    }
}
