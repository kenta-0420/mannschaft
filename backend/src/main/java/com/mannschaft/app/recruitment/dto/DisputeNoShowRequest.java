package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F03.11 Phase 5b: NO_SHOW 異議申立リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class DisputeNoShowRequest {

    /** 異議申立の理由。 */
    @NotBlank
    private String reason;
}
