package com.mannschaft.app.corkboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.8.1 ピン止め切替リクエストDTO。
 * 個人コルクボードのカードに対するピン止め状態のトグルに使用する。
 */
@Getter
@RequiredArgsConstructor
public class PinCardRequest {

    /** ピン止めする (true) / 解除する (false) */
    @NotNull
    private final Boolean isPinned;
}
