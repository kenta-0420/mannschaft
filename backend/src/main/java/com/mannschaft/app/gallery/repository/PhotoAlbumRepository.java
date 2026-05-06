package com.mannschaft.app.gallery.repository;

import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.visibility.PhotoAlbumVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * F00 Phase D-β — 指定 ID 群のアルバムから可視性判定に必要な Projection を 1 SQL で取得する。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / IDOR 防止 §11.3。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みの行は取得段階で除外され、
     * 取得不可 ID は自動的に fail-closed（不存在）として扱われる。</p>
     *
     * @param ids 取得対象 photo_album_id 集合（空でない、{@code null} ではない）
     * @return 実存するアルバムの Projection リスト（空でも null 不可）
     */
    @Query("SELECT new com.mannschaft.app.gallery.visibility.PhotoAlbumVisibilityProjection("
            + "a.id, a.teamId, a.organizationId, a.createdBy, a.visibility) "
            + "FROM PhotoAlbumEntity a WHERE a.id IN :ids")
    List<PhotoAlbumVisibilityProjection> findVisibilityProjectionsByIdIn(@Param("ids") Collection<Long> ids);
}
