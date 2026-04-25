package com.mannschaft.app.family.dto;

import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareRelationship;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ケア対象者招待リクエストDTO。見守り者がケア対象者を招待する際に使用する。F03.12。
 */
@Getter
@NoArgsConstructor
public class InviteRecipientRequest {
    @NotNull
    private Long careRecipientUserId;
    @NotNull
    private CareCategory careCategory;
    @NotNull
    private CareRelationship relationship;
}
