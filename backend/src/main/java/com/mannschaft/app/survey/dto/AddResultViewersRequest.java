package com.mannschaft.app.survey.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 結果閲覧者追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddResultViewersRequest {

    @NotEmpty
    private final List<Long> userIds;
}
