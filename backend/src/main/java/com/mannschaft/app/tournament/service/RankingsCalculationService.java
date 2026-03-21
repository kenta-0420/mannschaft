package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.RankingsRecalculationEvent;
import com.mannschaft.app.tournament.StatAggregationType;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.RankingSummaryResponse;
import com.mannschaft.app.tournament.entity.TournamentIndividualRankingEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchPlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import com.mannschaft.app.tournament.repository.TournamentIndividualRankingRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchPlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 個人ランキングの自動計算サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingsCalculationService {

    private final TournamentStatDefRepository statDefRepository;
    private final TournamentMatchPlayerStatRepository playerStatRepository;
    private final TournamentIndividualRankingRepository rankingRepository;
    private final TournamentMapper mapper;

    /**
     * ランキング再計算イベントを受信する。
     */
    @Async
    @EventListener
    @Transactional
    public void onRankingsRecalculation(RankingsRecalculationEvent event) {
        recalculateAll(event.getTournamentId());
    }

    /**
     * 全ランキング項目を再計算する。
     */
    @Transactional
    public void recalculateAll(Long tournamentId) {
        log.info("個人ランキング再計算開始: tournamentId={}", tournamentId);

        List<TournamentStatDefEntity> rankingDefs =
                statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(tournamentId);

        for (TournamentStatDefEntity def : rankingDefs) {
            recalculateForStatKey(tournamentId, def);
        }

        log.info("個人ランキング再計算完了: tournamentId={}", tournamentId);
    }

    private void recalculateForStatKey(Long tournamentId, TournamentStatDefEntity def) {
        String statKey = def.getStatKey();
        List<TournamentMatchPlayerStatEntity> allStats =
                playerStatRepository.findByTournamentIdAndStatKey(tournamentId, statKey);

        // ユーザーごとに集計
        Map<Long, PlayerAggregation> aggregations = new HashMap<>();

        for (TournamentMatchPlayerStatEntity stat : allStats) {
            PlayerAggregation agg = aggregations.computeIfAbsent(stat.getUserId(),
                    uid -> new PlayerAggregation(uid, stat.getParticipantId()));
            agg.matchCount++;

            switch (def.getDataType()) {
                case INTEGER -> {
                    if (stat.getValueInt() != null) {
                        agg.intValues.add(stat.getValueInt());
                    }
                }
                case DECIMAL -> {
                    if (stat.getValueDecimal() != null) {
                        agg.decimalValues.add(stat.getValueDecimal());
                    }
                }
                case TIME -> {
                    // TIME集計は将来対応
                }
            }
        }

        // 集計値の算出
        List<PlayerAggregation> sorted = new ArrayList<>(aggregations.values());
        for (PlayerAggregation agg : sorted) {
            switch (def.getDataType()) {
                case INTEGER -> agg.totalInt = aggregate(agg.intValues, def.getAggregationType());
                case DECIMAL -> agg.totalDecimal = aggregateDecimal(agg.decimalValues, def.getAggregationType());
                default -> {}
            }
        }

        // ソート（降順）
        sorted.sort((a, b) -> {
            if (a.totalInt != null && b.totalInt != null) return Integer.compare(b.totalInt, a.totalInt);
            if (a.totalDecimal != null && b.totalDecimal != null) return b.totalDecimal.compareTo(a.totalDecimal);
            return 0;
        });

        // ランキングの保存
        rankingRepository.deleteByTournamentIdAndStatKey(tournamentId, statKey);
        for (int i = 0; i < sorted.size(); i++) {
            PlayerAggregation agg = sorted.get(i);
            rankingRepository.save(TournamentIndividualRankingEntity.builder()
                    .tournamentId(tournamentId)
                    .userId(agg.userId)
                    .participantId(agg.participantId)
                    .statKey(statKey)
                    .rank(i + 1)
                    .totalValueInt(agg.totalInt)
                    .totalValueDecimal(agg.totalDecimal)
                    .matchesPlayed(agg.matchCount)
                    .build());
        }
    }

    private Integer aggregate(List<Integer> values, StatAggregationType type) {
        if (values.isEmpty()) return 0;
        return switch (type) {
            case SUM -> values.stream().mapToInt(Integer::intValue).sum();
            case AVG -> values.stream().mapToInt(Integer::intValue).sum() / values.size();
            case MAX -> values.stream().mapToInt(Integer::intValue).max().orElse(0);
            case MIN -> values.stream().mapToInt(Integer::intValue).min().orElse(0);
        };
    }

    private BigDecimal aggregateDecimal(List<BigDecimal> values, StatAggregationType type) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return switch (type) {
            case SUM -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
            case MAX -> values.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            case MIN -> values.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        };
    }

    // ===== Query =====

    public Page<IndividualRankingResponse> getRankings(Long tournamentId, String statKey, Pageable pageable) {
        TournamentStatDefEntity def = statDefRepository.findByTournamentIdAndStatKey(tournamentId, statKey)
                .orElse(null);
        String label = def != null ? def.getRankingLabel() : null;

        return rankingRepository.findByTournamentIdAndStatKeyOrderByRankAsc(tournamentId, statKey, pageable)
                .map(entity -> mapper.toIndividualRankingResponse(entity, label));
    }

    public RankingSummaryResponse getRankingSummary(Long tournamentId) {
        List<TournamentStatDefEntity> defs =
                statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(tournamentId);

        List<RankingSummaryResponse.RankingCategory> categories = defs.stream()
                .map(def -> {
                    List<TournamentIndividualRankingEntity> top =
                            rankingRepository.findByTournamentIdAndStatKeyOrderByRankAsc(tournamentId, def.getStatKey());
                    IndividualRankingResponse leader = top.isEmpty() ? null :
                            mapper.toIndividualRankingResponse(top.get(0), def.getRankingLabel());
                    return new RankingSummaryResponse.RankingCategory(
                            def.getStatKey(), def.getName(), def.getRankingLabel(), def.getUnit(), leader);
                })
                .toList();

        return new RankingSummaryResponse(categories);
    }

    private static class PlayerAggregation {
        final Long userId;
        final Long participantId;
        int matchCount = 0;
        List<Integer> intValues = new ArrayList<>();
        List<BigDecimal> decimalValues = new ArrayList<>();
        Integer totalInt = null;
        BigDecimal totalDecimal = null;

        PlayerAggregation(Long userId, Long participantId) {
            this.userId = userId;
            this.participantId = participantId;
        }
    }
}
