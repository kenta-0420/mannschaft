package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 通報一括対応リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkResolveRequest {

    @NotEmpty
    private final List<Long> reportIds;

    @NotBlank
    private final String actionType;

    @Size(max = 2000)
    private final String note;

    @Size(max = 100)
    private final String guidelineSection;
}
