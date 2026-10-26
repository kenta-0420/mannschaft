package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
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

    /**
     * 寄合の行を悲観ロック（{@code SELECT ... FOR UPDATE}）で取得する（F17.2 追補・寄合定員の並行制御）。
     *
     * <p>GOING 出欠の定員判定を直列化するために使う。@Version 楽観ロックは「別ユーザーが別の
     * 出欠行を insert する定員超過」（親行が dirty にならないため衝突しない）を防げない。よって
     * <strong>親の寄合行そのものをロック</strong>してから GOING 数を数え、判定・書込みまでを
     * 同一トランザクションに収めることで、同時 GOING を安全に直列化する（AC-20）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM VillageMeetupEntity m WHERE m.id = :id")
    Optional<VillageMeetupEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 接近通知バッチ用（F17.2 Wave2 ①・設計書 §3.5）: 指定状態かつ {@code confirmed_date} が
     * 指定日の生きている寄合を取得する。「翌日開催」の走査に使う（CONFIRMED のみ対象）。
     */
    java.util.List<VillageMeetupEntity> findByStatusAndConfirmedDateAndDeletedAtIsNull(
            VillageMeetupStatus status, java.time.LocalDate confirmedDate);

    // ====================================================================
    // F17.1 ②-2 村ニュースレター集計（村ドメイン内 read-only 呼出）
    // ====================================================================

    /**
     * 村ニュースレター集計用: 指定期間内に作成された生きている寄合件数（F17.1 ②-2・設計書 §5.3）。
     *
     * <p>{@code created_at} 基準・半開区間 {@code [fromInclusive, toExclusive)}・論理削除除外。</p>
     */
    @Query("""
            SELECT COUNT(m) FROM VillageMeetupEntity m
            WHERE m.villageId = :villageId
              AND m.deletedAt IS NULL
              AND m.createdAt >= :fromInclusive
              AND m.createdAt <  :toExclusive
            """)
    long countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(
            @Param("villageId") UUID villageId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive);
}
