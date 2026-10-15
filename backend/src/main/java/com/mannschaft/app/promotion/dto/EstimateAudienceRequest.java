package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 配信対象見積リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class EstimateAudienceRequest {

    private final List<SegmentCondition> segments;
}
