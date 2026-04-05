package com.mannschaft.app.gamification.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gamification.GamificationMapper;
import com.mannschaft.app.gamification.PeriodType;
import com.mannschaft.app.gamification.dto.RankingResponse;
import com.mannschaft.app.gamification.service.GamificationRankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * ゲーミフィケーション・ランキングコントローラー。
 * チームスコープのランキング取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/gamification/rankings")
@RequiredArgsConstructor
public class GamificationRankingController {

    private final GamificationRankingService gamificationRankingService;
    private final GamificationMapper gamificationMapper;
    private final AccessControlService accessControlService;

    /**
     * ランキングを取得する。
     *
     * @param teamId      チームID
     * @param periodType  期間種別（デフォルト: WEEKLY）
     * @param periodLabel 期間ラベル（例: 2026-W13。省略時は現在期間を自動生成）
     * @return ランキング一覧
     */
    @GetMapping
    public ApiResponse<List<RankingResponse>> getRanking(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "WEEKLY") PeriodType periodType,
            @RequestParam(required = false) String periodLabel) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        String resolvedLabel = (periodLabel != null && !periodLabel.isBlank())
                ? periodLabel
                : gamificationRankingService.buildPeriodLabel(periodType, LocalDate.now());

        List<RankingResponse> responses = gamificationRankingService
                .getRanking("TEAM", teamId, periodType, resolvedLabel)
                .getData()
                .stream()
                .map(gamificationMapper::toRankingResponse)
                .toList();

        return ApiResponse.of(responses);
    }
}
