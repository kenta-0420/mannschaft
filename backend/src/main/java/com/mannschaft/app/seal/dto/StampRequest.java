package com.mannschaft.app.seal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 押印リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class StampRequest {

    @NotNull
    private final Long sealId;

    @NotNull
    private final String targetType;

    @NotNull
    private final Long targetId;

    private final String stampDocumentHash;
}
