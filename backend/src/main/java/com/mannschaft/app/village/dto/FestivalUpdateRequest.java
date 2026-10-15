package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * F17.1 Phase 2 U5 — 村お祭り更新リクエスト。
 *
 * <p>すべて optional。{@code null} のフィールドは更新対象外。</p>
 *
 * <p>期間整合性は Service 側で「指定された方の値 + 既存値」で再評価する。
 * テーマ色フォーマットは Service 側で検証する。</p>
 */
public record FestivalUpdateRequest(
        @Size(max = 100) String title,
        @Size(max = 5000) String description,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        @Size(max = 255) String bannerR2Key,
        @Size(max = 7) String themeColorHex) {
}
