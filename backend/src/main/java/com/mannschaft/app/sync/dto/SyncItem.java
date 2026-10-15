package com.mannschaft.app.sync.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * オフラインキューの同期アイテム1件。
 * フロントエンドがオフライン中にキューイングした個別リクエストに相当する。
 */
@Getter
@RequiredArgsConstructor
public class SyncItem {

    @NotBlank
    private final String clientId;

    @NotBlank
    private final String method;

    @NotBlank
    private final String path;

    private final Map<String, Object> body;

    @NotBlank
    private final String createdAt;

    private final Long version;
}
