package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * F02.5 行動メモレスポンス DTO。
 *
 * <p><b>Phase 3 追加フィールド</b>: category / duration_minutes / progress_rate /
 * completes_todo / posted_team_id。</p>
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

    /** Phase 3: メモカテゴリ */
    private ActionMemoCategory category;

    /** Phase 3: 実績時間（分） */
    @JsonProperty("duration_minutes")
    private Integer durationMinutes;

    /** Phase 3: 進捗率 */
    @JsonProperty("progress_rate")
    private BigDecimal progressRate;

    private ActionMemoMood mood;

    @JsonProperty("related_todo_id")
    private Long relatedTodoId;

    /** Phase 3: TODO完了フラグ */
    @JsonProperty("completes_todo")
    private boolean completesTodo;

    @JsonProperty("timeline_post_id")
    private Long timelinePostId;

    /** Phase 3: チームタイムライン投稿済みチームID */
    @JsonProperty("posted_team_id")
    private Long postedTeamId;

    @Builder.Default
    private List<ActionMemoTagSummary> tags = List.of();

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
