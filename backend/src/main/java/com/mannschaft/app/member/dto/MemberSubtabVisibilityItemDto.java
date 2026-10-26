package com.mannschaft.app.member.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.dashboard.MinRole;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * CMP-260919-1140 Phase 1: サブタブ可視性設定1件分のレスポンスDTO。
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemberSubtabVisibilityItemDto {

    private final String subtabKey;
    private final MinRole minRole;
    private final boolean isDefault;
    private final MemberSubtabUpdatedByDto updatedBy;
    private final Instant updatedAt;
}
