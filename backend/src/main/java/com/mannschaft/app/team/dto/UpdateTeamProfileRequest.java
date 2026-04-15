package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.organization.EstablishedDatePrecision;
import com.mannschaft.app.organization.ProfileVisibility;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * チーム拡張プロフィール更新リクエスト DTO。
 * PATCH /teams/{id}/profile
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateTeamProfileRequest {

    @JsonProperty("homepage_url")
    private String homepageUrl;

    @JsonProperty("established_date")
    private LocalDate establishedDate;

    @JsonProperty("established_date_precision")
    private EstablishedDatePrecision establishedDatePrecision;

    @JsonProperty("philosophy")
    private String philosophy;

    @JsonProperty("profile_visibility")
    private ProfileVisibility profileVisibility;
}
