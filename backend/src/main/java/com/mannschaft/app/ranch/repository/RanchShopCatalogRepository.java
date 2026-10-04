package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchShopCatalogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 公開済み価格版のみ取得する。未公開行は本人shopへ投影しない。 */
public interface RanchShopCatalogRepository extends JpaRepository<RanchShopCatalogEntity, UUID> {
    Optional<RanchShopCatalogEntity> findFirstBySkuKeyAndActiveTrueOrderByPriceVersionDesc(
            String skuKey);

    List<RanchShopCatalogEntity> findByActiveTrueOrderBySkuKeyAscPriceVersionDesc();
    /** 有効価格と承認済み同origin素材catalogの対応があるSKUだけを公開gateへ数える。 */
    @Query("SELECT COUNT(item) > 0 FROM RanchShopCatalogEntity item, RanchCollectibleCatalogEntity collectible "
            + "WHERE item.collectibleKey = collectible.collectibleKey AND item.active = true "
            + "AND collectible.active = true AND item.pricePoints > 0 AND item.priceVersion > 0 "
            + "AND collectible.assetKey <> '' AND collectible.labelKey <> ''")
    boolean hasApprovedItems();
}
