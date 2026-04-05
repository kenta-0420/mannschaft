package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.entity.TemplateWallpaperEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * テンプレート壁紙リポジトリ。
 */
public interface TemplateWallpaperRepository extends JpaRepository<TemplateWallpaperEntity, Long> {

    @Query("""
            SELECT tw FROM TemplateWallpaperEntity tw
            WHERE tw.isActive = true
              AND (tw.templateSlug = :slug OR tw.templateSlug = '*')
            ORDER BY tw.templateSlug DESC, tw.sortOrder ASC
            """)
    List<TemplateWallpaperEntity> findAvailableBySlug(@Param("slug") String slug);

    List<TemplateWallpaperEntity> findAllByOrderByTemplateSlugAscSortOrderAsc();
}
