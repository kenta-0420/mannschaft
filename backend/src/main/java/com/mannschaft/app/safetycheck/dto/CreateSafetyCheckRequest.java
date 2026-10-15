package com.mannschaft.app.safetycheck.dto;

import com.mannschaft.app.safetycheck.entity.SafetyCheckSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 安否確認作成リクエストDTO。
 *
 * <p>{@code sourceType} は F09.16 居住実態管理からの連携時に {@code ORG_WIDE} をセットする。
 * 通常の手動発信では {@code null}（または {@code MANUAL}）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateSafetyCheckRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 1000)
    private final String message;

    @NotNull
    private final String scopeType;

    @NotNull
    private final Long scopeId;

    private final Boolean isDrill;

    private final Integer reminderIntervalMinutes;

    private final Long templateId;

    /**
     * 安否確認の発生源種別（オプション）。
     *
     * <p>F09.16 OrgWideSafetyCheckService からの連携時は {@code ORG_WIDE} をセットする。
     * 通常の REST API 経由（F03.6 既存フロー）では {@code null} のままで構わない。</p>
     */
    @Setter
    private SafetyCheckSourceType sourceType;
}
