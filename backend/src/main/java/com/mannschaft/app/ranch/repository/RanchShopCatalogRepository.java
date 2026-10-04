package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchShopCatalogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 公開済み価格版のみ取得する。未公開行は本人shopへ投影しない。 */
public interface RanchShopCatalogRepository extends JpaRepository<RanchShopCatalogEntity, UUID> {
    Optional<RanchShopCatalogEntity> findFirstBySkuKeyAndActiveTrueOrderByPriceVersionDesc(
            String skuKey);

    List<RanchShopCatalogEntity> findByActiveTrueOrderBySkuKeyAscPriceVersionDesc();
}
