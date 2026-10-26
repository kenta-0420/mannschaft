package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 個人成績一括入力リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PlayerStatBatchRequest {

    @NotNull
    private final Long version;

    @NotNull
    private final List<PlayerStatRequest> stats;
}
