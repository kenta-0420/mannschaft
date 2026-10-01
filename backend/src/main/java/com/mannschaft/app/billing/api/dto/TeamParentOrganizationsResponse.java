package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F01.2.1 §9.2 #17: シスアド手動付与の画面が出す、TEAM スコープの親組織の選択肢。
 */
@Getter
@Builder
@Schema(name = "BillingTeamParentOrganizationsResponse", description = "チームの ACTIVE な親組織の候補と代表親組織")
public class TeamParentOrganizationsResponse {

    @Schema(description = "ACTIVE な親組織の ID 一覧（無所属は空）")
    private final List<Long> organizationIds;

    @Schema(description = "組織を明示しなかったときに記録される代表親組織（§9.3。無所属は null）", nullable = true)
    private final Long representativeOrganizationId;
}
