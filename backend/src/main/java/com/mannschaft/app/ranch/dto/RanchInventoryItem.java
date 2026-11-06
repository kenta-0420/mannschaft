package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.util.UUID;

/** 本人所有置物の安全な一覧表示形。 */
public record RanchInventoryItem(UUID id, String collectibleKey, String labelKey,
                                 String assetKey, String acquisitionKind,
                                 Instant awardedAt, boolean isRevoked,
                                 String placedSlotKey) { }
