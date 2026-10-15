package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * ZIP 一括ダウンロードリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class DownloadZipRequest {

    @NotBlank
    private final String scopeType;

    @NotNull
    private final Long scopeId;

    private final LocalDate issuedFrom;

    private final LocalDate issuedTo;
}
