package com.mannschaft.app.advertising.campaign.service.moderation;

/**
 * F09.17 Phase 11-b {@code AdContentModerator} の判定結果サマリ。
 *
 * <ul>
 *   <li>{@code AUTO_PASS}: NG ワード検知ゼロ。{@code moderation_status} を {@code AUTO_PASSED} に進める推奨。</li>
 *   <li>{@code AUTO_FLAG}: {@code WARN} のみ検知。{@code AUTO_FLAGGED} へ進め SYSTEM_ADMIN レビュー待ち。</li>
 *   <li>{@code AUTO_BLOCK}: {@code BLOCK} を 1 件でも検知。即座に {@code BLOCKED} とし配信阻止。</li>
 * </ul>
 */
public enum SuggestedModerationAction {
    AUTO_PASS,
    AUTO_FLAG,
    AUTO_BLOCK
}
