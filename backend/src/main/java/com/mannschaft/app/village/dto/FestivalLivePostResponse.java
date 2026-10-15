package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageFestivalLivePostEntity;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.2 Wave2 ③お祭りの実況投稿の紐付けレスポンス（設計書 §5.6）。
 *
 * <p>投稿本文は既存 VILLAGE タイムライン投稿（{@code timelinePostId}）側にあり、FE は
 * その ID からタイムライン投稿を解決して表示する。timeline 側 {@code deleted_at} 済みの
 * 紐付けはサービス層の一覧生成で除外される（AC-17c）。</p>
 */
@Builder
public record FestivalLivePostResponse(
        UUID festivalId,
        Long timelinePostId,
        LocalDateTime createdAt) {

    public static FestivalLivePostResponse of(VillageFestivalLivePostEntity entity) {
        return FestivalLivePostResponse.builder()
                .festivalId(entity.getFestivalId())
                .timelinePostId(entity.getTimelinePostId())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
