package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セルフレビュー結果リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SelfReviewRequest {

    @NotBlank
    private final String action;
}
