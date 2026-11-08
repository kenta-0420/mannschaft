package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.util.UUID;

/** 保存した公開版の固定ACK。後日の設定や時計で再構成しない。 */
public record RanchPolicyPublicationResponse(UUID id, String version, String hash,
        Instant effectiveAt, RanchPolicyPublicationRequest settings, Instant publishedAt,
        String publishedBy) { }
