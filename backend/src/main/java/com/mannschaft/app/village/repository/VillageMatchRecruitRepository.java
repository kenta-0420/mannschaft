package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * 指定カテゴリを参照している<strong>生きている</strong>募集の件数（F17.1 P2 §6.2）。
     *
     * <p>削除ガード（AC-10・{@code RECRUIT_CATEGORY_IN_USE}）と一覧表示の {@code recruitCount}
     * は必ず同じ数を使う（設計書 §6.2）。論理削除済みの募集は数えない
     * （＝論理削除済み募集しか参照しないカテゴリは削除できる。設計書 §4.3 の注）。</p>
     */
    long countByVillageIdAndCategoryIdAndDeletedAtIsNull(UUID villageId, UUID categoryId);

    /**
     * 村内のカテゴリ別・生きている募集件数を一括集計する（N+1 対策・設計書 §6.2）。
     *
     * <p>{@code TodoStatusLabelService.findActiveByIds}（一覧 API の N+1 対策）と同じ動機・
     * 同じ作法。1本の {@code GROUP BY category_id} 集計で {@code Map<UUID, Long>} を作り、
     * カテゴリ一覧レスポンス組み立て時に引く。</p>
     *
     * @return {@code [categoryId, count]} の配列のリスト（{@code categoryId} が NULL の行は除外）
     */
    @Query("""
            SELECT r.categoryId, COUNT(r) FROM VillageMatchRecruitEntity r
            WHERE r.villageId = :villageId
              AND r.categoryId IS NOT NULL
              AND r.deletedAt IS NULL
            GROUP BY r.categoryId
            """)
    List<Object[]> countActiveGroupedByCategory(@Param("villageId") UUID villageId);
}
