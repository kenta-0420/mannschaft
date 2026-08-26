package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * 村ニュースレター号 公開範囲切替リクエスト（F17.1 ②-4・設計書 §8.1 / §4.7.2）。
 *
 * <p>{@code VILLAGE_MEMBERS}↔{@code PUBLIC} の 2 値のみ。{@code version} は楽観ロック（設計書 §4.4）。</p>
 *
 * @param visibility 切替後の公開範囲（必須）
 * @param version    楽観ロック版番号（必須）
 */
@Builder
public record NewsletterVisibilityUpdateRequest(
        @NotNull(message = "visibility は必須です")
        VillageNewsletterVisibility visibility,

        @NotNull(message = "version は必須です")
        Long version
) {}
