package com.mannschaft.app.todo.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * TODO作成リクエストDTO。
 */
@Getter
public class CreateTodoRequest {

    @NotBlank
    @Size(max = 300)
    private final String title;

    private final String description;

    private final Long projectId;

    private final Long milestoneId;

    private final String priority;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate dueDate;

    private final LocalTime dueTime;

    private final Integer sortOrder;

    private final List<Long> assigneeIds;

    private final Long parentId;

    /** 開始日（nullable）。ガントバー表示に使用する。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate startDate;

    /** 既存スケジュールとの連携ID（nullable）。 */
    private final Long linkedScheduleId;

    /** 手動設定の進捗率（nullable。指定時は0〜100の範囲）。 */
    @DecimalMin("0.00")
    @DecimalMax("100.00")
    private final BigDecimal progressRate;

    /**
     * trueの場合、TODO作成時に新規スケジュールも自動生成する（デフォルトfalse）。
     */
    private final Boolean createLinkedSchedule;

    @JsonCreator
    public CreateTodoRequest(
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("projectId") Long projectId,
            @JsonProperty("milestoneId") Long milestoneId,
            @JsonProperty("priority") String priority,
            @JsonProperty("dueDate") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dueDate,
            @JsonProperty("dueTime") LocalTime dueTime,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("assigneeIds") List<Long> assigneeIds,
            @JsonProperty("parentId") Long parentId,
            @JsonProperty("startDate") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @JsonProperty("linkedScheduleId") Long linkedScheduleId,
            @JsonProperty("progressRate") BigDecimal progressRate,
            @JsonProperty("createLinkedSchedule") Boolean createLinkedSchedule) {
        this.title = title;
        this.description = description;
        this.projectId = projectId;
        this.milestoneId = milestoneId;
        this.priority = priority;
        this.dueDate = dueDate;
        this.dueTime = dueTime;
        this.sortOrder = sortOrder;
        this.assigneeIds = assigneeIds;
        this.parentId = parentId;
        this.startDate = startDate;
        this.linkedScheduleId = linkedScheduleId;
        this.progressRate = progressRate;
        this.createLinkedSchedule = createLinkedSchedule;
    }
}
