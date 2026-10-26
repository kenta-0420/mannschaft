package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.ReportFrequency;

import java.time.LocalDateTime;
import java.util.List;

public record ReportScheduleResponse(
    Long id,
    ReportFrequency frequency,
    List<String> recipients,
    List<Long> includeCampaigns,
    boolean enabled,
    LocalDateTime lastSentAt
) {}
