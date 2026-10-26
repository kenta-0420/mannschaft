package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 公開ステータス変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PublishRequest {

    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private com.mannschaft.app.cms.service.BlogRanchCaptureContext ranchCaptureContext;
    /** 源非TX入口が設定する。クライアント入力からの設定は行わない。 */
    public void armRanchCapture(com.mannschaft.app.cms.service.BlogRanchCaptureContext context) { ranchCaptureContext=context; }
    public void clearRanchCapture() { ranchCaptureContext=null; }


    @NotBlank
    private final String status;

    private final LocalDateTime publishedAt;
    private final String rejectionReason;
}
