package com.mannschaft.app.tournament;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.tournament.dto.CreateParticipantRequest;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.service.DivisionService;
import com.mannschaft.app.tournament.service.ParticipantRegistrationFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ParticipantRegistrationFacade} の単体テスト。
 * 参加できるのは主催組織に ACTIVE 加盟しているチームのみ（F08.7 §2）。違反と不在は同じ 404 に畳む。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParticipantRegistrationFacade 単体テスト")
class ParticipantRegistrationFacadeTest {

    private static final Long ORG_ID = 100L;
    private static final Long T_ID = 1L;
    private static final Long DIV_ID = 10L;
    private static final Long USER_ID = 999L;

    @Mock private DivisionService divisionService;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    @InjectMocks
    private ParticipantRegistrationFacade facade;

    private void stubTournamentInOrg() {
        given(tournamentRepository.findById(T_ID))
                .willReturn(Optional.of(TournamentEntity.builder().organizationId(ORG_ID).build()));
    }

    @Test
    @DisplayName("主催組織に ACTIVE 加盟のチームは登録できる")
    void 加盟チームは登録できる() {
        stubTournamentInOrg();
        CreateParticipantRequest request = new CreateParticipantRequest(5L, null, null);
        ParticipantResponse expected = new ParticipantResponse(1L, DIV_ID, 5L, null, null, "REGISTERED", null, null);
        given(teamOrgMembershipQueryService.findActiveTeamIdsIn(ORG_ID, Set.of(5L))).willReturn(List.of(5L));
        given(divisionService.addParticipant(ORG_ID, T_ID, DIV_ID, USER_ID, request)).willReturn(expected);

        assertThat(facade.addParticipant(ORG_ID, T_ID, DIV_ID, USER_ID, request)).isSameAs(expected);
    }

    @Test
    @DisplayName("他組織のチーム・存在しないチームは TEAM_NOT_IN_ORGANIZATION（同一コード）で弾き、登録しない")
    void 非加盟チームは404() {
        stubTournamentInOrg();
        CreateParticipantRequest request = new CreateParticipantRequest(777L, null, null);
        given(teamOrgMembershipQueryService.findActiveTeamIdsIn(ORG_ID, Set.of(777L))).willReturn(List.of());

        assertThatThrownBy(() -> facade.addParticipant(ORG_ID, T_ID, DIV_ID, USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TournamentErrorCode.TEAM_NOT_IN_ORGANIZATION);
        verify(divisionService, never()).addParticipant(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("認可に失敗する呼び出し元には帰属検証の結果を返さない（認可が先）")
    void 認可が先() {
        CreateParticipantRequest request = new CreateParticipantRequest(777L, null, null);
        stubTournamentInOrg();
        willThrow(new IllegalStateException("forbidden"))
                .given(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> facade.addParticipant(ORG_ID, T_ID, DIV_ID, USER_ID, request))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(teamOrgMembershipQueryService);
    }
}
