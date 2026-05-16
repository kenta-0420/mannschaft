package com.mannschaft.app.advertising.campaign.enums;

/**
 * F09.17 ターゲティングセグメント種別。
 * 設計書 §3.2 (3) {@code ad_audience_segments.segment_type} に対応。
 */
public enum AdSegmentType {
    AGE_RANGE,
    GENDER,
    REGION_PREFECTURE,
    REGION_CITY,
    INTEREST_TAG,
    ORG_TYPE,
    LOCALE,
    DEVICE
}
