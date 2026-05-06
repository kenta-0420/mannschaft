package com.mannschaft.app.todo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * TODO更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
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
}
