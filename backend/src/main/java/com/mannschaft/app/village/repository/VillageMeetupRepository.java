package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 寄合リポジトリ（F17.1 Phase 3-β）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageMeetupRepository extends JpaRepository<VillageMeetupEntity, UUID> {

    /** 村の生きている寄合一覧（削除済みは除外）。 */
    Page<VillageMeetupEntity> findByVillageIdAndDeletedAtIsNull(UUID villageId, Pageable pageable);

    /** 状態別の村の寄合一覧。 */
    Page<VillageMeetupEntity> findByVillageIdAndStatusAndDeletedAtIsNull(
            UUID villageId, VillageMeetupStatus status, Pageable pageable);
}
