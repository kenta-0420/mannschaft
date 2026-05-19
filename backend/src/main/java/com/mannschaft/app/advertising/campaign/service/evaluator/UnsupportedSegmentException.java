package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;

/**
 * 戦略パターン未配備のセグメント型を評価しようとした場合に投げる例外。
 *
 * <p>「{@link AdSegmentEvaluator} の実装が DI コンテナに登録されていない」ことを示す。
 * すなわち、新しい {@link AdSegmentType} を enum に追加したが対応する Evaluator を
 * 実装していないケースに該当する。</p>
 *
 * <p>F09.17 Phase 11-b 第二陣時点では 8 種すべての Evaluator が登録済みのため、
 * 通常運用ではこの例外は投げられない。ただし、データソース未整備のセグメント型
 * (AGE_RANGE / GENDER / REGION_PREFECTURE / REGION_CITY / INTEREST_TAG) を
 * 評価しようとした場合は {@link SegmentDataSourceNotAvailableException} の方が投げられる。</p>
 *
 * <p>本例外もデータソース未整備例外も、対処療法（空集合返却）を禁ずる
 * （CLAUDE.md「障害対応の原則 — 根治治療を徹底すること」）。</p>
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
