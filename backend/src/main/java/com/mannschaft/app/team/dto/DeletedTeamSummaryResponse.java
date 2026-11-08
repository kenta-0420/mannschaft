package com.mannschaft.app.team.dto;

import com.mannschaft.app.team.entity.TeamEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/** システム管理者の復元対象一覧専用。Entity内部や加盟・役割情報は公開しない。 */
@Getter
@Builder
public class DeletedTeamSummaryResponse {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String name;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String slug;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final Instant deletedAt;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final TeamEntity.LifecycleStatus lifecycleStatus;
}
