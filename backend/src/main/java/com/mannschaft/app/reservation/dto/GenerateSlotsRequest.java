package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 週間テンプレート一括生成リクエストDTO（F03.4.2 §4 generate）。
 */
@Getter
@RequiredArgsConstructor
public class GenerateSlotsRequest {

    /** 何週先まで生成するか（1〜4）。省略時 4（=28日先まで）。 */
    @Min(1)
    @Max(4)
    private final Integer weeks;
}
