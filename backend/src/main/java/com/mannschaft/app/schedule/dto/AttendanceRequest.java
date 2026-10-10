package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 出欠回答リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AttendanceRequest {

    @NotNull
    private final String status;

    @Size(max = 500)
    private final String comment;

    private final List<SurveyResponseRequest> surveyResponses;

    /** HTTP入力から受け取らない、trusted request actorの今回本人/非本人区分。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean ranchSelfResponse;

    /** Guard callbackだけが有効化する今回捕捉。HTTP入出力には含めない。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private boolean ranchCaptureArmed;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private ScheduleRanchRewardPayload ranchCapturedPayload;

    /** 同じリクエストインスタンスの古い捕捉を再利用しない。 */
    public void armRanchCapture() {
        ranchCaptureArmed = true;
        ranchCapturedPayload = null;
    }

    /** 元業務が今回の本人初回証拠を同じ行へ保存するときだけ固定する。 */
    public void recordRanchCapture(ScheduleRanchRewardPayload payload) {
        if (ranchCaptureArmed) ranchCapturedPayload = payload;
    }

    /** proxy正常復帰後に一回消費し、残ったrequestから再配送しない。 */
    public ScheduleRanchRewardPayload takeRanchCapture() {
        var payload = ranchCapturedPayload;
        ranchCapturedPayload = null;
        ranchCaptureArmed = false;
        return payload;
    }

    /** Controllerがtrusted impersonation属性と今回proxyから固定する。公開JSON契約を増やさない。 */
    public void captureRanchResponseOrigin(boolean self) {
        ranchSelfResponse = self;
    }
}
