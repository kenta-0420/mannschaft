package com.mannschaft.app.tournament;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.service.ParticipantTeamNameEnricher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ParticipantTeamNameEnricher} の単体テスト（CMP-260929-0654: 参加チーム表のチーム名が空欄だった欠陥）。
 * チーム名は主催組織に ACTIVE 加盟しているチームだけに付与する（他組織の不可視チーム名を漏らさない）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParticipantTeamNameEnricher 単体テスト")
class ParticipantTeamNameEnricherTest {

    private static final Long ORG_ID = 100L;

    @Mock private NameResolverService nameResolverService;
    @Mock private TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    @Mock private TournamentRepository tournamentRepository;

    private static final Long T_ID = 1L;
    private static final Long OTHER_ORG_ID = 200L;

    @org.junit.jupiter.api.BeforeEach
    void stubTournament() {
        // 大会 T_ID の実際の主催組織は ORG_ID
        org.mockito.Mockito.lenient().when(tournamentRepository.findById(T_ID))
                .thenReturn(Optional.of(TournamentEntity.builder().organizationId(ORG_ID).build()));
    }

    @InjectMocks
    private ParticipantTeamNameEnricher enricher;

    private static ParticipantResponse response(long id, long teamId) {
        return new ParticipantResponse(id, 10L, teamId, null, null, "ACTIVE", null, null);
    }

    @Test
    @DisplayName("一覧: teamName が入り、チーム名は全チームぶんを1回の呼び出しで解決する（N+1 なし）")
    void 一覧にチーム名() {
        given(teamOrgMembershipQueryService.findActiveTeamIdsIn(ORG_ID, Set.of(5L, 6L))).willReturn(List.of(5L, 6L));
        given(nameResolverService.resolveTeamNames(Set.of(5L, 6L)))
                .willReturn(Map.of(5L, "レッドFC", 6L, "ブルーFC"));

        List<ParticipantResponse> result = enricher.enrich(ORG_ID, T_ID, List.of(response(1, 5), response(2, 6)));

        assertThat(result).extracting(ParticipantResponse::getTeamName).containsExactly("レッドFC", "ブルーFC");
        assertThat(result).extracting(ParticipantResponse::getTeamId).containsExactly(5L, 6L);
        verify(nameResolverService, times(1)).resolveTeamNames(Set.of(5L, 6L));
    }

    @Test
    @DisplayName("単一: teamName が入り、他の項目は保持される")
    void 単一にチーム名() {
        given(teamOrgMembershipQueryService.findActiveTeamIdsIn(ORG_ID, Set.of(5L))).willReturn(List.of(5L));
        given(nameResolverService.resolveTeamNames(Set.of(5L))).willReturn(Map.of(5L, "レッドFC"));

        ParticipantResponse result = enricher.enrich(ORG_ID, T_ID, response(1, 5));

        assertThat(result.getTeamName()).isEqualTo("レッドFC");
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getDivisionId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("主催組織に加盟していないチーム（過去データの他組織チーム等）は名前を引かず teamName が null")
    void 非加盟チームの名前は出さない() {
        given(teamOrgMembershipQueryService.findActiveTeamIdsIn(ORG_ID, Set.of(5L, 9L))).willReturn(List.of(5L));
        given(nameResolverService.resolveTeamNames(Set.of(5L))).willReturn(Map.of(5L, "レッドFC"));

        List<ParticipantResponse> result = enricher.enrich(ORG_ID, T_ID, List.of(response(1, 5), response(2, 9)));

        assertThat(result).extracting(ParticipantResponse::getTeamName).containsExactly("レッドFC", null);
    }

    @Test
    @DisplayName("パスの orgId が大会の主催組織と違えば 404（別組織専属のチーム名を引き出せない）。加盟判定にもパス orgId を使わない")
    void パス差し替えは404() {
        assertThatThrownBy(() -> enricher.enrich(OTHER_ORG_ID, T_ID, List.of(response(1, 5))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        verify(teamOrgMembershipQueryService, never()).findActiveTeamIdsIn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(nameResolverService, never()).resolveTeamNames(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("大会が存在しなければ 404（不一致と同じコード）")
    void 大会不在は404() {
        assertThatThrownBy(() -> enricher.enrich(ORG_ID, 999L, List.of(response(1, 5))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
    }
}
