package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * F02.5 行動メモレスポンス DTO。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionMemoResponse {

    private Long id;

    @JsonProperty("memo_date")
    private LocalDate memoDate;

    private String content;

    private ActionMemoMood mood;

    @JsonProperty("related_todo_id")
    private Long relatedTodoId;

    @JsonProperty("timeline_post_id")
    private Long timelinePostId;

    @Builder.Default
    private List<ActionMemoTagSummary> tags = List.of();

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
