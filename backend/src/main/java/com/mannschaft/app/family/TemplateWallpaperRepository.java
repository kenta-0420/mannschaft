package com.mannschaft.app.family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * テンプレート壁紙リポジトリ。
 */
public interface TemplateWallpaperRepository extends JpaRepository<TemplateWallpaperEntity, Long> {

    /**
     * テンプレート別 + 共通壁紙の一覧を取得する。
     */
    @Query("""
            SELECT tw FROM TemplateWallpaperEntity tw
            WHERE tw.isActive = true
              AND (tw.templateSlug = :slug OR tw.templateSlug = '*')
            ORDER BY tw.templateSlug DESC, tw.sortOrder ASC
            """)
    List<TemplateWallpaperEntity> findAvailableBySlug(@Param("slug") String slug);

    /**
     * 管理画面用：全壁紙を取得する。
     */
    List<TemplateWallpaperEntity> findAllByOrderByTemplateSlugAscSortOrderAsc();
}
