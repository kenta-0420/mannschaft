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

    @Schema(description = "候補の組織の表示用情報（画面の選択肢。削除済みの組織は含まれない）")
    private final List<Organization> organizations;

    @Schema(description = "組織を明示しなかったときに記録される代表親組織（§9.3。無所属は null）", nullable = true)
    private final Long representativeOrganizationId;

    /** 親組織の候補1件。 */
    @Getter
    @Builder
    @Schema(name = "BillingTeamParentOrganization", description = "チームの親組織の候補")
    public static class Organization {

        @Schema(description = "組織 ID")
        private final Long organizationId;

        @Schema(description = "組織名")
        private final String name;

        @Schema(description = "組織の slug")
        private final String slug;
    }
}
