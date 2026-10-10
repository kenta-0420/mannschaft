package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.util.UUID;

/** 保存時点に固定した管理公開ACK。再送時は現在の規則から再生成しない。 */
public record RanchCareRulePublicationResponse(UUID id, String version, String contentHash,
        Instant effectiveAt, RanchCareRulePublicationRequest settings, Instant publishedAt, String publishedBy) { }