package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentDistributionTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F03.11 募集型予約: 配信対象リポジトリ。
 * Phase 2 の publish 処理で使用する。
 */
public interface RecruitmentDistributionTargetRepository
        extends JpaRepository<RecruitmentDistributionTargetEntity, Long> {

    /**
     * 募集に設定された配信対象を取得する。
     *
     * @param listingId 募集ID
     * @return 配信対象リスト
     */
    List<RecruitmentDistributionTargetEntity> findByListingId(Long listingId);

    /**
     * 指定募集の配信対象件数を返す。
     * publish 時の RECRUITMENT_204 チェックに使用する。
     *
     * @param listingId 募集ID
     * @return 配信対象件数
     */
    int countByListingId(Long listingId);

    /**
     * 募集の配信対象を全削除する (再設定時に使用)。
     *
     * @param listingId 募集ID
     */
    void deleteByListingId(Long listingId);
}
