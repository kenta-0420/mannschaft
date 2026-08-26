package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.PromotionType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.CreatePromotionRequest;
import com.mannschaft.app.tournament.dto.PromotionPreviewResponse;
import com.mannschaft.app.tournament.dto.PromotionRecordResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentPromotionRecordEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentPromotionRecordRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 昇格・降格管理サービス。
 *
 * <h2>認可（認可根治戦役 Wave2 トランシェ2C）</h2>
 * <p>従来は認可が完全に欠落しており、認証さえあれば他組織の大会の昇降格を実行/閲覧できる
 * IDOR/BOLA の穴だった。実行・プレビューは tId が path orgId 配下であることを検証した上で
 * 主催組織 ADMIN/DEPUTY_ADMIN を要求し、履歴（閲覧）は親大会の F00 可視性判定に委譲する
 * （不可視は 404・fail-closed）。entries の fromDivisionId/toDivisionId は必ず tId 配下であることを
 * 束縛検証し、他大会の division を昇降格記録へ混入させる BOLA を遮断する。</p>
 */
@Slf4j
@Service("tournamentPromotionService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private final TournamentDivisionRepository divisionRepository;
    private final TournamentStandingRepository standingRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentFixtureRepository matchRepository;
    private final TournamentPromotionRecordRepository promotionRecordRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentMapper mapper;
    private final AccessControlService accessControlService;
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * 昇降格プレビューを取得する（主催組織 ADMIN/DEPUTY_ADMIN 限定）。
     * tId が path orgId 配下であることを検証（BOLA 是正）した上で現在の順位から自動判定した候補を表示。
     */
    public PromotionPreviewResponse getPromotionPreview(Long orgId, Long tournamentId, Long userId) {
        findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        List<TournamentDivisionEntity> divisions =
                divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId);

        // ディビジョンのペア（上位⇔下位）
        List<PromotionPreviewResponse.PromotionCandidate> candidates = new ArrayList<>();

        for (int i = 0; i < divisions.size(); i++) {
            TournamentDivisionEntity division = divisions.get(i);
            List<TournamentStandingEntity> standings =
                    standingRepository.findByDivisionIdOrderByRankAsc(division.getId());

            if (standings.isEmpty()) continue;

            // 上位ディビジョンがある場合 → 昇格候補
            if (i > 0 && division.getPromotionSlots() > 0) {
                TournamentDivisionEntity upperDiv = divisions.get(i - 1);
                for (TournamentStandingEntity s : standings) {
                    if (s.getRank() <= division.getPromotionSlots()) {
                        TournamentParticipantEntity participant =
                                participantRepository.findById(s.getParticipantId()).orElse(null);
                        if (participant != null) {
                            candidates.add(new PromotionPreviewResponse.PromotionCandidate(
                                    participant.getTeamId(),
                                    division.getId(), division.getName(),
                                    upperDiv.getId(), upperDiv.getName(),
                                    "PROMOTION", s.getRank()));
                        }
                    }
                }
            }

            // 下位ディビジョンがある場合 → 降格候補
            if (i < divisions.size() - 1 && division.getRelegationSlots() > 0) {
                TournamentDivisionEntity lowerDiv = divisions.get(i + 1);
                int totalTeams = standings.size();
                for (TournamentStandingEntity s : standings) {
                    if (s.getRank() > totalTeams - division.getRelegationSlots()) {
                        TournamentParticipantEntity participant =
                                participantRepository.findById(s.getParticipantId()).orElse(null);
                        if (participant != null) {
                            candidates.add(new PromotionPreviewResponse.PromotionCandidate(
                                    participant.getTeamId(),
                                    division.getId(), division.getName(),
                                    lowerDiv.getId(), lowerDiv.getName(),
                                    "RELEGATION", s.getRank()));
                        }
                    }
                }
            }
        }

        return new PromotionPreviewResponse(candidates);
    }

    /**
     * 昇降格を実行する（主催組織 ADMIN/DEPUTY_ADMIN 限定）。
     * tId が path orgId 配下であることを検証（BOLA 是正）し、各 entry の
     * fromDivisionId/toDivisionId が tId 配下であることを束縛検証する（他大会 division の混入遮断）。
     */
    @Transactional
    public List<PromotionRecordResponse> executePromotions(Long orgId, Long tournamentId, Long userId,
                                                           CreatePromotionRequest request) {
        findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        // entries の division 束縛検証（BOLA 対策・IDOR 対策で 404 に統一）
        for (CreatePromotionRequest.PromotionEntry entry : request.getEntries()) {
            findDivisionInTournamentOrThrow(tournamentId, entry.getFromDivisionId());
            findDivisionInTournamentOrThrow(tournamentId, entry.getToDivisionId());
        }

        // 全試合完了チェック
        List<TournamentDivisionEntity> divisions =
                divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId);
        for (TournamentDivisionEntity div : divisions) {
            long incomplete = matchRepository.countIncompleteByDivisionId(div.getId());
            if (incomplete > 0) {
                throw new BusinessException(TournamentErrorCode.MATCHES_NOT_COMPLETED);
            }
        }

        List<PromotionRecordResponse> results = new ArrayList<>();
        for (CreatePromotionRequest.PromotionEntry entry : request.getEntries()) {
            // 重複チェック
            promotionRecordRepository.findByTournamentIdAndTeamId(tournamentId, entry.getTeamId())
                    .ifPresent(r -> { throw new BusinessException(TournamentErrorCode.PROMOTION_ALREADY_EXECUTED); });

            TournamentPromotionRecordEntity record = TournamentPromotionRecordEntity.builder()
                    .tournamentId(tournamentId)
                    .teamId(entry.getTeamId())
                    .fromDivisionId(entry.getFromDivisionId())
                    .toDivisionId(entry.getToDivisionId())
                    .type(PromotionType.valueOf(entry.getType()))
                    .finalRank(entry.getFinalRank())
                    .reason(entry.getReason())
                    .executedBy(userId)
                    .build();
            record = promotionRecordRepository.save(record);
            results.add(mapper.toPromotionRecordResponse(record));
        }

        return results;
    }

    /**
     * 昇降格履歴を取得する（閲覧系）。
     * 親大会の F00 可視性判定に委譲する（不可視は 404・fail-closed）。
     */
    public List<PromotionRecordResponse> getPromotionHistory(Long tournamentId, Long viewerUserId) {
        verifyTournamentVisible(tournamentId, viewerUserId);
        return promotionRecordRepository.findByTournamentIdOrderByExecutedAtDesc(tournamentId)
                .stream().map(mapper::toPromotionRecordResponse).toList();
    }

    // ===== 内部ヘルパー =====

    /**
     * 大会が path orgId 配下であることを検証する（BOLA 対策・IDOR 対策で 404 に統一）。
     */
    private TournamentEntity findTournamentInOrgOrThrow(Long orgId, Long tournamentId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        if (!tournament.getOrganizationId().equals(orgId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return tournament;
    }

    /**
     * 大会 visibility ガード（閲覧系）。認証ユーザー（未認証なら null）が当該 tournament を
     * 閲覧できるか F00 共通可視性 Resolver で判定し、不可視なら 404 を投げる。
     */
    private void verifyTournamentVisible(Long tournamentId, Long viewerUserId) {
        if (!contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, tournamentId, viewerUserId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    /** divId が tournamentId 配下であることを束縛検証する（BOLA/IDOR 対策）。 */
    private void findDivisionInTournamentOrThrow(Long tournamentId, Long divisionId) {
        divisionRepository.findByIdAndTournamentId(divisionId, tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
    }
}
