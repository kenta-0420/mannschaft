package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.residencestatus.entity.AnnualReview;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.16 年次居住実態更新キャンペーン リポジトリ。
 */
public interface AnnualReviewRepository extends AbstractTenantAwareRepository<AnnualReview, UUID> {

    /** 組織 × 年度の一意な年次キャンペーンを取得。 */
    Optional<AnnualReview> findByOrganizationIdAndReviewYearAndDeletedAtIsNull(
            Long organizationId, Integer reviewYear);

    /** 締切バッチ用: 締切日時を過ぎていて未クローズのキャンペーン一覧。 */
    List<AnnualReview> findByDeadlineAtLessThanEqualAndClosedAtIsNullAndDeletedAtIsNull(LocalDateTime threshold);
}
