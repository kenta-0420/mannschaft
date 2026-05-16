package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCalendarEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 村歳時記カレンダーリポジトリ（F17.1 Phase 2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageCalendarEventRepository extends JpaRepository<VillageCalendarEventEntity, UUID> {

    /** 村の生きているイベント一覧（削除済みは除外）。 */
    Page<VillageCalendarEventEntity> findByVillageIdAndDeletedAtIsNull(UUID villageId, Pageable pageable);
}
