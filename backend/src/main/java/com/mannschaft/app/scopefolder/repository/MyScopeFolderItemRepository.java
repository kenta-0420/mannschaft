package com.mannschaft.app.scopefolder.repository;

import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.ScopeType;
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
     * <p><b>【issue #2545】JOIN 条件の {@code CAST(n.scope_id AS UNSIGNED)} を撤去した。</b>
     * 本番 DDL では {@code notifications.scope_id} が {@code BIGINT UNSIGNED}（V4.019）、
     * {@code my_scope_folder_items.scope_id} が符号付き {@code BIGINT}（V9.101）である。
     * つまりこの CAST は<b>既に符号なしの列を符号なしへ変換する no-op</b>だった。
     * {@code ddl-auto=create} のテスト環境では両側とも符号付きになるため、
     * この歪みは従来のテストでは原理的に観測できなかった。</p>
     *
     * <p>撤去の根拠は Flyway 実スキーマ（＝本番同一の符号性）上での実測である
     * （{@code NativeQueryUnsignedBigintTypeIT}）。本クエリ<b>そのもの</b>を CAST 有無で走らせて
     * 同一結果になることを確認しており、テストは SQL をリポジトリの {@code @Query} から反射で取得するため
     * 本 javadoc とクエリ本文がずれても追随する。
     * ID は AUTO_INCREMENT の非負値であり、MySQL の符号付き↔符号なし比較は非負域で厳密に一致する。</p>
     *
     * <p><b>撤去の便益（EXPLAIN の実測値・断定ではなく観測事実）</b>:
     * 同 IT の {@code EXPLAIN} 比較で {@code notifications}（別名 {@code n}）の {@code key} は
     * CAST 有りで {@code null}（索引未使用）、撤去後で {@code idx_notifications_scope} だった。
     * インデックス列に関数が乗ると sargable でなくなるためであり、
     * 撤去によって当該索引が選ばれるようになったことを測定で確認している
     * （測定条件: MySQL 8.0 / 通知 501 件 + {@code ANALYZE TABLE} 後。
     * 行数分布が変われば optimizer の選択は変わりうるので、本記述は当該条件下の観測である）。</p>
     *
     * <p>なお根本原因は「同じ意味の {@code scope_id} が表ごとに符号性が違う」というスキーマの不統一である。
     * {@code my_scope_folder_items.scope_id} を {@code BIGINT UNSIGNED} へ揃える migration が
     * 本来の根治だが、DDL 変更は別途承認が要るため本 PR では扱わない。</p>
     *
     * <p><b>照合順序不一致（issue #2589）は是正済み</b>:
     * かつて {@code notifications} は {@code utf8mb4_unicode_ci} を明示宣言（V4.019）する一方
     * {@code my_scope_folders} はサーバ既定に従い（V9.100）、本番 RDS のサーバ既定が
     * {@code utf8mb4_0900_ai_ci} であったため、{@code n.scope_type = folder.scope_type} が
     * <b>本番でのみ {@code Illegal mix of collations} で失敗</b>していた。
     * {@code V175.20260804134628__unify_table_collation.sql} がスキーマ全体を
     * {@code utf8mb4_0900_ai_ci} へ統一し、あわせて {@code ALTER DATABASE} でデータベース既定を
     * 固定したため、表の照合順序はサーバ変数 {@code collation_server} に依存しなくなった。
     * したがって本クエリに {@code COLLATE} を書く必要は無い。
     * 統一が維持されていることは {@code SchemaCollationConsistencyIT} が
     * 本番と同じ照合順序で起動したコンテナ上で全表・全文字列列について検証している。</p>
     *
     * @param userId    対象ユーザー
     * @param scopeType 対象スコープ種別
     * @return [folderId, unreadCount] の配列リスト
     */
    @Query(value = "SELECT folder.id AS folder_id, COALESCE(COUNT(n.id), 0) AS unread_count "
            + "FROM my_scope_folders folder "
            + "LEFT JOIN my_scope_folder_items item ON item.folder_id = folder.id "
            + "LEFT JOIN notifications n "
            + "  ON n.scope_id = item.scope_id "
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
