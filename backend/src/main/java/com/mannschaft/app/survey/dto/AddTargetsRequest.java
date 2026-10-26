package com.mannschaft.app.survey.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 配信対象追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddTargetsRequest {

    @NotEmpty
    private final List<Long> userIds;
}
