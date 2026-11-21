package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.ParticipantContactDivisionRow;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse.DivisionItem;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse.SpaceItem;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 参加者向け連絡スペース一覧のファサード（CMP-261118-0900 §10.3.4）。
 *
 * <p>意図して {@code @Transactional} を持たない（判定は tx の外で行い、拒否は同一の TOUR_029 を 1 箇所で投げる）。
 * 各段は戻り値（{@code Optional} / 空）で分岐し、例外の catch で分岐しない。</p>
 *
 * <p>クエリ本数は部門数・参加チーム数・スペース数に依存しない定数（AC-P20）: slug 解決 1 本、参加判定 1 本、
 * スペース取得 1 本、Gate の一括解決（定数本）。</p>
 */
@Component
@RequiredArgsConstructor
public class ParticipantContactSpaceFacade {

    /** 並び（X2）: 大会全体 → 部門 ID 昇順、各スコープ内は BULLETIN → CHAT。 */
    private static final Comparator<TournamentContactSpaceEntity> SPACE_ORDER = Comparator
            .comparing((TournamentContactSpaceEntity s) -> s.getScopeType() == ContactSpaceScopeType.TOURNAMENT ? 0 : 1)
            .thenComparing(TournamentContactSpaceEntity::getScopeId)
            .thenComparing(s -> s.getSpaceKind() == ContactSpaceKind.BULLETIN ? 0 : 1);

    // 越境: tournament → team ドメインの Service を slug→ID の読み取り解決のみに使う（原則 5。tx は持ち込まない）
    private final TeamService teamService;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentContactSpaceRepository contactSpaceRepository;
    private final TournamentContactSpaceAccessGate accessGate;

    /**
     * 参加者向けに、自チームの文脈で見られる連絡スペースを解決する。
     *
     * @param teamSlug     チーム slug（数値だけの文字列も slug として扱う）
     * @param tournamentId 大会 ID
     * @param userId       ユーザー ID（認証必須）
     * @throws BusinessException TOUR_029（チーム不在・アーカイブ済み・PROVISIONED・非所属・大会不在/論理削除・非参加・参加外を区別しない）
     */
    public ParticipantContactSpacesResponse listForParticipant(String teamSlug, Long tournamentId, Long userId) {
        // 手順2: slug 解決（例外を投げない Optional 版。不在・アーカイブ済み・PROVISIONED は空）
        Optional<Long> teamId = teamService.resolveFollowableTeamId(teamSlug);
        // 手順3: 参加判定（native 1 本）。チーム文脈での部門の絞り込み（P19）はここで決まる
        List<ParticipantContactDivisionRow> rows = teamId
                .map(id -> participantRepository.findParticipantContactDivisions(id, tournamentId, userId))
                .orElse(List.of());
        if (rows.isEmpty()) {
            throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND);
        }

        // 部門 ID 昇順・重複排除（divisionId → 部門名）
        Map<Long, String> divisionNames = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(ParticipantContactDivisionRow::getDivisionId))
                .forEach(r -> divisionNames.putIfAbsent(r.getDivisionId(), r.getDivisionName()));
        String tournamentName = rows.get(0).getTournamentName();

        // 手順4: 大会全体＋参加部門のスペースを 1 本で取得し、Gate で一括解決（実在した行だけが対象）
        List<TournamentContactSpaceEntity> spaces =
                contactSpaceRepository.findForTournamentAndDivisions(tournamentId, divisionNames.keySet());
        Map<UUID, ContactAccessLevel> levels = accessGate.resolveLevelsForSpaces(userId, tournamentId, spaces);

        List<SpaceItem> items = spaces.stream()
                .filter(s -> isInContext(s, tournamentId, divisionNames))
                .filter(s -> levels.containsKey(s.getId()))
                .sorted(SPACE_ORDER)
                .map(s -> toItem(s, divisionNames, levels.get(s.getId())))
                .toList();
        List<DivisionItem> divisions = divisionNames.entrySet().stream()
                .map(e -> new DivisionItem(e.getKey(), e.getValue()))
                .toList();
        return new ParticipantContactSpacesResponse(tournamentId, tournamentName, divisions, items);
    }

    /** チーム文脈（大会全体 or 自チームの参加部門）に属するスペースか。 */
    private static boolean isInContext(TournamentContactSpaceEntity s, Long tournamentId, Map<Long, String> divisionNames) {
        if (s.getScopeType() == ContactSpaceScopeType.TOURNAMENT) {
            return tournamentId.equals(s.getScopeId());
        }
        return divisionNames.containsKey(s.getScopeId());
    }

    private static SpaceItem toItem(TournamentContactSpaceEntity s, Map<Long, String> divisionNames,
                                    ContactAccessLevel level) {
        String divisionName = s.getScopeType() == ContactSpaceScopeType.TOURNAMENT
                ? null : divisionNames.get(s.getScopeId());
        return new SpaceItem(s.getId(), s.getScopeType(), s.getScopeId(), divisionName, s.getSpaceKind(),
                s.getRefId(), Boolean.TRUE.equals(s.getIsPublic()), level);
    }
}
