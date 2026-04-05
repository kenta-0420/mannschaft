package com.mannschaft.app.gallery.repository;

import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 写真アルバムリポジトリ。
 */
public interface PhotoAlbumRepository extends JpaRepository<PhotoAlbumEntity, Long> {

    /**
     * チーム別アルバム一覧をイベント日降順で取得する。
     */
    Page<PhotoAlbumEntity> findByTeamIdOrderByEventDateDesc(Long teamId, Pageable pageable);

    /**
     * 組織別アルバム一覧をイベント日降順で取得する。
     */
    Page<PhotoAlbumEntity> findByOrganizationIdOrderByEventDateDesc(Long organizationId, Pageable pageable);

    /**
     * チーム別アルバムをタイトル部分一致で検索する。
     */
    Page<PhotoAlbumEntity> findByTeamIdAndTitleContainingOrderByEventDateDesc(
            Long teamId, String title, Pageable pageable);

    /**
     * 組織別アルバムをタイトル部分一致で検索する。
     */
    Page<PhotoAlbumEntity> findByOrganizationIdAndTitleContainingOrderByEventDateDesc(
            Long organizationId, String title, Pageable pageable);

    /**
     * チーム別の全アルバムを取得する（バッチ処理用）。
     */
    List<PhotoAlbumEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * 組織別の全アルバムを取得する（バッチ処理用）。
     */
    List<PhotoAlbumEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /**
     * チーム/組織の合計写真数を取得する（ストレージクォータ確認用）。
     */
    @Query("SELECT COALESCE(SUM(a.photoCount), 0) FROM PhotoAlbumEntity a WHERE a.teamId = :teamId")
    int sumPhotoCountByTeamId(@Param("teamId") Long teamId);

    /**
     * 組織の合計写真数を取得する（ストレージクォータ確認用）。
     */
    @Query("SELECT COALESCE(SUM(a.photoCount), 0) FROM PhotoAlbumEntity a WHERE a.organizationId = :organizationId")
    int sumPhotoCountByOrganizationId(@Param("organizationId") Long organizationId);
}
