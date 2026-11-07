package com.mannschaft.app.ranch.dto;

/** 管理入力。主体と有効時刻はrequestから受けず、厳格parserと管理writerで決定する。 */
public record RanchOperationalControlsRequest(String version, boolean isCareEnabled,
        boolean isShopEnabled, boolean isDeliveryPaused, boolean isRewardsPaused, String reasonCode) { }