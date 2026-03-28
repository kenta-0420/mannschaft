package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.entity.AdRateCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 広告料金カードリポジトリ。
 */
public interface AdRateCardRepository extends JpaRepository<AdRateCardEntity, Long> {

    /**
     * 有効期間内の料金を優先順位で検索する。
     * <p>
     * 優先順位:
     * <ol>
     *   <li>都道府県 + テンプレート指定あり（最も具体的）</li>
     *   <li>都道府県のみ指定あり</li>
     *   <li>テンプレートのみ指定あり</li>
     *   <li>どちらも未指定（デフォルト料金）</li>
     * </ol>
     */
    @Query("""
            SELECT r FROM AdRateCardEntity r
            WHERE r.pricingModel = :pricingModel
              AND r.effectiveFrom <= :date
              AND (r.effectiveUntil IS NULL OR r.effectiveUntil >= :date)
            ORDER BY
              CASE WHEN r.targetPrefecture IS NOT NULL AND r.targetTemplate IS NOT NULL THEN 0
                   WHEN r.targetPrefecture IS NOT NULL AND r.targetTemplate IS NULL THEN 1
                   WHEN r.targetPrefecture IS NULL AND r.targetTemplate IS NOT NULL THEN 2
                   ELSE 3 END ASC,
              r.effectiveFrom DESC
            """)
    List<AdRateCardEntity> findMatchingRates(
            @Param("pricingModel") PricingModel pricingModel,
            @Param("date") LocalDate date);

    /**
     * 現在有効な料金カード一覧を取得する。
     */
    @Query("""
            SELECT r FROM AdRateCardEntity r
            WHERE r.effectiveFrom <= :today
              AND (r.effectiveUntil IS NULL OR r.effectiveUntil >= :today)
            """)
    List<AdRateCardEntity> findCurrentlyEffective(@Param("today") LocalDate today);
}
