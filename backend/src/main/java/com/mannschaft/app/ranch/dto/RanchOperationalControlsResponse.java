package com.mannschaft.app.ranch.dto;

import java.time.Instant;

/** 管理GET/保存ACKの最小状態。個人ownerや操作者の情報を含めない。 */
public record RanchOperationalControlsResponse(String version, boolean isCareEnabled,
        boolean isShopEnabled, boolean isDeliveryPaused, boolean isRewardsPaused, Instant updatedAt) { }