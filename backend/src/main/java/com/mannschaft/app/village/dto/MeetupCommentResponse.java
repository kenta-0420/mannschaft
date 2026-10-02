package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMeetupCommentEntity;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.2 Wave1 ②寄合後半戦 — コメントレスポンス（設計書 §4.4）。
 *
 * <p>{@code displayName} は<strong>村ニックネーム</strong>で解決する（実名スナップショット禁止・§10 G4）。</p>
 */
@Builder
public record MeetupCommentResponse(
        UUID id,
        UUID meetupId,
        Long authorUserId,
        String displayName,
        String body,
        LocalDateTime createdAt) {

    public static MeetupCommentResponse of(VillageMeetupCommentEntity entity, String displayName) {
        return MeetupCommentResponse.builder()
                .id(entity.getId())
                .meetupId(entity.getMeetupId())
                .authorUserId(entity.getAuthorUserId())
                .displayName(displayName)
                .body(entity.getBody())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
