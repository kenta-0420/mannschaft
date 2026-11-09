package com.mannschaft.app.billing;

import org.mapstruct.Mapper;
import org.mapstruct.Named;

/**
 * CMP-260930-1931: 新価格からlegacy保存型への変換契約の試練骨格。
 * Rootが許可した3signatureのみを固定し、既存の読取・契約入口にはまだ接続しない。
 * 全メソッドは未実装を明示し、MapStructの暗黙縮小変換を成功実装として使わない。
 */
@Mapper(componentModel = "spring")
public interface LegacyPlanPriceMapper {

    /** catalogでは表現不能な価格を販売不能nullへ写す契約。現時点は未実装。 */
    @Named("toCatalogPriceJpy")
    default Integer toCatalogPriceJpy(Long value) {
        throw new UnsupportedOperationException("CMP-260930-1931 試練骨格: 未実装");
    }

    /** 契約では表現不能な価格を040で拒否する契約。現時点は未実装。 */
    @Named("requireContractPriceJpy")
    default Integer requireContractPriceJpy(Long value) {
        throw new UnsupportedOperationException("CMP-260930-1931 試練骨格: 未実装");
    }

    /** 契約帯は正のShort範囲のみを受け入れる契約。現時点は未実装。 */
    @Named("requireContractBandNo")
    default Short requireContractBandNo(Integer value) {
        throw new UnsupportedOperationException("CMP-260930-1931 試練骨格: 未実装");
    }
}
