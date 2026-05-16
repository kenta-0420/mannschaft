package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCalendarEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * 指定月に該当するイベント一覧を取得する（U4 §2.2 歳時記カレンダー）。
     *
     * <p>取得条件は OR で 2 通り:</p>
     * <ul>
     *   <li>{@code is_annual_recurring = TRUE} で {@code MONTH(event_date) = :month} のもの（毎年繰返）</li>
     *   <li>{@code is_annual_recurring = FALSE} で {@code YEAR(event_date) = :year AND MONTH(event_date) = :month} のもの（単発）</li>
     * </ul>
     *
     * <p>{@code event_end_date} が指定された複数日イベントも、開始日が当月に含まれていれば返す。
     * 設計書 §2.2 では単純化のため期間跨ぎの月別表示は呼出側で補完する想定。</p>
     */
    @Query("""
            SELECT e FROM VillageCalendarEventEntity e
             WHERE e.villageId = :villageId
               AND e.deletedAt IS NULL
               AND MONTH(e.eventDate) = :month
               AND (e.isAnnualRecurring = TRUE OR YEAR(e.eventDate) = :year)
             ORDER BY e.eventDate ASC, e.createdAt ASC
            """)
    List<VillageCalendarEventEntity> findByMonth(@Param("villageId") UUID villageId,
                                                 @Param("year") int year,
                                                 @Param("month") int month);
}
