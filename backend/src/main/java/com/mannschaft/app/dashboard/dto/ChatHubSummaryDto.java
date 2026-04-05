package com.mannschaft.app.dashboard.dto;

/**
 * チャットハブ サマリーDTO。
 */
public record ChatHubSummaryDto(
        int totalUnread,
        int totalDmCount,
        int totalContactCount
) {}
