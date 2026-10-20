package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 参加チームレスポンスに team ドメインのチーム名を付与する。
 *
 * <p>team ドメインの参照は {@link NameResolverService} 経由の ID 一括解決のみ（Repository を直接触らない・N+1 なし）。
 * {@code @Transactional} な {@link DivisionService} からは team ドメインの Repository へ到達させない
 * （D-3T: 越境トランザクション禁止）ため、本クラスは非トランザクションで Controller から呼ぶ。</p>
 */
@Component
@RequiredArgsConstructor
public class ParticipantTeamNameEnricher {

    private final NameResolverService nameResolverService;
    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    private final TournamentRepository tournamentRepository;

    /**
     * 参加チーム一覧のチーム名をまとめて1回で解決して付与する。
     *
     * <p>加盟判定に使う組織は、パスの orgId ではなく大会エンティティの実際の主催組織。パスの orgId が
     * 主催組織と一致しない（不在の大会と同じく）場合は {@code TOURNAMENT_NOT_FOUND}（404）にする。
     * これにより、別組織のパスに差し替えて、その組織専属のチーム名を引き出すことはできない。</p>
     *
     * <p>名前を出すのは主催組織に ACTIVE 加盟しているチームだけ（他組織の不可視チーム名を漏らさない）。
     * 加盟していないチーム（過去データの他組織チーム・離脱済み等）の {@code teamName} は null になる。</p>
     */
    public List<ParticipantResponse> enrich(Long pathOrgId, Long tournamentId, List<ParticipantResponse> participants) {
        Long orgId = tournamentRepository.findById(tournamentId)
                .map(t -> t.getOrganizationId())
                .filter(hostOrgId -> hostOrgId.equals(pathOrgId))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        Set<Long> teamIds = participants.stream()
                .map(ParticipantResponse::getTeamId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> visibleTeamIds = Set.copyOf(teamOrgMembershipQueryService.findActiveTeamIdsIn(orgId, teamIds));
        Map<Long, String> names = nameResolverService.resolveTeamNames(visibleTeamIds);
        return participants.stream()
                .map(p -> ParticipantResponse.withTeamName(p, names.get(p.getTeamId())))
                .toList();
    }

    /** 単一の参加チームにチーム名を付与する。 */
    public ParticipantResponse enrich(Long pathOrgId, Long tournamentId, ParticipantResponse participant) {
        return enrich(pathOrgId, tournamentId, Collections.singletonList(participant)).get(0);
    }
}
