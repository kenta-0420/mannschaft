package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.util.UUID;

/** 購入時点の価格版・残高を凍結した成功結果。 */
public record RanchPurchaseResult(UUID commandId, UUID inventoryId, String skuKey,
                                  String costPoints, String priceVersion,
                                  String balanceAfter, Instant completedAt) { }
