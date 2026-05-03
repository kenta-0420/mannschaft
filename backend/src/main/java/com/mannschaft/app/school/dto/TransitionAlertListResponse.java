package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/** 移動検知アラート一覧レスポンスDTO。 */
@Getter
@Builder
public class TransitionAlertListResponse {

    /** クラスチームID。 */
    private Long teamId;

    /** 対象日。 */
    private LocalDate attendanceDate;

    /** アラート一覧。 */
    private List<TransitionAlertResponse> alerts;

    /** アラートの総件数。 */
    private int totalCount;

    /** 未解決アラートの件数。 */
    private int unresolvedCount;
}
