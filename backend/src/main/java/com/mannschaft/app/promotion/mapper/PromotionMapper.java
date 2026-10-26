package com.mannschaft.app.promotion.mapper;

import com.mannschaft.app.promotion.dto.BillingRecordResponse;
import com.mannschaft.app.promotion.dto.CouponResponse;
import com.mannschaft.app.promotion.dto.SegmentPresetResponse;
import com.mannschaft.app.promotion.entity.CouponEntity;
import com.mannschaft.app.promotion.entity.PromotionBillingRecordEntity;
import com.mannschaft.app.promotion.entity.SavedSegmentPresetEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * プロモーション配信の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface PromotionMapper {

    CouponResponse toCouponResponse(CouponEntity entity);

    List<CouponResponse> toCouponResponseList(List<CouponEntity> entities);

    SegmentPresetResponse toSegmentPresetResponse(SavedSegmentPresetEntity entity);

    List<SegmentPresetResponse> toSegmentPresetResponseList(List<SavedSegmentPresetEntity> entities);

    BillingRecordResponse toBillingRecordResponse(PromotionBillingRecordEntity entity);

    List<BillingRecordResponse> toBillingRecordResponseList(List<PromotionBillingRecordEntity> entities);
}
