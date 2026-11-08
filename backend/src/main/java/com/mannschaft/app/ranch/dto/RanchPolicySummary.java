package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.util.UUID;

public record RanchPolicySummary(UUID id, String version, Instant effectiveAt,
        RanchPolicyPublicationRequest settings, String contentHash, Instant publishedAt,
        String publishedBy) { }
