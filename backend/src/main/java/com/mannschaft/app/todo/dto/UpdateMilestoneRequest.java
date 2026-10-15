package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * マイルストーン更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateMilestoneRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final LocalDate dueDate;

    private final Short sortOrder;
}
