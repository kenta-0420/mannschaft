package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.PromotionType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.CreatePromotionRequest;
import com.mannschaft.app.tournament.dto.PromotionPreviewResponse;
import com.mannschaft.app.tournament.dto.PromotionRecordResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentPromotionRecordEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentPromotionRecordRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 昇格・降格管理サービス。
 */
@Slf4j
@Service("tournamentPromotionService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private final TournamentDivisionRepository divisionRepository;
    private final TournamentStandingRepository standingRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentMatchRepository matchRepository;
    private final TournamentPromotionRecordRepository promotionRecordRepository;
    private final TournamentMapper mapper;

    /**
     * 昇降格プレビューを取得する。現在の順位から自動判定した候補を表示。
     */
    public PromotionPreviewResponse getPromotionPreview(Long tournamentId) {
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
     * 昇降格を実行する。
     */
    @Transactional
    public List<PromotionRecordResponse> executePromotions(Long tournamentId, Long userId,
                                                           CreatePromotionRequest request) {
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
     * 昇降格履歴を取得する。
     */
    public List<PromotionRecordResponse> getPromotionHistory(Long tournamentId) {
        return promotionRecordRepository.findByTournamentIdOrderByExecutedAtDesc(tournamentId)
                .stream().map(mapper::toPromotionRecordResponse).toList();
    }
}
