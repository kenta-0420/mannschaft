package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.gamification.PeriodType;
import com.mannschaft.app.gamification.entity.GamificationUserSettingEntity;
import com.mannschaft.app.gamification.entity.RankingSnapshotEntity;
import com.mannschaft.app.gamification.repository.GamificationUserSettingRepository;
import com.mannschaft.app.gamification.repository.RankingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ゲーミフィケーション・ランキングサービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GamificationRankingService {

    private final RankingSnapshotRepository rankingSnapshotRepository;
    private final GamificationUserSettingRepository gamificationUserSettingRepository;

    /**
     * ランキングを取得する。
     * show_in_ranking=falseのユーザーをレスポンスから除外する。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param periodType  期間種別
     * @param periodLabel 期間ラベル
     * @return ランキングスナップショット一覧（除外後）
     */
    public ApiResponse<List<RankingSnapshotEntity>> getRanking(
            String scopeType, Long scopeId, PeriodType periodType, String periodLabel) {

        List<RankingSnapshotEntity> snapshots = rankingSnapshotRepository
                .findByScopeTypeAndScopeIdAndPeriodTypeAndPeriodLabelOrderByRankPositionAsc(
                        scopeType, scopeId, periodType, periodLabel);

        // show_in_ranking=falseのユーザーIDセットを取得
        Set<Long> hiddenUserIds = snapshots.stream()
                .map(RankingSnapshotEntity::getUserId)
                .distinct()
                .filter(userId -> {
                    return gamificationUserSettingRepository
                            .findByUserIdAndScopeTypeAndScopeId(userId, scopeType, scopeId)
                            .map(setting -> !Boolean.TRUE.equals(setting.getShowInRanking()))
                            .orElse(false); // デフォルトはshow_in_ranking=true（除外しない）
                })
                .collect(Collectors.toSet());

        List<RankingSnapshotEntity> filtered = snapshots.stream()
                .filter(snapshot -> !hiddenUserIds.contains(snapshot.getUserId()))
                .toList();

        return ApiResponse.of(filtered);
    }

    /**
     * 期間種別と日付から期間ラベルを生成する。
     * WEEKLY: "2026-W13" / MONTHLY: "2026-03" / YEARLY: "2026"
     *
     * @param periodType 期間種別
     * @param date       基準日
     * @return 期間ラベル文字列
     */
    public String buildPeriodLabel(PeriodType periodType, LocalDate date) {
        return switch (periodType) {
            case WEEKLY -> {
                int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                int year = date.get(IsoFields.WEEK_BASED_YEAR);
                yield String.format("%d-W%02d", year, week);
            }
            case MONTHLY -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            case YEARLY -> String.valueOf(date.getYear());
        };
    }
}
