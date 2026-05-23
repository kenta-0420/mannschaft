package com.mannschaft.app.todo.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * TODO更新リクエストDTO。
 */
@Getter
public class UpdateTodoRequest {

    @NotBlank
    @Size(max = 300)
    private final String title;

    private final String description;

    private final Long projectId;

    private final Long milestoneId;

    private final String priority;

    /** 開始日（nullable）。ガントバー表示に使用する。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate dueDate;

    private final LocalTime dueTime;

    private final Integer sortOrder;

    @JsonCreator
    public UpdateTodoRequest(
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("projectId") Long projectId,
            @JsonProperty("milestoneId") Long milestoneId,
            @JsonProperty("priority") String priority,
            @JsonProperty("startDate") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @JsonProperty("dueDate") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dueDate,
            @JsonProperty("dueTime") LocalTime dueTime,
            @JsonProperty("sortOrder") Integer sortOrder) {
        this.title = title;
        this.description = description;
        this.projectId = projectId;
        this.milestoneId = milestoneId;
        this.priority = priority;
        this.startDate = startDate;
        this.dueDate = dueDate;
        this.dueTime = dueTime;
        this.sortOrder = sortOrder;
    }
}
