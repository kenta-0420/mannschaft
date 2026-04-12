package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.DisputeResolution;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F03.11 Phase 5b: NO_SHOW 異議申立解決リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class ResolveDisputeRequest {

    /** 解決結果 (REVOKED or UPHELD)。 */
    @NotNull
    private DisputeResolution resolution;

    /** 管理者メモ（任意）。 */
    private String adminNote;
}
