package com.mannschaft.app.organization.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * 組織サマリーレスポンス（一覧用）。
 */
@Getter
@RequiredArgsConstructor
public class OrganizationSummaryResponse {

    private final UUID id;
    private final String name;
    private final String orgType;
    private final String visibility;
    private final int memberCount;
}
