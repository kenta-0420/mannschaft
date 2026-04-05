package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * TODO一括ステータス変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkStatusChangeRequest {

    @NotEmpty
    private final List<Long> todoIds;

    @NotBlank
    private final String status;
}
