package com.mannschaft.app.village.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 村内検索（F17.1 §4.12）の検索結果アイテム DTO。
 *
 * <p>POST / MESSAGE / MEMBER の 3 種を 1 つの形に統合する。
 * type ごとに利用フィールドが異なるため、未使用フィールドは {@code null} 許容。
 * Jackson は {@link JsonInclude.Include#NON_NULL} で空フィールドを抑止する。</p>
 *
 * <p><b>個人特定情報の保護（§6.1）</b>: MEMBER 型でも {@code userId} は持たない。
 * nickname と avatarR2Key（公開キー）のみ返却する。</p>
 *
 * @param type        結果タイプ: {@code POST} / {@code MESSAGE} / {@code MEMBER}
 * @param id          POST: bulletin_thread or timeline_post の ID 文字列 ／
 *                    MESSAGE: chat_message の ID 文字列 ／
 *                    MEMBER: village_memberships.id（UUIDv7 文字列、個人特定不可）
 * @param postKind    POST 種別: {@code BULLETIN_THREAD} / {@code TIMELINE_POST}（POST 型のみ）
 * @param title       POST 型のみ: 投稿タイトル
 * @param snippet     POST/MESSAGE 型: 本文の抜粋（最大 200 文字）
 * @param nickname    MEMBER 型: 村内ニックネーム
 * @param avatarR2Key MEMBER 型: 村人アバター R2 キー
 * @param channelId   MESSAGE 型: 投稿があったチャネル ID
 * @param createdAt   POST/MESSAGE 型: 投稿日時
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record VillageInternalSearchItemResponse(
        String type,
        String id,
        String postKind,
        String title,
        String snippet,
        String nickname,
        String avatarR2Key,
        Long channelId,
        LocalDateTime createdAt
) {}
