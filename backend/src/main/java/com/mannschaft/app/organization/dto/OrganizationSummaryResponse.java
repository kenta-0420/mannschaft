package com.mannschaft.app.organization.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 組織サマリーレスポンス（一覧用）。
 */
@Getter
@RequiredArgsConstructor
public class OrganizationSummaryResponse {

    /** URL 識別子（カスタムスラッグ）。 */
    private final String id;
    private final String name;
    private final String orgType;
    private final String visibility;
    private final int memberCount;
}
