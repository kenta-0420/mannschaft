package com.mannschaft.app.school.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 移動検知アラート解決リクエストDTO。 */
@Getter
@NoArgsConstructor
public class TransitionAlertResolveRequest {

    /** 解決理由（必須）。 */
    @NotBlank
    private String note;
}
