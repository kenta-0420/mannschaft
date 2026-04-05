package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TODOステータス変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class TodoStatusChangeRequest {

    @NotBlank
    private final String status;
}
