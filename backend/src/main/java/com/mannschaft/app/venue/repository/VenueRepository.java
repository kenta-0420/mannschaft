package com.mannschaft.app.venue.repository;

import com.mannschaft.app.venue.entity.VenueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 施設マスタリポジトリ。
 */
public interface VenueRepository extends JpaRepository<VenueEntity, Long> {

    Optional<VenueEntity> findByGooglePlaceId(String googlePlaceId);

    /**
     * 施設名の前方一致でDBキャッシュから候補を検索する（利用回数順）。
     */
    @Query("""
            SELECT v FROM VenueEntity v
            WHERE v.name LIKE CONCAT(:keyword, '%')
               OR v.name LIKE CONCAT('%', :keyword, '%')
            ORDER BY v.usageCount DESC
            """)
    List<VenueEntity> searchByKeyword(String keyword);

    /**
     * 都道府県・市区町村で絞り込んだ施設を取得する。
     */
    List<VenueEntity> findByPrefectureAndCityOrderByUsageCountDesc(String prefecture, String city);

    /**
     * 利用回数をインクリメントする。
     */
    @Modifying
    @Query("UPDATE VenueEntity v SET v.usageCount = v.usageCount + 1 WHERE v.id = :venueId")
    void incrementUsageCount(Long venueId);
}
