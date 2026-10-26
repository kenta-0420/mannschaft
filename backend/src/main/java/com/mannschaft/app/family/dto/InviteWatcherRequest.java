package com.mannschaft.app.family.dto;

import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareRelationship;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 見守り者招待リクエストDTO。ケア対象者が見守り者を招待する際に使用する。F03.12。
 */
@Getter
@NoArgsConstructor
public class InviteWatcherRequest {
    @NotNull
    private Long watcherUserId;
    @NotNull
    private CareCategory careCategory;
    @NotNull
    private CareRelationship relationship;
    private Boolean isPrimary;
}
