package com.mannschaft.app.advertising.dto;

public record RecommendationItem(
    String type,
    String priority,
    String message,
    Long campaignId,
    String action
) {}
