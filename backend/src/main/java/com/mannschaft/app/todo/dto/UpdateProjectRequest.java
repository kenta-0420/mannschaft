package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * プロジェクト更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateProjectRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String description;

    @Size(max = 10)
    private final String emoji;

    @Size(max = 7)
    private final String color;

    private final LocalDate dueDate;

    private final String visibility;

    private final String status;
}
