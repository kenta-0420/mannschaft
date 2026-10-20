package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.util.UUID;

/** 管理一覧の公開版metadata。本文・個人profile・ownerを含めない。 */
public record RanchCareRuleSummary(UUID id, String version, Instant effectiveAt, String amountXp,
        String weeklyCapXp, String juvenileXp, String adultXp, String contentHash, Instant publishedAt, String publishedBy) { }