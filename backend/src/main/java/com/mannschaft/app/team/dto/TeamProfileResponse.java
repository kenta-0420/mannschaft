package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.organization.EstablishedDatePrecision;
import com.mannschaft.app.organization.ProfileVisibility;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * チーム拡張プロフィールレスポンス DTO。
 */
@Getter
@Builder
public class TeamProfileResponse {

    private Long id;

    @JsonProperty("homepage_url")
    private String homepageUrl;

    @JsonProperty("established_date")
    private LocalDate establishedDate;

    @JsonProperty("established_date_precision")
    private EstablishedDatePrecision establishedDatePrecision;

    private String philosophy;

    @JsonProperty("profile_visibility")
    private ProfileVisibility profileVisibility;
}
