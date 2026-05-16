package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageChronicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村史リポジトリ（F17.1 Phase 3-β）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageChronicleRepository extends JpaRepository<VillageChronicleEntity, UUID> {

    /**
     * 村と年月で 1 件取得する（UNIQUE 制約に対応）。
     * バッチの UPSERT 判定に利用する。
     */
    Optional<VillageChronicleEntity> findByVillageIdAndYearMonth(UUID villageId, LocalDate yearMonth);

    /**
     * 村の村史一覧を年月降順で取得する。
     */
    List<VillageChronicleEntity> findByVillageIdOrderByYearMonthDesc(UUID villageId);
}
