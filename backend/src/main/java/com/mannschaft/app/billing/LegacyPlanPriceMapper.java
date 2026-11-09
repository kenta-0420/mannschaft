package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import org.mapstruct.Named;

/**
 * CMP-260930-1931: 新価格からlegacy保存型へ範囲検査して変換する。
 * MapStructの暗黙縮小変換に任せず、範囲外を既存の販売不能契約へ写す。
 */
@Mapper(componentModel = "spring")
public interface LegacyPlanPriceMapper {

    /** 価格Entityの検証後、必要なscalarだけをcopyする。 */
    BillingPriceSelector.LegacyPlanBandSnapshot toLegacyBandSnapshot(BillingPriceBandVersionEntity band);

    @Mapping(target = "priceVersionId", source = "revision.id")
    @Mapping(target = "bands", source = "bands")
    BillingPriceSelector.LegacyPlanSnapshot toLegacySnapshot(
            BillingPriceVersionEntity revision, List<BillingPriceSelector.LegacyPlanBandSnapshot> bands);

    @Mapping(target = "scopeKind", source = "scopeKind")
    @Mapping(target = "monthlyPriceJpy", source = "band.amountIncludingTax", qualifiedByName = "toCatalogPriceJpy")
    com.mannschaft.app.billing.api.dto.PriceBand toCatalogBand(
            BillingPriceSelector.LegacyPlanBandSnapshot band, String scopeKind);

    /** catalogでは表現不能な価格を販売不能nullへ写す契約。 */
    @Named("toCatalogPriceJpy")
    default Integer toCatalogPriceJpy(Long value) {
        if (value == null || value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    /** 契約では表現不能な価格を040で拒否する契約。 */
    @Named("requireContractPriceJpy")
    default Integer requireContractPriceJpy(Long value) {
        Integer converted = toCatalogPriceJpy(value);
        if (converted == null) {
            throw new BusinessException(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
        }
        return converted;
    }

    /** 契約帯は正のShort範囲のみを受け入れる契約。 */
    @Named("requireContractBandNo")
    default Short requireContractBandNo(Integer value) {
        if (value == null || value < 1 || value > Short.MAX_VALUE) {
            throw new BusinessException(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
        }
        return value.shortValue();
    }
}
