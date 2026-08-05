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

    Optional<VillageEntity> findBySlugAndDeletedAtIsNullAndArchivedAtIsNull(String slug);

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
     * F17.1 Phase 3-β 巡礼推薦バッチ用: 巡礼推薦の候補村を SQL 側で絞り込んでランダム順に取得する。
     *
     * <p>{@link com.mannschaft.app.village.batch.VillagePilgrimageBatchService} が全村を
     * {@code findAll()} でロードしユーザーごとにアプリ側でループ判定していた実装
     * （ユーザー数 × 村数のオーダー）を、WHERE 句での絞り込み＋DB 側ランダム順に置き換えるために追加した。
     * 呼び出し側は {@code pageable}（例: {@code PageRequest.of(0, 1)}）で必要件数だけ取得すること。</p>
     *
     * <p>{@code excludeIds} は呼び出し元ユーザーの参加済み／ピン留め済み村 ID 集合（非空必須。
     * MySQL は {@code IN ()} を許容しないため、呼び出し側は必ず 1 件以上を渡すこと）。</p>
     *
     * @param visibility     許可する公開範囲（巡礼対象は {@code PUBLIC} のみ）
     * @param excludeIds     除外する村 ID 集合（参加済み・ピン留め済み。非空）
     * @param categoriesEmpty true の場合カテゴリ絞り込みを行わない（categories は無視される）
     * @param categories     一致させたいカテゴリ集合（categoriesEmpty=false のときのみ使用）
     * @param pageable       取得件数の上限（ソートは本クエリの {@code ORDER BY RAND()} が優先される）
     */
    @Query("SELECT v FROM VillageEntity v WHERE v.deletedAt IS NULL AND v.archivedAt IS NULL "
            + "AND v.visibility = :visibility AND v.id NOT IN :excludeIds "
            + "AND (:categoriesEmpty = true OR v.category IN :categories) "
            + "ORDER BY FUNCTION('RAND')")
    List<VillageEntity> findPilgrimageCandidatesRandomOrder(
            @Param("visibility") VillageVisibility visibility,
            @Param("excludeIds") Collection<UUID> excludeIds,
            @Param("categoriesEmpty") boolean categoriesEmpty,
            @Param("categories") Collection<String> categories,
            Pageable pageable);
}
