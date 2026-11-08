package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 運営承認済み素材だけを取得する。 */
public interface RanchCollectibleCatalogRepository extends JpaRepository<RanchCollectibleCatalogEntity, String> {
    Optional<RanchCollectibleCatalogEntity> findByCollectibleKeyAndActiveTrue(String collectibleKey);
    List<RanchCollectibleCatalogEntity> findByCollectibleKeyInAndActiveTrue(Collection<String> keys);
}
