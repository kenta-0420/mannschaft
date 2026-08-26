package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.FileVisibilityRole;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.visibility.FileAttachmentVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 共有ファイルリポジトリ。
 */
public interface SharedFileRepository extends JpaRepository<SharedFileEntity, Long> {

    /**
     * フォルダ内のファイル一覧を取得する。
     */
    List<SharedFileEntity> findByFolderIdOrderByNameAsc(Long folderId);

    /**
     * フォルダ内のファイル一覧をページングで取得する。
     */
    Page<SharedFileEntity> findByFolderIdOrderByNameAsc(Long folderId, Pageable pageable);

    /**
     * フォルダ内のファイル数を取得する。
     */
    long countByFolderId(Long folderId);

    /**
     * B: フォルダ内のファイルのうち、ユーザーが満たす最低可視ロールで<b>クエリ段階</b>絞り込んだ一覧を取得する（非ページング）。
     *
     * <p>{@code min_visible_role} が {@code NULL} のファイルは「フォルダ継承（フォルダ認可を通過した時点で可視）」
     * のため常に含む。非 NULL のファイルは {@code levels}（ユーザーが満たす {@link FileVisibilityRole} 集合）に
     * 含まれる場合のみ返す。取得後の Java フィルタと違いページング総件数がズレないよう SQL 段階で絞る。</p>
     *
     * @param folderId フォルダ ID
     * @param levels   ユーザーが満たす非 NULL レベル集合（<b>空集合を渡してはならない</b>。空のときは
     *                 {@link #findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc} を使う）
     * @return 可視ファイル一覧（name 昇順）
     */
    @Query("SELECT f FROM SharedFileEntity f WHERE f.folderId = :folderId "
            + "AND (f.minVisibleRole IS NULL OR f.minVisibleRole IN :levels) ORDER BY f.name ASC")
    List<SharedFileEntity> findVisibleByFolderIdAndLevels(
            @Param("folderId") Long folderId, @Param("levels") Collection<FileVisibilityRole> levels);

    /**
     * B: {@link #findVisibleByFolderIdAndLevels(Long, Collection)} のページング版。
     *
     * <p>{@code countQuery} を明示し、絞り込み後の総件数・総ページ数がページング整合を保つようにする
     * （取得後 Java フィルタではないことの担保）。{@code levels} には空集合を渡さないこと。</p>
     *
     * @param folderId フォルダ ID
     * @param levels   ユーザーが満たす非 NULL レベル集合（空集合不可）
     * @param pageable ページング情報
     * @return 可視ファイルのページ
     */
    @Query(value = "SELECT f FROM SharedFileEntity f WHERE f.folderId = :folderId "
            + "AND (f.minVisibleRole IS NULL OR f.minVisibleRole IN :levels) ORDER BY f.name ASC",
            countQuery = "SELECT COUNT(f) FROM SharedFileEntity f WHERE f.folderId = :folderId "
            + "AND (f.minVisibleRole IS NULL OR f.minVisibleRole IN :levels)")
    Page<SharedFileEntity> findVisibleByFolderIdAndLevels(
            @Param("folderId") Long folderId, @Param("levels") Collection<FileVisibilityRole> levels, Pageable pageable);

    /**
     * B: ユーザーがどの非 NULL レベルも満たさない場合の絞り込み（{@code min_visible_role IS NULL} のみ返す）。
     *
     * <p>空集合を JPQL の {@code IN} に渡す構文/挙動問題を避けるための専用クエリ（番兵不要）。</p>
     */
    List<SharedFileEntity> findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc(Long folderId);

    /**
     * B: {@link #findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc(Long)} のページング版。
     */
    Page<SharedFileEntity> findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc(Long folderId, Pageable pageable);

    /**
     * F00 共通可視性基盤用 — shared_files と shared_folders をクロスジョインで結合し、
     * 可視性判定に必要な最小限の情報を 1 SQL で射影として取得する。
     *
     * <p>JPA 関連マッピング（{@code @ManyToOne}）が存在しないため JPQL クロスジョイン形式を使用する。</p>
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6。</p>
     *
     * @param ids 取得対象 shared_file の ID 集合（空でない）
     * @return 実存する {@link FileAttachmentVisibilityProjection} の List
     */
    @Query("SELECT new com.mannschaft.app.filesharing.visibility.FileAttachmentVisibilityProjection("
            + "f.id, fol.scopeType, fol.teamId, fol.organizationId, fol.userId) "
            + "FROM SharedFileEntity f, SharedFolderEntity fol "
            + "WHERE f.folderId = fol.id AND f.id IN :ids")
    List<FileAttachmentVisibilityProjection> findVisibilityProjectionsByIdIn(@Param("ids") Collection<Long> ids);
}
