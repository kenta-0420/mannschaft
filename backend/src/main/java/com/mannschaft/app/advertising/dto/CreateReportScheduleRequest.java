package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.ReportFrequency;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateReportScheduleRequest(
    @NotNull ReportFrequency frequency,
    @NotEmpty @Size(max = 5) List<@Email String> recipients,
    List<Long> includeCampaigns
) {}
