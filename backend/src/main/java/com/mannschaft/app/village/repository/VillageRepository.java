package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村本体リポジトリ（F17.1 Phase 1）。
 *
 * <p>原則7 適用外: 全テナント横断ゆえ {@code AbstractTenantAwareRepository} を継承しない。</p>
 */
public interface VillageRepository extends JpaRepository<VillageEntity, UUID> {

    Optional<VillageEntity> findByIdAndDeletedAtIsNullAndArchivedAtIsNull(UUID id);

    boolean existsBySlug(String slug);

    boolean existsByName(String name);

    /**
     * 論理削除されていない村をページネーション取得する（バッチチャンク処理用）。
     *
     * <p>findAll() 無制限取得の代替。CHUNK_SIZE=500 で呼び出すことで
     * 大量データでもヒープを圧迫しない。</p>
     */
    Page<VillageEntity> findByDeletedAtIsNull(Pageable pageable);

    /**
     * 論理削除・凍結どちらもされていない村をページネーション取得する（バッチチャンク処理用）。
     *
     * <p>findAll() 無制限取得の代替。CHUNK_SIZE=500 で呼び出すことで
     * 大量データでもヒープを圧迫しない。</p>
     */
    Page<VillageEntity> findByDeletedAtIsNullAndArchivedAtIsNull(Pageable pageable);

    /**
     * F17.1 Phase 3-β 巡礼推薦バッチ用: 巡礼推薦の候補村 ID を SQL 側の WHERE 句で絞り込んで取得する。
     *
     * <p>{@link com.mannschaft.app.village.batch.VillagePilgrimageBatchService} が全村を
     * {@code findAll()} でロードしユーザーごとにアプリ側でループ判定していた実装
     * （ユーザー数 × 村数のオーダー）を、WHERE 句での絞り込みに置き換えるために追加した。</p>
     *
     * <p>{@code ORDER BY RAND()} は全行に乱数を振ってからソートするため村テーブルが大きくなるほど
     * 致命的に遅くなり、インデックスも効かない。SQL 側ではソートせず絞り込みのみを行い、呼び出し側が
     * 返却された ID 集合（WHERE 句で絞り込み済みのため件数は限られる）からアプリ側で {@code Random} により
     * 1 件選ぶこと（候補数ぶんのメモリしか使わない）。</p>
     *
     * <p>{@code excludeIds} は呼び出し元ユーザーの参加済み／ピン留め済み村 ID 集合（非空必須。
     * MySQL は {@code IN ()} を許容しないため、呼び出し側は必ず 1 件以上を渡すこと）。</p>
     *
     * @param visibility     許可する公開範囲（巡礼対象は {@code PUBLIC} のみ）
     * @param excludeIds     除外する村 ID 集合（参加済み・ピン留め済み。非空）
     * @param categoriesEmpty true の場合カテゴリ絞り込みを行わない（categories は無視される）
     * @param categories     一致させたいカテゴリ集合（categoriesEmpty=false のときのみ使用）
     */
    @Query("SELECT v.id FROM VillageEntity v WHERE v.deletedAt IS NULL AND v.archivedAt IS NULL "
            + "AND v.visibility = :visibility AND v.id NOT IN :excludeIds "
            + "AND (:categoriesEmpty = true OR v.category IN :categories)")
    List<UUID> findPilgrimageCandidateIds(
            @Param("visibility") VillageVisibility visibility,
            @Param("excludeIds") Collection<UUID> excludeIds,
            @Param("categoriesEmpty") boolean categoriesEmpty,
            @Param("categories") Collection<String> categories);
}
