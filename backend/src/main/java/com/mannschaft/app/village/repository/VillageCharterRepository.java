package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCharterEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 村憲章（親）リポジトリ（F17.3・設計書 §13.1.1/§4.5/§7）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断）。標準 {@link JpaRepository} を継承し、
 * 憲章の自動生成（1村1憲章）・悲観ロック直列化に必要なクエリのみ追加する。</p>
 */
public interface VillageCharterRepository extends JpaRepository<VillageCharterEntity, UUID> {

    /** 村の生きている憲章（論理削除除外）。1村1憲章（{@code uk_vc_village}）ゆえ最大1件。 */
    Optional<VillageCharterEntity> findByVillageIdAndDeletedAtIsNull(UUID villageId);

    /**
     * 村の生きている憲章の <b>id だけ</b> をスカラ取得する（悲観ロック直前の同定用・§4.5/§6.3）。
     *
     * <p>{@link #findByIdForUpdate} の直前にエンティティ本体を先読みすると、Hibernate の
     * 一次キャッシュがロック取得後も古い {@code @Version} を保持してしまい、親 charter のバンプ
     * （版付き UPDATE）が {@code WHERE version=旧値} で 0 行更新＝OptimisticLock 失敗になる。
     * よってロック前は id のみをスカラで引き、本体はロック付き読みで初めてロードして最新版を得る。</p>
     */
    @Query("SELECT c.id FROM VillageCharterEntity c WHERE c.villageId = :villageId AND c.deletedAt IS NULL")
    Optional<UUID> findIdByVillageId(@Param("villageId") UUID villageId);

    /**
     * 親 charter 行を悲観ロック（{@code SELECT ... FOR UPDATE}）で取得する（§4.5・§6.3・§7）。
     *
     * <p>全構造変更 EP（{@code POST}/{@code DELETE}/{@code PATCH order}）の先頭でこれを呼び、
     * 「親 charter 行 → 条行」の統一ロック順で採番・再連番を直列化する。これにより並行 append の
     * {@code sort_order} 重複を構造的に閉じ（AC-11b）、{@code DELETE}×{@code PATCH order} の
     * ロック順序逆転デッドロックを封殺する（AC-11d）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM VillageCharterEntity c WHERE c.id = :id")
    Optional<VillageCharterEntity> findByIdForUpdate(@Param("id") UUID id);
}
