package com.mannschaft.app.safetycheck.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 安否確認一括回答リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkRespondRequest {

    @NotEmpty
    @Size(max = 100)
    @Valid
    private final List<BulkRespondItem> items;

    /**
     * 一括回答の個別アイテム。
     */
    @Getter
    @RequiredArgsConstructor
    public static class BulkRespondItem {

        @NotNull
        private final Long userId;

        @NotBlank
        private final String status;

        @Size(max = 200)
        private final String message;
    }
}
