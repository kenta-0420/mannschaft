package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * TODO作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTodoRequest {

    @NotBlank
    @Size(max = 300)
    private final String title;

    private final String description;

    private final Long projectId;

    private final Long milestoneId;

    private final String priority;

    private final LocalDate dueDate;

    private final LocalTime dueTime;

    private final Integer sortOrder;

    private final List<Long> assigneeIds;
}
