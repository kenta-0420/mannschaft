package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ページレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TeamPageResponse {

    private final Long id;
    private final Long teamId;
    private final Long organizationId;
    private final String title;
    private final String slug;
    private final String pageType;
    private final Short year;
    private final String description;
    private final String coverImageS3Key;
    private final String visibility;
    private final String status;
    private final Boolean allowSelfEdit;
    private final Integer sortOrder;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<SectionResponse> sections;
    private final List<MemberProfileResponse> members;
}
