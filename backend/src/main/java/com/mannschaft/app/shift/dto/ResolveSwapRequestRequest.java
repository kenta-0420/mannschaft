package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフト交代リクエスト承認・却下DTO。
 */
@Getter
@RequiredArgsConstructor
public class ResolveSwapRequestRequest {

    @NotNull
    private final String action;

    @Size(max = 500)
    private final String adminNote;
}
