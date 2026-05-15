package com.mannschaft.app.village.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 村ロビーチャネル情報レスポンス（F17.1 Phase 1 B9 §4.10.1）。
 *
 * <p>呼び出しユーザーが村ロビーチャネルにメッセージ送信する際の入口情報。
 * 実際のメッセージ送受信は既存 {@code /api/v1/chat/channels/{chatChannelId}/messages} を使う。</p>
 *
 * @param chatChannelId     対応する chat_channels.id（既存 Long PK）
 * @param channelType       常に {@code "VILLAGE_LOBBY"}
 * @param villageId         村 UUIDv7
 * @param todayThreadDate   本日の日次スレッド対象日（NULL 可: 1 件もない場合）
 * @param todayThreadId     本日の日次スレッド ID（NULL 可）
 */
public record LobbyChannelResponse(
        Long chatChannelId,
        String channelType,
        UUID villageId,
        LocalDate todayThreadDate,
        UUID todayThreadId
) {
}
