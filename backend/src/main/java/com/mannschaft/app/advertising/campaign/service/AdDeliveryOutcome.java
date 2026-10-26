package com.mannschaft.app.advertising.campaign.service;

/**
 * F09.17 Phase 11-c {@link AdCampaignDeliveryDispatcher#deliverForUser} の結果種別。
 *
 * <p>スキップ理由を区別できるようにし、特に {@link #SKIPPED_ALREADY_CLAIMED}（DB claim 競合による
 * スキップ＝claim-then-act が正しく機能した証跡）の件数を {@link AdCampaignDeliveryWorker} が
 * 集計してログへ可視化できるようにする。</p>
 */
public enum AdDeliveryOutcome {
    /** 1 件以上のチャネルへ実配信できた。 */
    DELIVERED,
    /** 広告主ブロック中 / FreqCap 超過 / プリファレンス全 opt-out など通常のスキップ。 */
    SKIPPED,
    /** DB claim が既に他の実行に確保されていたためスキップ（claim-then-act の正常系）。 */
    SKIPPED_ALREADY_CLAIMED,
    /** Valkey 接続異常等で FreqCap を数えられず、fail-closed でスキップ。 */
    SKIPPED_FREQ_CAP_UNAVAILABLE
}
