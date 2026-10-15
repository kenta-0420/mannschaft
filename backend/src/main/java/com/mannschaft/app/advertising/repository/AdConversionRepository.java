package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdConversionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 広告コンバージョンリポジトリ。
 */
public interface AdConversionRepository extends JpaRepository<AdConversionEntity, Long> {

    /**
     * キャンペーンIDと期間でコンバージョンを取得する。
     */
    List<AdConversionEntity> findByCampaignIdAndConvertedAtBetween(Long campaignId, LocalDateTime from, LocalDateTime to);

    /**
     * キャンペーンIDでコンバージョン数をカウントする。
     */
    long countByCampaignId(Long campaignId);
}
