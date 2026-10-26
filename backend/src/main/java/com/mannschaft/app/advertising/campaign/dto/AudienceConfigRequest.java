package com.mannschaft.app.advertising.campaign.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * F09.17 ターゲティング設定リクエスト。
 * 既存セグメントを全件 replace する設計（空配列ならすべて削除）。
 */
public record AudienceConfigRequest(
        @NotNull
        @Valid
        List<AudienceSegmentRequest> segments
) {
}
