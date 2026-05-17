package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;

/**
 * 未実装のセグメント型を評価しようとした場合に投げる例外。
 *
 * <p>F09.17 Phase 11-b 第一陣時点では LOCALE / ORG_TYPE のみ評価可能。
 * AGE_RANGE / GENDER / REGION_PREFECTURE / REGION_CITY / INTEREST_TAG / DEVICE は
 * 後続フェーズ実装予定のため、不用意に「空集合扱い」しない（対処療法禁止原則）。</p>
 */
public class UnsupportedSegmentException extends BusinessException {

    private final AdSegmentType segmentType;

    public UnsupportedSegmentException(AdSegmentType type) {
        super(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        this.segmentType = type;
    }

    public AdSegmentType getSegmentType() {
        return segmentType;
    }
}
