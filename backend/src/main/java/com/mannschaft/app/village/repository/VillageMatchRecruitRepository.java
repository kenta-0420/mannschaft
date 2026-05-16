package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 村練習試合・募集リポジトリ（F17.1 Phase 2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageMatchRecruitRepository extends JpaRepository<VillageMatchRecruitEntity, UUID> {

    /** 村の生きている募集一覧（削除済みは除外）。 */
    Page<VillageMatchRecruitEntity> findByVillageIdAndDeletedAtIsNull(UUID villageId, Pageable pageable);

    /** 状態別の村の募集一覧。 */
    Page<VillageMatchRecruitEntity> findByVillageIdAndStatusAndDeletedAtIsNull(
            UUID villageId, VillageMatchRecruitStatus status, Pageable pageable);

    /** カテゴリ + 状態別の村の募集一覧。 */
    Page<VillageMatchRecruitEntity> findByVillageIdAndCategoryAndStatusAndDeletedAtIsNull(
            UUID villageId, VillageMatchRecruitCategory category, VillageMatchRecruitStatus status, Pageable pageable);
}
