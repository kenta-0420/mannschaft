package com.mannschaft.app.organization.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 組織詳細レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class OrganizationResponse {

    private final Long id;
    private final String name;
    private final String nameKana;
    private final String nickname1;
    private final String nickname2;
    private final String orgType;
    private final Long parentOrganizationId;
    private final String prefecture;
    private final String city;
    private final String visibility;
    private final String hierarchyVisibility;
    private final Boolean supporterEnabled;
    private final Long version;
    private final int memberCount;
    private final String iconUrl;
    private final String bannerUrl;
    private final LocalDateTime archivedAt;
    private final LocalDateTime createdAt;
}
