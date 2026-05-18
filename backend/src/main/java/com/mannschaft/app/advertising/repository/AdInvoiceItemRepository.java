package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 広告請求書明細リポジトリ。
 */
public interface AdInvoiceItemRepository extends JpaRepository<AdInvoiceItemEntity, Long> {

    /**
     * 請求書IDで明細を取得する。
     */
    List<AdInvoiceItemEntity> findByInvoiceId(Long invoiceId);

    /**
     * 請求書IDで明細を削除する（DRAFT再生成時に使用）。
     */
    void deleteByInvoiceId(Long invoiceId);

    /**
     * F09.17 由来課金行の冪等チェック用。
     * 同一 messaging_campaign_id × channel_type × month_key の重複登録を防ぐ。
     */
    Optional<AdInvoiceItemEntity> findByMessagingCampaignIdAndChannelTypeAndMonthKey(
            UUID messagingCampaignId, String channelType, String monthKey);

    /**
     * messaging_campaign_id 単位の課金行集計用 (consumed_budget_yen 反映向け)。
     */
    List<AdInvoiceItemEntity> findByMessagingCampaignId(UUID messagingCampaignId);
}
