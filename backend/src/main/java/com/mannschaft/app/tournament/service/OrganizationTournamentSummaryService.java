package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.dto.DivisionLeaderProjection;
import com.mannschaft.app.tournament.dto.DivisionParticipantCountProjection;
import com.mannschaft.app.tournament.dto.OrganizationTournamentSummaryResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * F08.7.1 / 02 ②: 主催大会サマリ（ORG_TOURNAMENT_SUMMARY ウィジェット）の参照サービス。
 *
 * <p>組織が主催する各大会 × 各部の「首位チーム名・参加チーム数・大会 status」だけを集約して返す。
 * 設計書 docs/features/F08.7.1_tournament_extensions/02_dashboard_widgets.md §2.1 ② / §5.3 に準拠。</p>
 *
 * <h2>N+1 回避</h2>
 * 大会本体ループ内で個別クエリを撃たない。次の 4 クエリで全データを取得する:
 * <ol>
 *   <li>組織の大会一覧（DRAFT 除外）</li>
 *   <li>全大会のディビジョンを IN 句で一括取得</li>
 *   <li>全ディビジョンの参加数を GROUP BY で一括集約</li>
 *   <li>全ディビジョンの首位（rank=1）を IN 句で一括取得</li>
 * </ol>
 *
 * <h2>セキュリティ（§5.3）</h2>
 * 未公開（DRAFT）の大会は、min_role を PUBLIC に下げた場合でも漏れないよう API 側で除外する。
 * （本サービスは常に DRAFT を除外する。認可は Controller 層で組織 MEMBER 以上を要求する。）
 *
 * <p>{@code @Transactional(readOnly=true)} は tournament ドメイン内に閉じる（クロスドメイン参照なし）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationTournamentSummaryService {

    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentStandingRepository standingRepository;

    /**
     * 組織の主催大会サマリを取得する。
     *
     * @param organizationId 組織 ID
     * @return 主催大会サマリ（DRAFT 大会を除外・作成日降順）
     */
    public OrganizationTournamentSummaryResponse getSummary(Long organizationId) {
        // 1. 組織の大会一覧（§5.3: DRAFT を除外）
        List<TournamentEntity> tournaments =
                tournamentRepository.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
                        organizationId, TournamentStatus.DRAFT);

        if (tournaments.isEmpty()) {
            return OrganizationTournamentSummaryResponse.builder()
                    .tournaments(List.of())
                    .build();
        }

        List<Long> tournamentIds = tournaments.stream()
                .map(TournamentEntity::getId)
                .toList();

        // 2. 全大会のディビジョンを一括取得
        List<TournamentDivisionEntity> divisions =
                divisionRepository.findByTournamentIdInOrderByLevelAscSortOrderAsc(tournamentIds);

        List<Long> divisionIds = divisions.stream()
                .map(TournamentDivisionEntity::getId)
                .toList();

        // 3. 参加数を一括集約（division_id → count）
        Map<Long, Integer> participantCountByDivision = divisionIds.isEmpty()
                ? Map.of()
                : participantRepository.countParticipantsByDivisionIdIn(divisionIds).stream()
                        .collect(Collectors.toMap(
                                DivisionParticipantCountProjection::divisionId,
                                p -> p.participantCount() == null ? 0 : p.participantCount().intValue()));

        // 4. 首位（rank=1）を一括取得（division_id → 首位チーム名）
        Map<Long, String> leaderNameByDivision = divisionIds.isEmpty()
                ? Map.of()
                : standingRepository.findLeadersByDivisionIdIn(divisionIds).stream()
                        .collect(Collectors.toMap(
                                DivisionLeaderProjection::divisionId,
                                OrganizationTournamentSummaryService::resolveLeaderName,
                                // 同一ディビジョンに rank=1 が複数（同点・データ不整合）の場合は先勝ち
                                (a, b) -> a));

        // 大会 ID → ディビジョン一覧 にグルーピング
        Map<Long, List<TournamentDivisionEntity>> divisionsByTournament = divisions.stream()
                .collect(Collectors.groupingBy(TournamentDivisionEntity::getTournamentId));

        List<OrganizationTournamentSummaryResponse.TournamentSummaryEntry> entries = tournaments.stream()
                .map(t -> OrganizationTournamentSummaryResponse.TournamentSummaryEntry.builder()
                        .tournamentId(t.getId())
                        .name(t.getName())
                        .status(t.getStatus().name())
                        .divisions(buildDivisionEntries(
                                divisionsByTournament.getOrDefault(t.getId(), List.of()),
                                participantCountByDivision,
                                leaderNameByDivision))
                        .build())
                .toList();

        return OrganizationTournamentSummaryResponse.builder()
                .tournaments(entries)
                .build();
    }

    private List<OrganizationTournamentSummaryResponse.DivisionSummaryEntry> buildDivisionEntries(
            List<TournamentDivisionEntity> divisions,
            Map<Long, Integer> participantCountByDivision,
            Map<Long, String> leaderNameByDivision) {
        return divisions.stream()
                .map(d -> new OrganizationTournamentSummaryResponse.DivisionSummaryEntry(
                        d.getId(),
                        d.getName(),
                        participantCountByDivision.getOrDefault(d.getId(), 0),
                        leaderNameByDivision.get(d.getId())))
                .toList();
    }

    /**
     * 首位チームの表示名を解決する。displayName が空なら "Team {teamId}" にフォールバックする
     * （StandingsQueryService と同じ規約）。
     */
    private static String resolveLeaderName(DivisionLeaderProjection p) {
        if (p.displayName() != null && !p.displayName().isBlank()) {
            return p.displayName();
        }
        return "Team " + p.teamId();
    }
}
