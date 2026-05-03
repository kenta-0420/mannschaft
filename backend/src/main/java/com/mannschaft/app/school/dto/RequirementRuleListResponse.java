package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** F03.13 Phase 10: 出席要件規程 一覧レスポンス DTO。 */
@Getter
@Builder
public class RequirementRuleListResponse {

    /** 規程一覧 */
    private List<RequirementRuleResponse> rules;

    /** 総件数 */
    private int total;
}
