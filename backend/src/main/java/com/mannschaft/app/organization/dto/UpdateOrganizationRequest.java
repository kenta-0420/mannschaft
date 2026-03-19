package com.mannschaft.app.organization.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 組織更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateOrganizationRequest {

    private final String name;
    private final String nameKana;
    private final String nickname1;
    private final String nickname2;
    private final String prefecture;
    private final String city;
    private final String visibility;
    private final String hierarchyVisibility;
    private final Boolean supporterEnabled;

    @NotNull
    private final Long version;
}
