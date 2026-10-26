package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.residencestatus.entity.AnnualReviewResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.16 年次更新キャンペーン回答 リポジトリ。
 */
public interface AnnualReviewResponseRepository
        extends AbstractTenantAwareRepository<AnnualReviewResponse, UUID> {

    /** キャンペーン × 居住者の回答を取得（UPSERT 用・一意制約あり）。 */
    Optional<AnnualReviewResponse> findByAnnualReviewIdAndResidentRegistryIdAndDeletedAtIsNull(
            UUID annualReviewId, Long residentRegistryId);

    /** キャンペーンの全回答を取得（管理ダッシュボード用）。 */
    List<AnnualReviewResponse> findByAnnualReviewIdAndDeletedAtIsNull(UUID annualReviewId);

    /** キャンペーン × 居住実態状態で絞り込み（集計・抽出用）。 */
    List<AnnualReviewResponse> findByAnnualReviewIdAndResidenceStateAndDeletedAtIsNull(
            UUID annualReviewId, String residenceState);

    /** 居住者の回答履歴（直近順）。 */
    List<AnnualReviewResponse> findByResidentRegistryIdAndDeletedAtIsNullOrderByRespondedAtDesc(
            Long residentRegistryId);
}
