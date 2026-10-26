package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdUserReport;
import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * F09.17 / F09.19.9 ユーザー通報リポジトリ。
 *
 * <p>通報 3 件で自動停止判定に使う件数集計（{@code NEW / REVIEWING} のみが対象）と、
 * SYSTEM_ADMIN 通報一覧（status / reasonCode フィルタ + ページング）を提供する。</p>
 */
public interface AdUserReportRepository extends JpaRepository<AdUserReport, UUID> {

    /** キャンペーン単位の通報一覧 (新しい順)。 */
    List<AdUserReport> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    /** SYSTEM_ADMIN レビューキュー: 状態別。 */
    List<AdUserReport> findByStatusOrderByCreatedAtAsc(AdReportStatus status);

    /** 退会時匿名化用: reporter_user_id を NULL 化する対象を取得。 */
    List<AdUserReport> findByReporterUserId(Long reporterUserId);

    // ─── F09.19.9 自動停止判定（NEW / REVIEWING のみカウント。RESOLVED / DISMISSED は除外） ───

    /** メッセージ型キャンペーンの未処理通報件数（自動 BLOCK 判定用）。 */
    long countByCampaignIdAndStatusIn(UUID campaignId, Collection<AdReportStatus> statuses);

    /** 運用型キャンペーンの未処理通報件数（自動停止判定用）。 */
    long countByOperationalCampaignIdAndStatusIn(Long operationalCampaignId, Collection<AdReportStatus> statuses);

    // ─── F09.19.9 SYSTEM_ADMIN 通報一覧（status / reasonCode 任意フィルタ + ページング） ───

    /**
     * status / reasonCode を任意で絞り込んだ通報一覧を新しい順で取得する。
     * どちらのパラメータも {@code null} 可（null の場合はその条件を無視する）。
     */
    @Query("SELECT r FROM AdUserReport r "
            + "WHERE (:status IS NULL OR r.status = :status) "
            + "AND (:reasonCode IS NULL OR r.reasonCode = :reasonCode) "
            + "ORDER BY r.createdAt DESC")
    Page<AdUserReport> searchForAdmin(
            @Param("status") AdReportStatus status,
            @Param("reasonCode") AdReportReasonCode reasonCode,
            Pageable pageable);
}
