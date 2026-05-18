package com.mannschaft.app.advertising.campaign.event;

import java.time.YearMonth;
import java.util.UUID;

/**
 * F09.17 Phase 11-b ε-C — 月次課金ブリッジ完了時に発行する消費予算更新イベント。
 *
 * <p>{@code AdMessagingBillingBridge} が前月の {@code ad_*_deliveries} を集計し、
 * {@code ad_invoice_items} に積み上げた後、キャンペーン単位で本イベントを発行する。
 * F09.11 ダッシュボードや credit_limit リアクティブ判定が消費する想定。</p>
 *
 * <p>同一 (campaignId, month) で複数回発火しないよう、ブリッジ側で冪等性を保証する
 * (UNIQUE 制約 + UPDATE 一回限り)。</p>
 *
 * @param campaignId           ad_messaging_campaigns.id
 * @param advertiserAccountId  ad_messaging_campaigns.advertiser_account_id (クロスドメイン参照)
 * @param consumedBudgetYen    今回の月次集計でこのキャンペーンに加算された金額 (税抜・円)
 * @param totalConsumedBudgetYen 集計後のキャンペーン累計 consumed_budget_yen
 * @param totalBudgetYen       キャンペーン total_budget_yen (上限)
 * @param month                集計対象月 (前月)
 */
public record MessagingCampaignBudgetConsumedEvent(
        UUID campaignId,
        Long advertiserAccountId,
        long consumedBudgetYen,
        long totalConsumedBudgetYen,
        long totalBudgetYen,
        YearMonth month
) {
}
