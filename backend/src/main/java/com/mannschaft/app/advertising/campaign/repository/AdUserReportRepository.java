package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdUserReport;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 ユーザー通報リポジトリ。
 * 通報 3 件で自動 SUSPEND 判定に使用する {@link #countByCampaignId(UUID)} が中核。
 */
public interface AdUserReportRepository extends JpaRepository<AdUserReport, UUID> {

    /** 自動 SUSPEND 判定用 (3 件閾値)。 */
    long countByCampaignId(UUID campaignId);

    /** キャンペーン単位の通報一覧 (新しい順)。 */
    List<AdUserReport> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    /** SYSTEM_ADMIN レビューキュー: 状態別。 */
    List<AdUserReport> findByStatusOrderByCreatedAtAsc(AdReportStatus status);

    /** 退会時匿名化用: reporter_user_id を NULL 化する対象を取得。 */
    List<AdUserReport> findByReporterUserId(Long reporterUserId);
}
