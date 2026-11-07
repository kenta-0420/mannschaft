package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括操作リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkActionRequest {

    @NotEmpty
    private final List<Long> ids;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden=true)
    private com.mannschaft.app.cms.service.BlogRanchBulkCaptureContext ranchCaptureContext;
    public void armRanchCapture(com.mannschaft.app.cms.service.BlogRanchBulkCaptureContext context) { ranchCaptureContext=context; }
    public void clearRanchCapture() { ranchCaptureContext=null; }
    @NotBlank
    private final String action;
}
