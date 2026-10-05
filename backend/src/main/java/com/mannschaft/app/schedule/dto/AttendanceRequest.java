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

    /** Controllerがtrusted impersonation属性と今回proxyから固定する。公開JSON契約を増やさない。 */
    public void captureRanchResponseOrigin(boolean self) {
        ranchSelfResponse = self;
    }
}
