package com.mannschaft.app.favorite.repository;

import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.entity.UserFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ユーザー横断お気に入りリポジトリ。
 *
 * <p>user_favorites テーブルのCRUD操作を提供する。
 * organization_id カラムを持たない（ユーザースコープのみ）ため
 * AbstractTenantAwareRepository は使用しない。</p>
 */
public interface UserFavoriteRepository extends JpaRepository<UserFavoriteEntity, UUID> {

    /**
     * ユーザーのお気に入り一覧を表示順で取得する。
     *
     * @param userId ユーザーID
     * @return お気に入り一覧（表示順昇順）
     */
    List<UserFavoriteEntity> findByUserIdOrderByDisplayOrderAsc(Long userId);

    /**
     * 特定のエンティティがお気に入り登録済みか検索する。
     *
     * @param userId     ユーザーID
     * @param entityType エンティティ種別
     * @param entityId   エンティティID
     * @return お気に入りエンティティ（未登録の場合は empty）
     */
    Optional<UserFavoriteEntity> findByUserIdAndEntityTypeAndEntityId(
            Long userId, FavoriteEntityType entityType, String entityId);

    /**
     * ユーザーのお気に入り登録件数を取得する。
     * 上限チェック（20件）に使用する。
     *
     * @param userId ユーザーID
     * @return 登録件数
     */
    long countByUserId(Long userId);

    /**
     * ユーザーの全お気に入りの表示順を1増加させる（先頭に追加する前の空き作成用）。
     *
     * @param userId ユーザーID
     */
    @Modifying
    @Query("UPDATE UserFavoriteEntity f SET f.displayOrder = f.displayOrder + 1 WHERE f.userId = :userId")
    void incrementAllDisplayOrders(Long userId);

    /**
     * ユーザーの全お気に入りを削除する（退会時のバッチ削除用）。
     *
     * @param userId ユーザーID
     */
    @Modifying
    @Query("DELETE FROM UserFavoriteEntity f WHERE f.userId = :userId")
    void deleteAllByUserId(Long userId);
}
