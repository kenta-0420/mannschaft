package com.mannschaft.app.forms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * フォーム提出作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFormSubmissionRequest {

    @NotNull
    private final Long templateId;

    private final Boolean submitImmediately;

    @Valid
    private final List<SubmissionValueRequest> values;
}
