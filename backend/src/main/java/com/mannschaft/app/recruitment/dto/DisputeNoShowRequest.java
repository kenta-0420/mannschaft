package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(max = 10000)
    private String reason;
}
