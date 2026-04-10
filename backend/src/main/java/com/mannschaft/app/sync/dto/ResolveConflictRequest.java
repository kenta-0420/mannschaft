package com.mannschaft.app.sync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コンフリクト解決リクエスト。
 * resolution が MANUAL_MERGE の場合は mergedData が必須。
 */
@Getter
@RequiredArgsConstructor
public class ResolveConflictRequest {

    @NotBlank
    @Pattern(regexp = "CLIENT_WIN|SERVER_WIN|MANUAL_MERGE")
    private final String resolution;

    /** MANUAL_MERGE 時のマージ後データ（JSON文字列）。CLIENT_WIN / SERVER_WIN 時は不要。 */
    private final String mergedData;
}
