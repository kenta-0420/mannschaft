package com.mannschaft.app.scopefolder.repository;

import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * マイスコープフォルダアイテムリポジトリ。
 */
public interface MyScopeFolderItemRepository extends JpaRepository<MyScopeFolderItemEntity, Long> {

    /**
     * フォルダ内のアイテム一覧を並び順で取得する。
     */
    List<MyScopeFolderItemEntity> findByFolderIdOrderBySortOrder(Long folderId);

    /**
     * フォルダID一覧からアイテムを一括取得する（フォルダ一覧API用・N+1回避）。
     */
    List<MyScopeFolderItemEntity> findByFolderIdIn(List<Long> folderIds);

    /**
     * フォルダIDとスコープIDでアイテムを取得する。
     */
    Optional<MyScopeFolderItemEntity> findByFolderIdAndScopeId(Long folderId, Long scopeId);

    /**
     * フォルダIDとスコープIDの組み合わせが存在するか確認する。
     */
    boolean existsByFolderIdAndScopeId(Long folderId, Long scopeId);

    /**
     * ユーザーの全フォルダ（指定スコープタイプ）からscopeIdを検索する（1アイテム1フォルダ制約用）。
     */
    @Query("SELECT i FROM MyScopeFolderItemEntity i JOIN MyScopeFolderEntity f ON i.folderId = f.id " +
           "WHERE f.userId = :userId AND f.scopeType = :scopeType AND i.scopeId = :scopeId AND f.deletedAt IS NULL")
    Optional<MyScopeFolderItemEntity> findByUserAndScopeTypeAndScopeId(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * フォルダのアイテムを全削除する（フォルダ削除時など）。
     */
    void deleteByFolderId(Long folderId);

    /**
     * 指定ユーザー × scope_type × scope_id のアイテムを全件取得する。
     * F15.3 §9.6: MembershipEndedEvent リスナーで物理削除に使用。
     */
    @Query("SELECT i FROM MyScopeFolderItemEntity i JOIN MyScopeFolderEntity f ON i.folderId = f.id " +
            "WHERE f.userId = :userId AND f.scopeType = :scopeType AND i.scopeId = :scopeId AND f.deletedAt IS NULL")
    List<MyScopeFolderItemEntity> findAllByUserAndScope(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定 scope_type × scope_id のアイテムを全ユーザー分取得する。
     * F15.3 §9.5: TeamDeletedEvent / OrganizationDeletedEvent リスナーで物理削除に使用。
     */
    @Query("SELECT i FROM MyScopeFolderItemEntity i JOIN MyScopeFolderEntity f ON i.folderId = f.id " +
            "WHERE f.scopeType = :scopeType AND i.scopeId = :scopeId")
    List<MyScopeFolderItemEntity> findAllByScope(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定ユーザーの全アイテムを物理削除する（GDPR 退会時など）。
     * F15.3 §9.4 (UserAnonymizedEvent リスナー想定。本フェーズではフック設置のみ)。
     */
    @Modifying
    @Query("DELETE FROM MyScopeFolderItemEntity i WHERE i.folderId IN " +
            "(SELECT f.id FROM MyScopeFolderEntity f WHERE f.userId = :userId)")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * フォルダ別未読通知件数を集計する（N+1 防止・1 クエリ）。
     *
     * <p>設計書 F15.3 §6.4 の集計クエリを実装。notifications テーブルを LEFT JOIN し、
     * 各フォルダの itemScopeIds に対応する未読通知数を返す。
     * 未読 0 件のフォルダも結果に含む（COUNT(n.id) が 0 で返る）。</p>
     *
     * <p>クロスドメイン参照だが読み取り専用 (@Transactional(readOnly=true)) で
     * scopefolder ドメインに閉じる（設計書 §6.4 / §12.2）。</p>
     *
     * @param userId    対象ユーザー
     * @param scopeType 対象スコープ種別
     * @return [folderId, unreadCount] の配列リスト
     */
    @Query(value = "SELECT folder.id AS folder_id, COALESCE(COUNT(n.id), 0) AS unread_count "
            + "FROM my_scope_folders folder "
            + "LEFT JOIN my_scope_folder_items item ON item.folder_id = folder.id "
            + "LEFT JOIN notifications n "
            + "  ON CAST(n.scope_id AS UNSIGNED) = item.scope_id "
            + "  AND n.scope_type = folder.scope_type "
            + "  AND n.user_id = :userId "
            + "  AND n.is_read = FALSE "
            + "WHERE folder.user_id = :userId "
            + "  AND folder.scope_type = :scopeType "
            + "  AND folder.deleted_at IS NULL "
            + "GROUP BY folder.id",
            nativeQuery = true)
    List<Object[]> aggregateFolderUnreadCounts(
            @Param("userId") Long userId,
            @Param("scopeType") String scopeType);
}
