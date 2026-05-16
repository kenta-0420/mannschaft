package com.mannschaft.app.village.entity.enums;

/**
 * 村ニュースレターの配信頻度（F17.1 Phase 3-β-E）。
 *
 * <p>WEEKLY / MONTHLY の 2 種類のみ。週次は金曜 18:00、月次は月末日 18:00 に
 * {@code VillageNewsletterDispatchBatchService} が配信を行う。</p>
 */
public enum VillageNewsletterFrequency {

    /** 週次（毎週金曜 18:00 配信）。 */
    WEEKLY,

    /** 月次（月末日 18:00 配信）。 */
    MONTHLY
}
