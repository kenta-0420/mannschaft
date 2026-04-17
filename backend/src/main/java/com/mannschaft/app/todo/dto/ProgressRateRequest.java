package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 進捗率更新リクエストDTO。手動モードのTODOにのみ適用可能。
 */
@Getter
@RequiredArgsConstructor
public class ProgressRateRequest {

    /** 設定する進捗率（0.00〜100.00、必須）。 */
    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("100.00")
    private final BigDecimal progressRate;
}
