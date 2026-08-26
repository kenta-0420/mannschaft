package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCharterRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 村憲章の改定履歴リポジトリ（F17.3・設計書 §13.1.4/§8）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断）。append-only の軽量履歴を
 * {@code revised_at} 降順（新しい改定を先頭）で取得する（§8.4）。</p>
 */
public interface VillageCharterRevisionRepository extends JpaRepository<VillageCharterRevisionEntity, UUID> {

    /** charter 配下の改定履歴を {@code revised_at} 降順で取得（新しい順・§8.4）。 */
    List<VillageCharterRevisionEntity> findByCharterIdOrderByRevisedAtDesc(UUID charterId);
}
