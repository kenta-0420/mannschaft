package com.mannschaft.app.chart.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 身体チャート一括更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateBodyMarksRequest {

    @NotNull
    @Valid
    private final List<ChartBodyMarkRequest> marks;
}
