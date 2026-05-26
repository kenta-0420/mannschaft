package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinArchiveFolderEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 掲示板 保管庫フォルダリポジトリ（設計書 F05.1 §3/§5）。
 *
 * <p>主キーは UUIDv7。{@code AbstractTenantAwareRepository} は継承しない（bulletin 慣習・
 * {@code scope_type}/{@code scope_id} 明示引数の multi-scope 方式に揃える。設計書 §3 備考）。</p>
 *
 * <p>論理削除（{@code deleted_at}）は Entity の {@code @SQLRestriction} で透過フィルタされるため、
 * JPQL/メソッド名クエリには明示の {@code deletedAt IS NULL} は不要。ただし悲観ロック付き
 * クエリ（{@code @Lock} + JPQL）も同じ Entity マッピングを通すため {@code @SQLRestriction} が効く。</p>
 */
public interface BulletinArchiveFolderRepository
        extends JpaRepository<BulletinArchiveFolderEntity, UUID> {

    /**
     * スコープの全フォルダを取得する（ツリー構築の主クエリ・1 クエリ）。
     * display_order 昇順で並べ、メモリ上で親子ネストを組み立てる（N+1 回避）。
     */
    List<BulletinArchiveFolderEntity> findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
            ScopeType scopeType, Long scopeId);

    /**
     * 親フォルダ別の子フォルダ一覧（移動・削除時のサブツリー取得）。
     */
    List<BulletinArchiveFolderEntity> findByParentFolderIdOrderByDisplayOrderAsc(UUID parentFolderId);

    /**
     * ID + スコープでフォルダを取得する（scope 越境防止）。
     */
    Optional<BulletinArchiveFolderEntity> findByIdAndScopeTypeAndScopeId(
            UUID id, ScopeType scopeType, Long scopeId);

    /**
     * 悲観ロック（SELECT ... FOR UPDATE）付きでフォルダを取得する。
     * 移動・削除・更新の前に対象行をロックし、複数管理者の同時操作レースを防ぐ（設計書 §5 並行性制御）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM BulletinArchiveFolderEntity f WHERE f.id = :id")
    Optional<BulletinArchiveFolderEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * スコープの全フォルダを悲観ロック付きで取得する（移動・削除時にサブツリーを安全に展開するため）。
     * 深さ上限が浅い（最大5階層）ためスコープ単位ロックで十分かつ確実。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM BulletinArchiveFolderEntity f "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId "
            + "ORDER BY f.displayOrder ASC")
    List<BulletinArchiveFolderEntity> findByScopeForUpdate(
            @Param("scopeType") ScopeType scopeType, @Param("scopeId") Long scopeId);

    /**
     * スコープのアクティブフォルダ件数を悲観ロック付きでカウントする（上限 200 判定・同時すり抜け防止）。
     * 設計書 §5: フォルダ作成時の上限チェックは同一スコープ行をロックして計数する。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT COUNT(f) FROM BulletinArchiveFolderEntity f "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId")
    long countByScopeForUpdate(
            @Param("scopeType") ScopeType scopeType, @Param("scopeId") Long scopeId);

    /**
     * スコープのアクティブフォルダ総数（ロックなし・ツリーメタ表示用）。
     */
    long countByScopeTypeAndScopeId(ScopeType scopeType, Long scopeId);

    /**
     * 同一親内の最大 display_order を取得する（作成時の末尾採番用）。NULL なら -1 相当として扱う。
     */
    @Query("SELECT COALESCE(MAX(f.displayOrder), -1) FROM BulletinArchiveFolderEntity f "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId "
            + "AND ((:parentFolderId IS NULL AND f.parentFolderId IS NULL) "
            + "     OR f.parentFolderId = :parentFolderId)")
    int findMaxDisplayOrder(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("parentFolderId") UUID parentFolderId);
}
