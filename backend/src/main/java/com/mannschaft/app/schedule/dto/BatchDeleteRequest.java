package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括削除リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BatchDeleteRequest {

    @NotEmpty
    @Size(max = 50)
    private final List<Long> ids;
}
