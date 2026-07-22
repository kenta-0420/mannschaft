package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * 村お祭りリポジトリ（F17.1 Phase 2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageFestivalRepository extends JpaRepository<VillageFestivalEntity, UUID> {

    /** 村の生きているお祭り一覧（削除済みは除外）。 */
    Page<VillageFestivalEntity> findByVillageIdAndDeletedAtIsNull(UUID villageId, Pageable pageable);

    /** 状態別の村のお祭り一覧。 */
    Page<VillageFestivalEntity> findByVillageIdAndStatusAndDeletedAtIsNull(
            UUID villageId, VillageFestivalStatus status, Pageable pageable);

    /** 自動状態遷移バッチ用: 指定状態のお祭りを一括取得。 */
    List<VillageFestivalEntity> findByStatusAndDeletedAtIsNull(VillageFestivalStatus status);

    /**
     * 接近通知バッチ用（F17.2 Wave2 ①・設計書 §3.5）: 指定状態かつ {@code starts_at} が
     * 半開区間 {@code [fromInclusive, toExclusive)} に入る生きているお祭りを取得する。
     * 「翌日開催」の走査に使う（前日1回のみ・冪等判定は timeline 側で別途行う）。
     */
    List<VillageFestivalEntity> findByStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanAndDeletedAtIsNull(
            VillageFestivalStatus status, java.time.LocalDateTime fromInclusive, java.time.LocalDateTime toExclusive);

    // ====================================================================
    // F17.1 ②-2 村ニュースレター集計（村ドメイン内 read-only 呼出）
    // ====================================================================

    /**
     * 村ニュースレター集計用: 指定期間内に作成された生きているお祭り件数（F17.1 ②-2・設計書 §5.3）。
     *
     * <p>{@code created_at} 基準・半開区間 {@code [fromInclusive, toExclusive)}・論理削除除外。
     * 掲示板/タイムライン集計（{@code BulletinThreadRepository#countByVillageIdAndCreatedAtBetween}）と
     * 同じ半開区間の作法に揃える。</p>
     */
    @Query("""
            SELECT COUNT(f) FROM VillageFestivalEntity f
            WHERE f.villageId = :villageId
              AND f.deletedAt IS NULL
              AND f.createdAt >= :fromInclusive
              AND f.createdAt <  :toExclusive
            """)
    long countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(
            @Param("villageId") UUID villageId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive);
}
