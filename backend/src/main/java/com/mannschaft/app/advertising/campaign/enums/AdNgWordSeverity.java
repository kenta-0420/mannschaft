package com.mannschaft.app.advertising.campaign.enums;

/**
 * F09.17 Phase 11-b 自動 NG 辞書エントリの重大度。
 *
 * <ul>
 *   <li>{@code WARN}: 検出時にキャンペーンを {@code AUTO_FLAGGED} とし
 *       SYSTEM_ADMIN の手動審査に回す。最終承認は人間が下す。</li>
 *   <li>{@code BLOCK}: 検出時にキャンペーンを即座に {@code BLOCKED} とし
 *       配信開始を阻止する。薬機法・金商法・公序良俗違反のハイリスク語に適用。</li>
 * </ul>
 */
public enum AdNgWordSeverity {
    WARN,
    BLOCK
}
