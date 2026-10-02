package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMeetupTodoEntity;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.2 Wave1 ②寄合後半戦 — 宿題 TODO レスポンス（設計書 §4.4）。
 *
 * <p>{@code assigneeDisplayName} は<strong>村ニックネーム</strong>で解決する（未割当時は {@code null}）。
 * {@code doneAt} が非 null なら完了済み。</p>
 */
@Builder
public record MeetupTodoResponse(
        UUID id,
        UUID meetupId,
        String title,
        Long assigneeUserId,
        String assigneeDisplayName,
        LocalDateTime doneAt,
        Long createdBy,
        LocalDateTime createdAt) {

    public static MeetupTodoResponse of(VillageMeetupTodoEntity entity, String assigneeDisplayName) {
        return MeetupTodoResponse.builder()
                .id(entity.getId())
                .meetupId(entity.getMeetupId())
                .title(entity.getTitle())
                .assigneeUserId(entity.getAssigneeUserId())
                .assigneeDisplayName(assigneeDisplayName)
                .doneAt(entity.getDoneAt())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
