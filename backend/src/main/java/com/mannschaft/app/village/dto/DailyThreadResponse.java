package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageLobbyDailyThreadEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 日次スレッドレスポンス（F17.1 Phase 1 B9 §4.10.2 / §4.10.3）。
 *
 * <p>井戸端会議の 1 日分のアーカイブ要約情報。発言本体は chat_messages から取得する。</p>
 *
 * @param id                日次スレッド UUIDv7
 * @param villageId         村 UUIDv7
 * @param threadDate        スレッド対象日
 * @param chatChannelId     対応するチャットチャネル ID（メッセージ取得用）
 * @param messageCount      キャッシュ済みメッセージ件数（読み出し時点）
 * @param summary           AI による日次サマリ（Phase 2 以降、現状は NULL）
 * @param createdAt         スレッドレコード作成日時
 */
public record DailyThreadResponse(
        UUID id,
        UUID villageId,
        LocalDate threadDate,
        Long chatChannelId,
        long messageCount,
        String summary,
        LocalDateTime createdAt
) {

    public static DailyThreadResponse of(VillageLobbyDailyThreadEntity e) {
        return new DailyThreadResponse(
                e.getId(),
                e.getVillageId(),
                e.getThreadDate(),
                e.getChatChannelId(),
                e.getMessageCountCache() != null ? e.getMessageCountCache() : 0L,
                e.getSummary(),
                e.getCreatedAt());
    }
}
