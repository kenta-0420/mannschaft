package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdCampaignModerationLog;
import com.mannschaft.app.advertising.campaign.enums.AdModerationAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 キャンペーン審査ログリポジトリ。保持期間 3 年。
 */
public interface AdCampaignModerationLogRepository
        extends JpaRepository<AdCampaignModerationLog, UUID> {

    /** キャンペーン単位の審査履歴 (新しい順)。 */
    List<AdCampaignModerationLog> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    /** 操作種別別の最新ログ (SYSTEM_ADMIN ダッシュボード用)。 */
    List<AdCampaignModerationLog> findByActionOrderByCreatedAtDesc(AdModerationAction action);
}
