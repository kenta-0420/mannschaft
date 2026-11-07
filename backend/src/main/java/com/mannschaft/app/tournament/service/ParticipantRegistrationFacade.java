package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.CreateParticipantRequest;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 参加チーム登録の入口。F08.7 §2「大会は組織が主催し、組織傘下のチームが参加する」に従い、
 * 登録できるのは主催組織に ACTIVE で加盟しているチームだけとする。
 *
 * <p>順序は「主催組織 ADMIN の認可 → teamId の帰属検証 → 登録」。帰属違反と存在しない teamId は
 * 同じ {@code TEAM_NOT_IN_ORGANIZATION}（404）に畳み、チームの存在オラクルにしない。
 * team ドメインの参照を {@code @Transactional} な {@link DivisionService} から切り離すため、
 * 本クラスは非トランザクション（D-3T）。</p>
 */
@Service
@RequiredArgsConstructor
public class ParticipantRegistrationFacade {

    private final DivisionService divisionService;
    private final TournamentRepository tournamentRepository;
    private final AccessControlService accessControlService;
    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    public ParticipantResponse addParticipant(Long orgId, Long tournamentId, Long divisionId, Long userId,
                                               CreateParticipantRequest request) {
        // 大会が path の orgId 配下であること（不在・他組織は同じ 404）と主催組織 ADMIN 権限を先に検証する
        tournamentRepository.findById(tournamentId)
                .filter(t -> orgId.equals(t.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        Long teamId = request.getTeamId();
        if (teamId == null || !teamOrgMembershipQueryService.findActiveTeamIdsIn(orgId, Set.of(teamId)).contains(teamId)) {
            throw new BusinessException(TournamentErrorCode.TEAM_NOT_IN_ORGANIZATION);
        }
        return divisionService.addParticipant(orgId, tournamentId, divisionId, userId, request);
    }
}
