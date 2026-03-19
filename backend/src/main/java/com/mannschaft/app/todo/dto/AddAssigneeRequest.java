package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 担当者追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddAssigneeRequest {

    @NotNull
    private final Long userId;
}
