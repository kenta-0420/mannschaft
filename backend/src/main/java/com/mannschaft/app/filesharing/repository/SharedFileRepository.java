package com.mannschaft.app.filesharing.repository;

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
