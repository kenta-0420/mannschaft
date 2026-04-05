package com.mannschaft.app.organization.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 組織作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateOrganizationRequest {

    @NotBlank
    private final String name;

    @NotBlank
    private final String orgType;

    private final String prefecture;
    private final String city;
    private final String visibility;
    private final Long parentOrganizationId;
}
