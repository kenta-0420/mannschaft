package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイブレーク作成・更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class TiebreakerRequest {

    @NotNull
    private final Integer priority;

    @NotNull
    private final String criteria;

    private final String direction;
}
