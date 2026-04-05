package com.mannschaft.app.role.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 自分の所属組織レスポンス（GET /api/v1/me/organizations 用）。
 */
@Getter
@RequiredArgsConstructor
public class MyOrganizationResponse {

    private final Long id;
    private final String name;
    /** アイコンURL（DB未実装のため常にnull）。 */
    private final String iconUrl;
    private final String visibility;
    private final int memberCount;
    private final String role;
    private final LocalDateTime joinedAt;
    @JsonProperty("isArchived")
    private final boolean isArchived;
}
