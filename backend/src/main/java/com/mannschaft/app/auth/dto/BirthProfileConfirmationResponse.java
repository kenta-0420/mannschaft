package com.mannschaft.app.auth.dto;

import java.util.UUID;
import java.time.Instant;

/** 原情報を持たない不透明な確認参照と固定期限。 */
public record BirthProfileConfirmationResponse(UUID confirmationRef, Instant expiresAt, String profileRevision) {}
