package com.mannschaft.app.corkboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * コルクボードカードレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class CorkboardCardResponse {

    private final Long id;
    private final Long corkboardId;
    /**
     * F09.8 積み残し件1: カードの主セクション ID・参照情報。
     */
    private final CardReferenceDto reference;
    private final CardContentDto content;
    private final CardLayoutDto layout;
    private final CardStyleDto style;
    private final CardStateDto state;
    private final CardAuditDto audit;

    public record CardReferenceDto(Long sectionId, String cardType, String referenceType,
                                   Long referenceId, String contentSnapshot) {}

    public record CardContentDto(String title, String body, String url,
                                 String ogTitle, String ogImageUrl, String ogDescription) {}

    public record CardLayoutDto(Integer positionX, Integer positionY, Integer zIndex, String cardSize) {}

    public record CardStyleDto(String colorLabel, String noteColor) {}

    public record CardStateDto(Boolean isArchived, Boolean isPinned, LocalDateTime pinnedAt,
                               LocalDateTime autoArchiveAt, Boolean isRefDeleted) {}

    public record CardAuditDto(String userNote, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
