package com.mannschaft.app.incidentbanner.repository;

import com.mannschaft.app.incidentbanner.entity.IncidentBannerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 障害告知バナーリポジトリ。
 *
 * <p>{@code @SQLRestriction("deleted_at IS NULL")} により、
 * 全クエリで論理削除済みレコードは自動除外される。</p>
 */
public interface IncidentBannerRepository extends JpaRepository<IncidentBannerEntity, UUID> {

    /**
     * 一覧取得（管理用・論理削除済みを除く全件）。
     *
     * @param pageable ページング条件
     * @return バナー一覧（ページング）
     */
    Page<IncidentBannerEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 公開中かつ有効期間内のバナーを取得する（ユーザー表示用）。
     *
     * <p>条件:
     * <ul>
     *   <li>published = true</li>
     *   <li>starts_at が NULL または starts_at &lt;= :now</li>
     *   <li>ends_at が NULL または ends_at &gt; :now</li>
     * </ul>
     * {@code @SQLRestriction} により deleted_at IS NULL は自動適用。</p>
     *
     * @param now 現在日時
     * @return 公開中・有効なバナーのリスト（作成日時昇順）
     */
    @Query("SELECT b FROM IncidentBannerEntity b " +
            "WHERE b.published = true " +
            "AND (b.startsAt IS NULL OR b.startsAt <= :now) " +
            "AND (b.endsAt IS NULL OR b.endsAt > :now) " +
            "ORDER BY b.createdAt ASC")
    List<IncidentBannerEntity> findActivePublicBanners(@Param("now") LocalDateTime now);
}
