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
