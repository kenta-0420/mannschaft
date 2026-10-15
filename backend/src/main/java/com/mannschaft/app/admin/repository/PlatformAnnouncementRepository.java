package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * プラットフォームお知らせリポジトリ。
 */
public interface PlatformAnnouncementRepository extends JpaRepository<PlatformAnnouncementEntity, Long> {

    /**
     * 公開済みかつ有効期限内のお知らせを取得する。
     */
    @Query("SELECT a FROM PlatformAnnouncementEntity a " +
           "WHERE a.publishedAt IS NOT NULL AND a.publishedAt <= :now " +
           "AND (a.expiresAt IS NULL OR a.expiresAt > :now) " +
           "ORDER BY a.isPinned DESC, a.publishedAt DESC")
    List<PlatformAnnouncementEntity> findActiveAnnouncements(@Param("now") LocalDateTime now);

    /**
     * 全お知らせをページネーション付きで取得する（管理者向け）。
     */
    Page<PlatformAnnouncementEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
