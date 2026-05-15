package com.mannschaft.app.village.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ダッシュボード村フィード（F17.1 §4.13）の 1 アイテム DTO。
 *
 * <p>個人ダッシュボードでピン留め村横断の「最新動き」を見せるための要約。
 * {@code TIMELINE}（タイムライン投稿） / {@code LOBBY}（井戸端メッセージ）の 2 種。</p>
 *
 * @param type        {@code TIMELINE} / {@code LOBBY}
 * @param villageId   投稿が属する村 ID
 * @param villageName 村名（表示用、毎回引きに行かなくて済むよう同梱）
 * @param postId      TIMELINE 型のみ: timeline_post の ID
 * @param messageId   LOBBY 型のみ: chat_message の ID
 * @param snippet     本文抜粋（200 文字まで）
 * @param createdAt   投稿日時
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record VillageFeedItemResponse(
        String type,
        UUID villageId,
        String villageName,
        Long postId,
        Long messageId,
        String snippet,
        LocalDateTime createdAt
) {}
