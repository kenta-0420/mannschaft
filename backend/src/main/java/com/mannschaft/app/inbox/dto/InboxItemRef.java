package com.mannschaft.app.inbox.dto;

import com.mannschaft.app.inbox.InboxSourceType;

/**
 * F04.11 統合通知インボックス：名寄せ（重複統合）で畳まれた構成メンバー 1 件の参照。
 *
 * <p>Phase 3 ① 名寄せで、同一終端実体（例: 同じ BLOG_POST）を指す複数ソース通知を 1 カードへ
 * 畳む際、代表 DTO の {@code groupMembers} に全構成メンバーの {@code (sourceType, sourceId)} を載せる。
 * FE は Phase 2 の bulk triage API で各メンバーへ一括適用し「片方だけ既読/アーカイブ」を防ぐ
 * （設計書: 03_business_logic.md §8）。</p>
 *
 * <p>{@code sourceId} は各ソースの<b>チャネル行 PK</b>（notifications.id / announcement_feeds.id 等）であり、
 * triage オーバーレイ（{@code inbox_item_states}）のキーとして使う。終端実体 ID ではない点に注意。</p>
 *
 * @param sourceType 通知ソース種別
 * @param sourceId   各ソース PK（triage キー）
 */
public record InboxItemRef(
        InboxSourceType sourceType,
        Long sourceId
) {
}
