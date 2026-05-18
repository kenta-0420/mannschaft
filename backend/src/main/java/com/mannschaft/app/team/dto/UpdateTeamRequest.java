package com.mannschaft.app.team.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTeamRequest {

    private final String name;
    private final String nameKana;
    private final String nickname1;
    private final String nickname2;
    private final String template;
    private final String prefecture;
    private final String city;
    private final String visibility;
    private final Boolean supporterEnabled;

    /**
     * F15.4 Phase 5-β: Google Maps 埋め込み URL。
     * null 許容。null 以外の場合は Google Maps embed URL パターンに合致する必要がある。
     * 設計書: docs/features/F15.4_phase5_team_public_detail.md §5.2
     */
    @Pattern(
            regexp = "^https://www\\.google\\.com/maps/embed\\?.*$",
            message = "Google Maps 埋め込み URL（https://www.google.com/maps/embed?...）の形式である必要があります"
    )
    private final String mapEmbedUrl;

    @NotNull
    private final Long version;
}
