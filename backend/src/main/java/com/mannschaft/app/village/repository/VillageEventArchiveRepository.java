package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageEventArchiveEntity;
import com.mannschaft.app.village.entity.enums.VillageEventArchiveSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 村史（行事アーカイブ）リポジトリ（F17.2 Wave2 ⑦・設計書 §7.2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageEventArchiveRepository extends JpaRepository<VillageEventArchiveEntity, UUID> {

    /** 元行事の種別×UUID で既存の村史エントリを検索（冪等・二重編纂防止・設計書 §5.5）。 */
    Optional<VillageEventArchiveEntity> findBySourceTypeAndSourceId(
            VillageEventArchiveSourceType sourceType, UUID sourceId);

    /** 村史タブ一覧（新しい順・設計書 §7.4）。 */
    Page<VillageEventArchiveEntity> findByVillageIdAndDeletedAtIsNullOrderByArchivedAtDesc(
            UUID villageId, Pageable pageable);

    /** 村史タブ一覧（種別絞り込み・新しい順・設計書 §7.4）。 */
    Page<VillageEventArchiveEntity> findByVillageIdAndSourceTypeAndDeletedAtIsNullOrderByArchivedAtDesc(
            UUID villageId, VillageEventArchiveSourceType sourceType, Pageable pageable);
}
