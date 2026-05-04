package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コルクボードカードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CorkboardCardResponse {

    private final Long id;
    private final Long corkboardId;
    /**
     * F09.8 積み残し件1: カードの主セクション ID（V9.097 で追加。未所属時は {@code null}）。
     * フロント (`corkboard/[id].vue`) はこの値を直接読んで紐付け表示を判定する。
     */
    private final Long sectionId;
    private final String cardType;
    private final String referenceType;
    private final Long referenceId;
    private final String contentSnapshot;
    private final String title;
    private final String body;
    private final String url;
    private final String ogTitle;
    private final String ogImageUrl;
    private final String ogDescription;
    private final String colorLabel;
    private final String cardSize;
    private final Integer positionX;
    private final Integer positionY;
    private final Integer zIndex;
    private final String userNote;
    /**
     * F09.8 件3' (V9.098): ピン止め時付箋メモの専用色。
     * {@code null} はカラーラベル ({@link #colorLabel}) と同色とみなすことを意味する。
     */
    private final String noteColor;
    private final LocalDateTime autoArchiveAt;
    private final Boolean isArchived;
    private final Boolean isPinned;
    private final LocalDateTime pinnedAt;
    private final Boolean isRefDeleted;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
