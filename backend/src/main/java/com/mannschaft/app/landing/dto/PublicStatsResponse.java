package com.mannschaft.app.landing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * ランディングページ公開統計レスポンス。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicStatsResponse {

    private long totalUsers;
    private long totalTeams;
    private long totalOrganizations;
    private Map<String, CountryStats> countryBreakdown;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CountryStats {
        private long users;
        private long teams;
        private long organizations;
    }
}
