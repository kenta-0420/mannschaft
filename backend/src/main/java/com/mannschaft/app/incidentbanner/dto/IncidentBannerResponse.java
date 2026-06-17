package com.mannschaft.app.incidentbanner.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 障害告知バナーの管理レスポンス（シスアド用）。
 *
 * <p>原文・公開状態・各言語の翻訳一覧を含む。</p>
 */
@Getter
@Builder
public class IncidentBannerResponse {

    private final String id;
    private final String level;
    private final String pagePattern;
    private final boolean published;
    private final String originalLanguage;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /** 各言語の翻訳メッセージ一覧。 */
    private final List<TranslationDto> translations;

    /**
     * 翻訳メッセージ。
     */
    @Getter
    @Builder
    public static class TranslationDto {
        private final String language;
        private final String message;
    }
}
