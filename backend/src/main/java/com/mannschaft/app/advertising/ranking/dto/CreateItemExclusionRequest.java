package com.mannschaft.app.advertising.ranking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 備品除外設定作成リクエストDTO（SYSTEM_ADMIN向け）。
 */
@Getter
@NoArgsConstructor
public class CreateItemExclusionRequest {

    @NotBlank
    @Size(max = 200)
    private String normalizedName;

    @Size(max = 300)
    private String reason;
}
