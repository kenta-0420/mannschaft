package com.mannschaft.app.gallery.repository;

import com.mannschaft.app.gallery.entity.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 写真リポジトリ。
 */
public interface PhotoRepository extends JpaRepository<PhotoEntity, Long> {

    /**
     * アルバム内写真を表示順で取得する。
     */
    Page<PhotoEntity> findByAlbumIdOrderBySortOrder(Long albumId, Pageable pageable);

    /**
     * アルバム内写真を撮影日時順で取得する。
     */
    Page<PhotoEntity> findByAlbumIdOrderByTakenAtDesc(Long albumId, Pageable pageable);

    /**
     * アルバム内の全写真を取得する（一括ダウンロード用）。
     */
    List<PhotoEntity> findByAlbumIdOrderBySortOrder(Long albumId);

    /**
     * アップロードユーザー別一覧を取得する。
     */
    List<PhotoEntity> findByUploadedByOrderByCreatedAtDesc(Long uploadedBy);

    /**
     * アルバムの合計ファイルサイズを取得する（ストレージクォータ確認用）。
     */
    @Query("SELECT COALESCE(SUM(p.fileSize), 0) FROM PhotoEntity p " +
            "JOIN PhotoAlbumEntity a ON p.albumId = a.id WHERE a.teamId = :teamId")
    long sumFileSizeByTeamId(@Param("teamId") Long teamId);

    /**
     * 組織の合計ファイルサイズを取得する。
     */
    @Query("SELECT COALESCE(SUM(p.fileSize), 0) FROM PhotoEntity p " +
            "JOIN PhotoAlbumEntity a ON p.albumId = a.id WHERE a.organizationId = :organizationId")
    long sumFileSizeByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * アルバムIDで全写真を削除する。
     */
    void deleteByAlbumId(Long albumId);
}
