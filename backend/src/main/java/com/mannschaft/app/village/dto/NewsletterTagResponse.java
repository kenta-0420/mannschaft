package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.UUID;

/**
 * 村ニュースレタータグ レスポンス DTO（F17.1 ②-4・設計書 §4.7 / §8.1）。
 *
 * @param id        タグ ID（UUIDv7）
 * @param villageId 村 ID
 * @param name      タグ名
 * @param color     表示色（#RRGGBB）
 * @param sortOrder 表示順
 * @param version   楽観ロック版番号
 */
@Builder
public record NewsletterTagResponse(
        UUID id,
        UUID villageId,
        String name,
        String color,
        Integer sortOrder,
        Long version
) {}
