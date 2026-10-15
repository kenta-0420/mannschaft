package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ブロックリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class BlockRequest {

    @NotNull
    private final Long userId;

    private final String reason;
}
