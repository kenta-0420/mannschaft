package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アフィリエイト広告設定リポジトリ。
 */
public interface AffiliateConfigRepository extends JpaRepository<AffiliateConfigEntity, Long> {

    /**
     * 全件ページネーション取得（SYSTEM_ADMIN用）。
     */
    Page<AffiliateConfigEntity> findAllByOrderByDisplayPriorityAsc(Pageable pageable);

    /**
     * 現在有効な広告一覧を取得する。
     */
    @Query("""
            SELECT a FROM AffiliateConfigEntity a
            WHERE a.isActive = true
              AND (a.activeFrom IS NULL OR a.activeFrom <= :now)
              AND (a.activeUntil IS NULL OR a.activeUntil >= :now)
            ORDER BY a.placement ASC, a.displayPriority ASC
            """)
    List<AffiliateConfigEntity> findActiveAds(LocalDateTime now);

    /**
     * ユーザー属性に基づいてターゲティングされた有効広告一覧を取得する。
     * 各ターゲティングカラムがNULLの広告は全対象として扱う。
     */
    @Query("""
            SELECT a FROM AffiliateConfigEntity a
            WHERE a.isActive = true
              AND (a.activeFrom IS NULL OR a.activeFrom <= :now)
              AND (a.activeUntil IS NULL OR a.activeUntil >= :now)
              AND (a.targetTemplate IS NULL OR a.targetTemplate = :template)
              AND (a.targetPrefecture IS NULL OR a.targetPrefecture = :prefecture)
              AND (a.targetLocale IS NULL OR a.targetLocale = :locale)
            ORDER BY a.placement ASC, a.displayPriority ASC
            """)
    List<AffiliateConfigEntity> findTargetedAds(
            LocalDateTime now, String template, String prefecture, String locale);
}
