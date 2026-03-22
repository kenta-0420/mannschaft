package com.mannschaft.app.promotion.mapper;

import com.mannschaft.app.promotion.dto.BillingRecordResponse;
import com.mannschaft.app.promotion.dto.CouponResponse;
import com.mannschaft.app.promotion.dto.SegmentPresetResponse;
import com.mannschaft.app.promotion.entity.CouponEntity;
import com.mannschaft.app.promotion.entity.PromotionBillingRecordEntity;
import com.mannschaft.app.promotion.entity.SavedSegmentPresetEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:09+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PromotionMapperImpl implements PromotionMapper {

    @Override
    public CouponResponse toCouponResponse(CouponEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        Long createdBy = null;
        String title = null;
        String description = null;
        String couponType = null;
        BigDecimal discountValue = null;
        BigDecimal minPurchaseAmount = null;
        Integer maxIssues = null;
        Integer issuedCount = null;
        Short maxUsesPerUser = null;
        LocalDateTime validFrom = null;
        LocalDateTime validUntil = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        createdBy = entity.getCreatedBy();
        title = entity.getTitle();
        description = entity.getDescription();
        couponType = entity.getCouponType();
        discountValue = entity.getDiscountValue();
        minPurchaseAmount = entity.getMinPurchaseAmount();
        maxIssues = entity.getMaxIssues();
        issuedCount = entity.getIssuedCount();
        maxUsesPerUser = entity.getMaxUsesPerUser();
        validFrom = entity.getValidFrom();
        validUntil = entity.getValidUntil();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        CouponResponse couponResponse = new CouponResponse( id, scopeType, scopeId, createdBy, title, description, couponType, discountValue, minPurchaseAmount, maxIssues, issuedCount, maxUsesPerUser, validFrom, validUntil, isActive, createdAt, updatedAt );

        return couponResponse;
    }

    @Override
    public List<CouponResponse> toCouponResponseList(List<CouponEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CouponResponse> list = new ArrayList<CouponResponse>( entities.size() );
        for ( CouponEntity couponEntity : entities ) {
            list.add( toCouponResponse( couponEntity ) );
        }

        return list;
    }

    @Override
    public SegmentPresetResponse toSegmentPresetResponse(SavedSegmentPresetEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String name = null;
        String conditions = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        name = entity.getName();
        conditions = entity.getConditions();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        SegmentPresetResponse segmentPresetResponse = new SegmentPresetResponse( id, scopeType, scopeId, name, conditions, createdBy, createdAt, updatedAt );

        return segmentPresetResponse;
    }

    @Override
    public List<SegmentPresetResponse> toSegmentPresetResponseList(List<SavedSegmentPresetEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SegmentPresetResponse> list = new ArrayList<SegmentPresetResponse>( entities.size() );
        for ( SavedSegmentPresetEntity savedSegmentPresetEntity : entities ) {
            list.add( toSegmentPresetResponse( savedSegmentPresetEntity ) );
        }

        return list;
    }

    @Override
    public BillingRecordResponse toBillingRecordResponse(PromotionBillingRecordEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long promotionId = null;
        String scopeType = null;
        Long scopeId = null;
        Integer deliveryCount = null;
        BigDecimal unitPrice = null;
        BigDecimal totalAmount = null;
        String billingStatus = null;
        String stripeChargeId = null;
        LocalDateTime billedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        promotionId = entity.getPromotionId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        deliveryCount = entity.getDeliveryCount();
        unitPrice = entity.getUnitPrice();
        totalAmount = entity.getTotalAmount();
        billingStatus = entity.getBillingStatus();
        stripeChargeId = entity.getStripeChargeId();
        billedAt = entity.getBilledAt();
        createdAt = entity.getCreatedAt();

        BillingRecordResponse billingRecordResponse = new BillingRecordResponse( id, promotionId, scopeType, scopeId, deliveryCount, unitPrice, totalAmount, billingStatus, stripeChargeId, billedAt, createdAt );

        return billingRecordResponse;
    }

    @Override
    public List<BillingRecordResponse> toBillingRecordResponseList(List<PromotionBillingRecordEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BillingRecordResponse> list = new ArrayList<BillingRecordResponse>( entities.size() );
        for ( PromotionBillingRecordEntity promotionBillingRecordEntity : entities ) {
            list.add( toBillingRecordResponse( promotionBillingRecordEntity ) );
        }

        return list;
    }
}
