package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillagePilgrimageRecommendationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 巡礼推薦リポジトリ（F17.1 Phase 3-β）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承する。</p>
 */
public interface VillagePilgrimageRecommendationRepository
        extends JpaRepository<VillagePilgrimageRecommendationEntity, UUID> {

    /** 当該日の特定ユーザーへの推薦（UNIQUE 制約と対応）。 */
    Optional<VillagePilgrimageRecommendationEntity> findByUserIdAndRecommendedDate(Long userId, LocalDate recommendedDate);

    /** 自分の巡礼履歴（推薦日降順）。 */
    Page<VillagePilgrimageRecommendationEntity> findByUserIdOrderByRecommendedDateDesc(Long userId, Pageable pageable);

    /** バッチ重複生成チェック用: 特定日にすでに推薦行があるか。 */
    boolean existsByUserIdAndRecommendedDate(Long userId, LocalDate recommendedDate);
}
