package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCalendarEventLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 歳時記×村史の年輪リポジトリ（F17.2 Wave1 ④年輪・設計書 §6.2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageCalendarEventLogRepository extends JpaRepository<VillageCalendarEventLogEntity, UUID> {

    /** 歳時記の生きている年輪一覧（year 降順→作成日降順・設計書 §11.4/§13.5）。 */
    Page<VillageCalendarEventLogEntity> findByCalendarEventIdAndDeletedAtIsNullOrderByYearDescCreatedAtDesc(
            UUID calendarEventId, Pageable pageable);

    /** 歳時記の生きている年輪一覧を年で絞り込み（{@code ?year=} 指定時・設計書 §6.4）。 */
    Page<VillageCalendarEventLogEntity> findByCalendarEventIdAndYearAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID calendarEventId, Integer year, Pageable pageable);
}
